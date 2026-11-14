import React, { useState, useEffect, useRef } from 'react';
import { ShieldOff } from 'lucide-react';
import { Header } from './components/Header';
import { PowerSwitch } from './components/PowerSwitch';
import { StatsCard } from './components/StatsCard';
import { DeviceInfoBar } from './components/DeviceInfoBar';
import { TelegramBanner } from './components/TelegramBanner';
import { Footer } from './components/Footer';
import { AddServerModal } from './components/AddServerModal';
import { ServerListModal } from './components/ServerListModal';
import { ConfigExportModal } from './components/ConfigExportModal';
import { SettingsModal } from './components/SettingsModal';
import { NavigationDrawer } from './components/NavigationDrawer';
import { BalyBadge } from './components/BalyBadge';
import { ConnectionHistoryModal } from './components/ConnectionHistoryModal';
import { AppExclusionsModal } from './components/AppExclusionsModal';
import { ProxyProfilesModal, SavedProxyProfile } from './components/ProxyProfilesModal';
import { DiagnosticsModal } from './components/DiagnosticsModal';

import { WireguardServer, ConnectionState, AppSettings, NetworkTelemetry, ConnectionLogEntry } from './types';
import { getInitialServers, parseWireguardUri, USER_DEFAULT_URI } from './utils/wireguard';
import { measureRealPing } from './utils/realSpeed';
import { fetchRealExternalIpAndCountry } from './utils/geoIp';
import VpnBridge, { InstalledApp } from './utils/vpnBridge';
import ShizziBridge from './utils/shizziBridge';
import { decryptBackup, encryptBackup } from './utils/backup';
import { CellularInfo, EMPTY_CELLULAR_INFO, readCellularInfo } from './utils/cellularInfo';

const STORAGE_SERVERS_KEY = 'seloomwarp_servers_v1';
const STORAGE_SETTINGS_KEY = 'seloomwarp_settings_v1';
const STORAGE_ACTIVE_SERVER_KEY = 'seloomwarp_active_id_v1';
const STORAGE_CONNECTION_LOG_KEY = 'seloomwarp_connection_log_v1';

const STORAGE_RECONNECT_INTENT_KEY = 'nexalink_reconnect_intent_v1';
const STORAGE_PROXY_PROFILES_KEY = 'nexalink_proxy_profiles_v1';
const delay = (milliseconds: number) => new Promise((resolve) => window.setTimeout(resolve, milliseconds));

function readReconnectIntent(): boolean {
  try { return localStorage.getItem(STORAGE_RECONNECT_INTENT_KEY) === 'true'; } catch { return false; }
}

async function connectProxyProfile(profile: SavedProxyProfile): Promise<boolean> {
  await VpnBridge.connectProxy({ link: profile.link });
  for (let attempt = 0; attempt < 80; attempt += 1) {
    await delay(250);
    if ((await VpnBridge.isVpnConnected()).connected) return true;
  }
  await VpnBridge.disconnect().catch(() => undefined);
  return false;
}

async function connectWireGuard(server: WireguardServer, settings: AppSettings): Promise<boolean> {
  await VpnBridge.connect({
    serverName: server.name || 'NexaLink',
    endpoint: server.endpoint,
    dns: Array.isArray(server.dns) ? server.dns.join(',') : server.dns,
    address: Array.isArray(server.addresses) ? server.addresses.join(',') : (server.addresses || '172.16.0.2/32'),
    privateKey: server.privateKey,
    publicKey: server.publicKey,
    allowedIPs: Array.isArray(server.allowedIPs) ? server.allowedIPs.join(',') : '0.0.0.0/0',
    bypassLanRoute: settings.bypassLanRoute,
    excludedApplications: settings.excludedApplications,
    mtu: settings.mtu,
    keepalive: settings.keepalive,
  });
  // Native VPN startup can take several seconds on slower Android devices.
  // Confirm the actual VpnService state instead of treating the start command
  // response as a successful connection.
  for (let attempt = 0; attempt < 80; attempt += 1) {
    await delay(250);
    if ((await VpnBridge.isVpnConnected()).connected) return true;
  }

  // A timed-out startup must not leave a background tunnel running while the
  // UI says "disconnected".
  await VpnBridge.disconnect().catch(() => undefined);
  return false;
}

function normalizeStoredServers(value: unknown): WireguardServer[] | null {
  if (!Array.isArray(value) || value.length === 0) return null;
  return value.map((server) => {
    if (!server || typeof server !== 'object') return server as WireguardServer;
    const item = server as WireguardServer;
    const hasIpv6Address = Array.isArray(item.addresses)
      && item.addresses.some((address) => address.includes(':'));
    const allowed = Array.isArray(item.allowedIPs) ? item.allowedIPs : [];
    const dns = Array.isArray(item.dns) ? item.dns : [];
    const normalizedRoutes = !hasIpv6Address && allowed.includes('::/0')
      ? allowed.filter((route) => route !== '::/0')
      : allowed;
    const normalizedDns = !hasIpv6Address
      ? dns.filter((serverAddress) => !serverAddress.includes(':'))
      : dns;
    // Migrate the old implicit IPv4+IPv6 default only when the peer itself
    // has no IPv6 address. Explicit IPv6-capable configurations are preserved.
    if (normalizedRoutes !== allowed || normalizedDns !== dns) {
      return {
        ...item,
        allowedIPs: normalizedRoutes,
        dns: normalizedDns.length ? normalizedDns : ['1.1.1.1', '1.0.0.1'],
      };
    }
    return item;
  });
}


function isStringArray(value: unknown, maxItems: number, maxLength = 256): value is string[] {
  return Array.isArray(value)
    && value.length <= maxItems
    && value.every((item) => typeof item === 'string' && item.length > 0 && item.length <= maxLength);
}

function isValidBackupServer(value: unknown): value is WireguardServer {
  if (!value || typeof value !== 'object') return false;
  const server = value as Partial<WireguardServer>;
  return server.protocol === 'WireGuard'
    && typeof server.id === 'string' && server.id.length > 0 && server.id.length <= 128
    && typeof server.name === 'string' && server.name.length > 0 && server.name.length <= 128
    && typeof server.rawUri === 'string' && server.rawUri.length <= 4096
    && typeof server.endpoint === 'string' && server.endpoint.length <= 300
    && typeof server.host === 'string' && server.host.length <= 253
    && Number.isInteger(server.port) && Number(server.port) >= 1 && Number(server.port) <= 65535
    && typeof server.privateKey === 'string' && /^[A-Za-z0-9+/]{43}=$/.test(server.privateKey)
    && typeof server.publicKey === 'string' && /^[A-Za-z0-9+/]{43}=$/.test(server.publicKey)
    && isStringArray(server.addresses, 8, 64)
    && isStringArray(server.dns, 8, 64)
    && isStringArray(server.allowedIPs, 32, 128)
    && Number.isInteger(server.mtu) && Number(server.mtu) >= 576 && Number(server.mtu) <= 9000
    && Number.isInteger(server.persistentKeepalive) && Number(server.persistentKeepalive) >= 0 && Number(server.persistentKeepalive) <= 65535
    && Number.isFinite(server.createdAt);
}

function sanitizeBackupSettings(value: unknown, current: AppSettings): AppSettings {
  if (!value || typeof value !== 'object') return current;
  const raw = value as Partial<AppSettings>;
  const next: AppSettings = { ...current };
  if (typeof raw.dpiBypass === 'boolean') next.dpiBypass = raw.dpiBypass;
  if (typeof raw.autoReconnect === 'boolean') next.autoReconnect = raw.autoReconnect;
  if (typeof raw.bypassLanRoute === 'boolean') next.bypassLanRoute = raw.bypassLanRoute;
  if (typeof raw.externalIp === 'string' && raw.externalIp.length <= 128) next.externalIp = raw.externalIp;
  if (typeof raw.location === 'string' && raw.location.length <= 128) next.location = raw.location;
  if (typeof raw.country === 'string' && raw.country.length <= 80) next.country = raw.country;
  if (typeof raw.countryFlag === 'string' && raw.countryFlag.length <= 16) next.countryFlag = raw.countryFlag;
  if (typeof raw.defaultDns === 'string' && raw.defaultDns.length <= 64) next.defaultDns = raw.defaultDns;
  if (Number.isInteger(raw.mtu) && Number(raw.mtu) >= 576 && Number(raw.mtu) <= 9000) next.mtu = Number(raw.mtu);
  if (Number.isInteger(raw.keepalive) && Number(raw.keepalive) >= 0 && Number(raw.keepalive) <= 65535) next.keepalive = Number(raw.keepalive);
  if (isStringArray(raw.excludedApplications, 200, 256)) next.excludedApplications = raw.excludedApplications;
  if (raw.language === 'en' || raw.language === 'ar') next.language = raw.language;
  return next;
}

export default function App() {
  // 1. Servers state
  const [servers, setServers] = useState<WireguardServer[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_SERVERS_KEY);
      if (saved) {
        const parsed = normalizeStoredServers(JSON.parse(saved));
        if (parsed) return parsed;
      }
    } catch {
      // fallback
    }
    return getInitialServers();
  });

  // Saved Sing-box profiles are stored separately from WireGuard peers.
  const [proxyProfiles, setProxyProfiles] = useState<SavedProxyProfile[]>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_PROXY_PROFILES_KEY);
      const parsed = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(parsed)) return [];
      return parsed.filter((item): item is SavedProxyProfile =>
        !!item && typeof item.id === 'string' && typeof item.link === 'string'
        && ['vless', 'vmess', 'trojan'].includes(item.protocol)
        && typeof item.name === 'string' && typeof item.server === 'string'
        && Number.isInteger(item.port) && item.port > 0 && item.port <= 65535
      ).slice(0, 100);
    } catch { return []; }
  });
  const [activeProxyId, setActiveProxyId] = useState<string | null>(() => {
    try { return localStorage.getItem('nexalink_active_proxy_id_v1'); } catch { return null; }
  });

  // Active server ID
  const [activeServerId, setActiveServerId] = useState<string>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_ACTIVE_SERVER_KEY);
      if (saved) return saved;
    } catch {
      // fallback
    }
    return servers[0]?.id || '';
  });

  // 2. Settings state
  const [settings, setSettings] = useState<AppSettings>(() => {
    const defaults: AppSettings = {
      dpiBypass: true,
      autoReconnect: true,
      bypassLanRoute: false,
      externalIp: 'Not checked',
      location: 'Not checked',
      country: 'Iraq',
      countryFlag: '🇮🇶',
      defaultDns: '1.1.1.1',
      mtu: 1280,
      keepalive: 25,
      excludedApplications: [],
      language: 'en',
    };
    try {
      const saved = localStorage.getItem(STORAGE_SETTINGS_KEY);
      if (saved) return { ...defaults, ...JSON.parse(saved) };
    } catch {
      // fallback
    }
    return defaults;
  });

  // 3. Connection State (Dedicated strictly to WireGuard)
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected');
  const connectionStateRef = useRef<ConnectionState>('disconnected');
  connectionStateRef.current = connectionState;
  const reconnectWantedRef = useRef<boolean>(readReconnectIntent());
  const reconnectInFlightRef = useRef(false);

  // 4. Telemetry State
  const [trafficStatsAvailable, setTrafficStatsAvailable] = useState(false);
  const [telemetry, setTelemetry] = useState<NetworkTelemetry>({
    durationSeconds: 0,
    uploadBytes: 0,
    downloadBytes: 0,
    uploadSpeedKBs: 0,
    downloadSpeedKBs: 0,
    pingMs: 0,
  });
  const [connectionLogs, setConnectionLogs] = useState<ConnectionLogEntry[]>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_CONNECTION_LOG_KEY);
      const parsed = saved ? JSON.parse(saved) : [];
      return Array.isArray(parsed) ? parsed.slice(0, 50) : [];
    } catch {
      return [];
    }
  });

  // 5. Modal and Drawer states
  const [isAddServerOpen, setIsAddServerOpen] = useState(false);
  const [isServerListOpen, setIsServerListOpen] = useState(false);
  const [isProxyProfilesOpen, setIsProxyProfilesOpen] = useState(false);
  const [isConfigExportOpen, setIsConfigExportOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const [serverForExport, setServerForExport] = useState<WireguardServer | null>(null);
  const [isPinging, setIsPinging] = useState(false);
  const [isLoadingIp, setIsLoadingIp] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [isAppExclusionsOpen, setIsAppExclusionsOpen] = useState(false);
  const [isDiagnosticsOpen, setIsDiagnosticsOpen] = useState(false);
  const [installedApplications, setInstalledApplications] = useState<InstalledApp[]>([]);
  const [cellularInfo, setCellularInfo] = useState<CellularInfo>(EMPTY_CELLULAR_INFO);
  const [isLoadingCellular, setIsLoadingCellular] = useState(false);

  // Active server object
  const activeServer = servers.find((s) => s.id === activeServerId) || servers[0];
  const activeProxyProfile = proxyProfiles.find((profile) => profile.id === activeProxyId) || null;
  const activeProxyRef = useRef<SavedProxyProfile | null>(activeProxyProfile);
  activeProxyRef.current = activeProxyProfile;

  useEffect(() => {
    if (activeProxyId && !proxyProfiles.some((profile) => profile.id === activeProxyId)) {
      setActiveProxyId(null);
    }
  }, [activeProxyId, proxyProfiles]);
  const activeServerRef = useRef(activeServer);
  activeServerRef.current = activeServer;
  const settingsRef = useRef(settings);
  settingsRef.current = settings;

  // Probe native VPN state after the first paint so the interface opens immediately.
  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void VpnBridge.isVpnConnected().then(({ connected }) => {
        if (!cancelled) {
          if (connected) {
            reconnectWantedRef.current = true;
            try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'true'); } catch { /* storage may be unavailable */ }
            setConnectionState('connected');
          } else {
            setConnectionState(reconnectWantedRef.current && settings.autoReconnect ? 'reconnecting' : 'disconnected');
          }
        }
      }).catch(() => undefined);
    }, 0);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, []);

  // Restore only a tunnel the user previously asked to keep active.
  useEffect(() => {
    if (!settings.autoReconnect) {
      if (connectionStateRef.current === 'reconnecting') setConnectionState('disconnected');
      return;
    }
    let disposed = false;
    const monitor = async () => {
      if (disposed || !settings.autoReconnect || !reconnectWantedRef.current || reconnectInFlightRef.current) return;
      const current = connectionStateRef.current;
      if (current === 'connecting' || current === 'disconnecting') return;

      reconnectInFlightRef.current = true;
      try {
        const native = await VpnBridge.isVpnConnected();
        if (disposed || !reconnectWantedRef.current) return;
        if (native.connected) {
          if (connectionStateRef.current !== 'connected') setConnectionState('connected');
          return;
        }

        setConnectionState('reconnecting');
        for (const wait of [1000, 2000, 4000, 8000]) {
          await delay(wait);
          if (disposed || !reconnectWantedRef.current) return;
          try {
            const server = activeServerRef.current;
            const proxy = activeProxyRef.current;
            const recovered = proxy
              ? await connectProxyProfile(proxy)
              : await connectWireGuard(server, settingsRef.current);
            if (recovered) {
              if (!settingsRef.current.autoReconnect || !reconnectWantedRef.current
                  || activeServerRef.current.id !== server.id
                  || (proxy?.id || null) !== (activeProxyRef.current?.id || null)) {
                await VpnBridge.disconnect().catch(() => undefined);
                setConnectionState('disconnected');
                return;
              }
              setConnectionState('connected');
              return;
            }
            await VpnBridge.disconnect().catch(() => undefined);
          } catch (error) {
            console.warn('Automatic VPN reconnect attempt failed:', error);
          }
        }

        reconnectWantedRef.current = false;
        try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
        setConnectionState('disconnected');
      } catch (error) {
        console.warn('VPN recovery check failed:', error);
        if (!disposed && reconnectWantedRef.current) setConnectionState('disconnected');
      } finally {
        reconnectInFlightRef.current = false;
      }
    };

    const timer = window.setInterval(() => { void monitor(); }, 5000);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [settings.autoReconnect, activeServerId]);

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void VpnBridge.listApplications().then(({ applications }) => {
        if (!cancelled) setInstalledApplications(applications);
      }).catch(() => undefined);
    }, 750);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const refreshCellular = async () => {
      setIsLoadingCellular(true);
      const info = await readCellularInfo();
      if (!cancelled) {
        setCellularInfo(info);
        setIsLoadingCellular(false);
      }
    };
    const initialTimer = window.setTimeout(() => { void refreshCellular(); }, 1000);
    const timer = window.setInterval(() => { void refreshCellular(); }, 10000);
    return () => { cancelled = true; window.clearTimeout(initialTimer); window.clearInterval(timer); };
  }, []);

  // Save servers to localStorage
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_SERVERS_KEY, JSON.stringify(servers));
    } catch (e) {
      console.warn('Storage save failed:', e);
    }
  }, [servers]);

  useEffect(() => {
    try { localStorage.setItem(STORAGE_PROXY_PROFILES_KEY, JSON.stringify(proxyProfiles)); }
    catch (error) { console.warn('Proxy profile storage failed:', error); }
  }, [proxyProfiles]);

  useEffect(() => {
    try {
      if (activeProxyId) localStorage.setItem('nexalink_active_proxy_id_v1', activeProxyId);
      else localStorage.removeItem('nexalink_active_proxy_id_v1');
    } catch { /* storage may be unavailable */ }
  }, [activeProxyId]);

  // Save active server ID
  useEffect(() => {
    if (activeServerId) {
      try {
        localStorage.setItem(STORAGE_ACTIVE_SERVER_KEY, activeServerId);
      } catch (e) {
        console.warn('Storage save failed:', e);
      }
    }
  }, [activeServerId]);

  // Save settings
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_SETTINGS_KEY, JSON.stringify(settings));
    } catch (e) {
      console.warn('Storage save failed:', e);
    }
  }, [settings]);

  useEffect(() => {
    try { localStorage.setItem(STORAGE_CONNECTION_LOG_KEY, JSON.stringify(connectionLogs)); } catch { /* keep live app usable */ }
  }, [connectionLogs]);

  const saveConnectionLog = (snapshot: NetworkTelemetry) => {
    const entry: ConnectionLogEntry = {
      id: `session-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
      timestamp: Date.now(),
      durationSeconds: snapshot.durationSeconds,
      uploadBytes: snapshot.uploadBytes,
      downloadBytes: snapshot.downloadBytes,
    };
    setConnectionLogs((previous) => [entry, ...previous].slice(0, 50));
  };

  const clearConnectionLogs = () => setConnectionLogs([]);

  // Live traffic, duration & latency when connected. Transfer counters come
  // directly from the native WireGuard backend; they are never extrapolated.
  const timerRef = useRef<NodeJS.Timeout | null>(null);
  const telemetryPollTimerRef = useRef<NodeJS.Timeout | null>(null);
  const geoPollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const telemetryRef = useRef<NetworkTelemetry>(telemetry);
  const previousTrafficSampleRef = useRef<{ downloadBytes: number; uploadBytes: number; capturedAt: number } | null>(null);

  useEffect(() => {
    telemetryRef.current = telemetry;
  }, [telemetry]);

  // Measure real ping helper
  const refreshRealPing = async () => {
    setIsPinging(true);
    try {
      const realPing = await measureRealPing();
      if (realPing <= 0) return;
      setTelemetry((prev) => ({
        ...prev,
        pingMs: realPing,
      }));
      setServers((prev) =>
        prev.map((s) => (s.id === activeServerId ? { ...s, latency: realPing } : s))
      );
    } catch {
      // fallback
    } finally {
      setIsPinging(false);
    }
  };

  // Fetch real external IP, Country and Flag helper
  const refreshRealIp = async () => {
    setIsLoadingIp(true);
    try {
      const geo = await fetchRealExternalIpAndCountry();
      setSettings((prev) => ({
        ...prev,
        externalIp: geo.ip,
        country: geo.country,
        countryFlag: geo.flag,
        location: `${geo.country} (${geo.city})`,
      }));
    } catch {
      // ignore
    } finally {
      setIsLoadingIp(false);
    }
  };

  useEffect(() => {
    let disposed = false;
    if (connectionState === 'connected') {
      let trafficRequestInFlight = false;
      let pingRequestInFlight = false;
      previousTrafficSampleRef.current = null;

      // The native tunnel-counter read and RTT probe intentionally remain
      // independent. A slow network RTT request must never delay the three
      // second traffic refresh shown in the UI.
      const pollLiveTelemetry = async () => {
        if (trafficRequestInFlight || disposed) return;
        trafficRequestInFlight = true;
        try {
          const traffic = await VpnBridge.getTrafficStats();
          const capturedAt = performance.now();
          if (disposed || !traffic.connected) return;
          if (!traffic.available) { setTrafficStatsAvailable(false); return; }
          setTrafficStatsAvailable(true);
          const previous = previousTrafficSampleRef.current;
          const elapsedSeconds = previous ? (capturedAt - previous.capturedAt) / 1000 : 0;
          const downloadDelta = previous ? traffic.downloadBytes - previous.downloadBytes : 0;
          const uploadDelta = previous ? traffic.uploadBytes - previous.uploadBytes : 0;
          const validDelta = elapsedSeconds > 0 && downloadDelta >= 0 && uploadDelta >= 0;

          previousTrafficSampleRef.current = {
            downloadBytes: traffic.downloadBytes,
            uploadBytes: traffic.uploadBytes,
            capturedAt,
          };

          setTelemetry((prev) => ({
            ...prev,
            // Backend totals are assigned as-is rather than incremented with
            // generated bytes, so the UI precisely mirrors tunnel traffic.
            downloadBytes: traffic.downloadBytes,
            uploadBytes: traffic.uploadBytes,
            downloadSpeedKBs: validDelta ? Math.round((downloadDelta / elapsedSeconds) / 1024) : 0,
            uploadSpeedKBs: validDelta ? Math.round((uploadDelta / elapsedSeconds) / 1024) : 0,
          }));
        } catch {
          // Keep the last valid native values if the service is restarting.
        } finally {
          trafficRequestInFlight = false;
        }
      };

      const pollPing = async () => {
        if (pingRequestInFlight || disposed) return;
        pingRequestInFlight = true;
        try {
          const pingMs = await measureRealPing();
          if (!disposed && pingMs > 0) {
            setTelemetry((prev) => ({ ...prev, pingMs }));
            setServers((prev) => prev.map((server) => (
              server.id === activeServerId ? { ...server, latency: pingMs } : server
            )));
          }
        } catch {
          // Preserve the most recent successful RTT.
        } finally {
          pingRequestInFlight = false;
        }
      };

      const pollMeasurements = () => {
        void pollLiveTelemetry();
        void pollPing();
      };

      // Read actual counters immediately, then refresh all live measurements
      // every three seconds as requested. The in-flight guard prevents overlap
      // on a slow network and avoids a growing queue of callbacks.
      void refreshRealIp();
      if (geoPollTimerRef.current) clearInterval(geoPollTimerRef.current);
      geoPollTimerRef.current = setInterval(() => { void refreshRealIp(); }, 30000);
      pollMeasurements();
      telemetryPollTimerRef.current = setInterval(pollMeasurements, 3000);

      // Duration is the only visual counter that is updated locally; transfer
      // bytes and throughput stay entirely native-measurement based.
      timerRef.current = setInterval(() => {
        setTelemetry((prev) => ({ ...prev, durationSeconds: prev.durationSeconds + 1 }));
      }, 1000);
    } else {
      if (timerRef.current) clearInterval(timerRef.current);
      if (telemetryPollTimerRef.current) clearInterval(telemetryPollTimerRef.current);
      if (geoPollTimerRef.current) clearInterval(geoPollTimerRef.current);
      previousTrafficSampleRef.current = null;
      setTrafficStatsAvailable(false);
    }

    return () => {
      disposed = true;
      if (timerRef.current) clearInterval(timerRef.current);
      if (telemetryPollTimerRef.current) clearInterval(telemetryPollTimerRef.current);
      if (geoPollTimerRef.current) clearInterval(geoPollTimerRef.current);
    };
  }, [connectionState, activeServerId]);

  // Handle Power button toggle with Real Android VPN Service
  const handleToggleConnection = async () => {
    if (connectionState === 'connected' || connectionState === 'reconnecting') {
      reconnectWantedRef.current = false;
      try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
      setConnectionState('disconnecting');
      try {
        await VpnBridge.disconnect();
      } catch (err) {
        console.warn('VPN disconnect error:', err);
      }
      saveConnectionLog(telemetryRef.current);
      setTimeout(() => {
        setConnectionState('disconnected');
      }, 500);
    } else if (connectionState === 'disconnected') {
      reconnectWantedRef.current = true;
      try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'true'); } catch { /* storage may be unavailable */ }
      previousTrafficSampleRef.current = null;
      setTrafficStatsAvailable(false);
      setConnectionState('connecting');
      setTelemetry({
        durationSeconds: 0,
        uploadBytes: 0,
        downloadBytes: 0,
        uploadSpeedKBs: 0,
        downloadSpeedKBs: 0,
        pingMs: 0,
      });
      try {
        const connected = activeProxyProfile
          ? await connectProxyProfile(activeProxyProfile)
          : await connectWireGuard(activeServer, settings);
        if (connected) {
          setConnectionState('connected');
        } else {
          reconnectWantedRef.current = false;
          try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
          setConnectionState('disconnected');
        }
      } catch (err) {
        reconnectWantedRef.current = false;
        try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
        console.error('VPN permission or connection error:', err);
        setConnectionState('disconnected');
      }
    }
  };

  // Add Server handler
  const handleAddServer = (newServer: WireguardServer, activateImmediately: boolean) => {
    setServers((prev) => [newServer, ...prev]);
    if (activateImmediately) {
      reconnectWantedRef.current = false;
      try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
      setActiveServerId(newServer.id);
      setActiveProxyId(null);
      // Selecting a server must not claim a native VPN connection. The tunnel
      // is started only by the power button after the user grants permission.
      setConnectionState('disconnected');
    }
  };

  // Select server handler
  const handleSelectServer = (server: WireguardServer) => {
    setActiveServerId(server.id);
    setActiveProxyId(null);
    setIsServerListOpen(false);
    if (connectionState === 'connected' || connectionState === 'reconnecting') {
      reconnectWantedRef.current = false;
      try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
      // Do not swap credentials underneath a live WireGuard tunnel.
      void VpnBridge.disconnect().catch(() => undefined);
      setConnectionState('disconnected');
    }
  };

  // Delete server handler
  const handleDeleteServer = (id: string) => {
    setServers((prev) => {
      const filtered = prev.filter((s) => s.id !== id);
      if (activeServerId === id && filtered.length > 0) {
        setActiveServerId(filtered[0].id);
      }
      return filtered;
    });
  };

  // Ping all servers with real measurement
  const handlePingAll = async () => {
    setIsPinging(true);
    try {
      const realLatency = await measureRealPing();
      if (realLatency <= 0) return;
      setServers((prev) =>
        prev.map((s) => (s.id === activeServerId ? { ...s, latency: realLatency } : s))
      );
      setTelemetry((prev) => ({
        ...prev,
        pingMs: realLatency,
      }));
    } catch {
      // fallback
    } finally {
      setIsPinging(false);
    }
  };

  // Reset to default servers
  const handleResetServers = () => {
    reconnectWantedRef.current = false;
    try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
    const defaults = getInitialServers();
    setServers(defaults);
    setActiveServerId(defaults[0].id);
    setActiveProxyId(null);
  };

  const handleExportBackup = async (password: string) => {
    const payload = {
      formatVersion: 1,
      createdAt: new Date().toISOString(),
      servers,
      settings,
      activeServerId,
    };
    const blob = await encryptBackup(payload, password);
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'nexalink-backup.json';
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const handleImportBackup = async (file: File, password: string) => {
    if (connectionState !== 'disconnected') {
      throw new Error('Disconnect the VPN before restoring a backup.');
    }
    const payload = await decryptBackup(file, password) as {
      formatVersion?: number;
      servers?: unknown;
      settings?: unknown;
      activeServerId?: unknown;
    };
    if (payload.formatVersion !== 1 || !Array.isArray(payload.servers) ||
        payload.servers.length === 0 || payload.servers.length > 100) {
      throw new Error('The backup payload is incomplete, unsupported, or contains too many servers (maximum 100).');
    }
    const importedServers = payload.servers.filter(isValidBackupServer);
    if (importedServers.length !== payload.servers.length) {
      throw new Error('The backup contains an invalid WireGuard server entry.');
    }
    if (new Set(importedServers.map((server) => server.id)).size !== importedServers.length) {
      throw new Error('The backup contains duplicate server IDs.');
    }
    const importedSettings = sanitizeBackupSettings(payload.settings, settings);
    const nextActiveId = typeof payload.activeServerId === 'string'
      && importedServers.some((server) => server.id === payload.activeServerId)
      ? payload.activeServerId
      : importedServers[0].id;
    setServers(importedServers);
    setSettings(importedSettings);
    reconnectWantedRef.current = false;
    try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
    setActiveServerId(nextActiveId);
    setTelemetry({
      durationSeconds: 0,
      uploadBytes: 0,
      downloadBytes: 0,
      uploadSpeedKBs: 0,
      downloadSpeedKBs: 0,
      pingMs: 0,
    });
  };

  // Open config export for a specific server
  const handleOpenExport = (server?: WireguardServer) => {
    setServerForExport(server || activeServer);
    setIsConfigExportOpen(true);
  };

  // Quick activate Baly server handler
  const handleQuickActivateBaly = () => {
    // Check if baly server exists in servers list
    let balyServer = servers.find((s) => s.name.toLowerCase().includes('bally') || s.name.toLowerCase().includes('بلي') || s.rawUri?.includes('BALLY'));
    if (!balyServer) {
      balyServer = parseWireguardUri(USER_DEFAULT_URI);
      balyServer.name = 'NexaLink - Baly Mode';
      setServers((prev) => [balyServer!, ...prev]);
    }
    setActiveServerId(balyServer.id);
    if (connectionState === 'connected' || connectionState === 'reconnecting') {
      reconnectWantedRef.current = false;
      try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
      void VpnBridge.disconnect().catch(() => undefined);
      setConnectionState('disconnected');
    }
  };

  const isBalyActive = activeServer.name.toLowerCase().includes('bally') || activeServer.name.toLowerCase().includes('بلي') || activeServer.rawUri?.includes('BALLY');

  return (
    <div className="nexalink-app relative min-h-[100dvh] w-full text-slate-100 flex flex-col items-center overflow-x-hidden selection:bg-cyan-400 selection:text-slate-950" dir="ltr">
      <div className="nexalink-ambient" aria-hidden="true" />
      <div className="nexalink-noise" aria-hidden="true" />

      {/* Main Single-Screen Full-View Frame - No Scrolling Needed */}
      <main className="nexalink-shell relative w-full max-w-lg mx-auto min-h-[100dvh] flex flex-col justify-start gap-4 px-4 pt-3 pb-[max(1.25rem,env(safe-area-inset-bottom))] z-10">
        {/* Top Header & Mobile Status Bar */}
        <Header
          onOpenMenu={() => setIsMenuOpen(true)}
          onOpenSettings={() => setIsSettingsOpen(true)}
          serverCount={servers.length}
        />

        {/* Central Power Switch button and status pill */}
        <PowerSwitch
          connectionState={connectionState}
          onToggle={handleToggleConnection}
          serverName={activeProxyProfile?.name || activeServer.name}
        />

        {/* Baly App Oodi Integration Card */}
        <BalyBadge
          isBalyActive={isBalyActive}
        >
          <StatsCard
            telemetry={telemetry}
            isConnected={connectionState === 'connected'}
            trafficAvailable={trafficStatsAvailable}
            onRefreshPing={refreshRealPing}
            isPinging={isPinging}
          />
          <DeviceInfoBar
            externalIp={settings.externalIp}
            country={settings.country || 'Iraq'}
            countryFlag={settings.countryFlag || '🇮🇶'}
            isConnected={connectionState === 'connected'}
            totalBytes={telemetry.uploadBytes + telemetry.downloadBytes}
            cellularInfo={cellularInfo}
            onRefreshCellular={() => { void readCellularInfo().then(setCellularInfo); }}
            onRefreshIp={refreshRealIp}
            onOpenHistory={() => setIsHistoryOpen(true)}
            onOpenShizzi={() => {
              void ShizziBridge.open().catch(() => {
                void ShizziBridge.openShizuku().catch(() => undefined);
              });
            }}
            isLoadingIp={isLoadingIp}
            isLoadingCellular={isLoadingCellular}
          />
        </BalyBadge>

        {/* Server & Network Controls Group */}
        <div className="flex flex-col gap-1.5 w-full my-auto">
          <button type="button" onClick={() => setIsProxyProfilesOpen(true)}
            className="-translate-y-1 mb-0.5 flex w-full items-center justify-between rounded-xl border border-violet-500/30 bg-violet-950/60 px-3 py-2 text-[10px] font-bold text-violet-200 hover:bg-violet-900/70 transition-colors">
            <span className="flex items-center gap-2"><span aria-hidden="true" className="inline-flex h-4 w-4 items-center justify-center rounded-full border border-violet-300 text-[9px]">↗</span><span>{activeProxyProfile ? activeProxyProfile.name : 'Add or select a proxy profile'}</span></span>
            <span className="rounded-full bg-violet-400/20 px-2 py-0.5 text-[9px] font-black text-violet-200">VLESS / VMESS / TROJAN</span>
          </button>
          <button type="button" onClick={() => setIsAppExclusionsOpen(true)} className="-translate-y-1 mb-0.5 flex w-full items-center justify-between rounded-xl border border-fuchsia-500/30 bg-fuchsia-950/70 px-3 py-2 text-[10px] font-bold text-fuchsia-300 shadow-lg shadow-fuchsia-950/20 hover:bg-fuchsia-900/70 transition-colors" dir="ltr">
            <span className="flex items-center gap-2"><ShieldOff className="h-4 w-4 text-fuchsia-400" /><span>Exclude Apps from VPN</span></span>
            <span className="rounded-full bg-fuchsia-400/80 px-2 py-0.5 text-[9px] font-black text-slate-950">{settings.excludedApplications.length}</span>
          </button>
          {/* Telegram Channel & Developer Bar */}
          <TelegramBanner />
        </div>

        {/* Bottom Compact Footer with Waves & Developer Tag */}
        <Footer />
      </main>

      {/* Modals & Slide-out Drawers */}
      <AddServerModal
        isOpen={isAddServerOpen}
        onClose={() => setIsAddServerOpen(false)}
        onAddServer={handleAddServer}
      />

      <ServerListModal
        isOpen={isServerListOpen}
        onClose={() => setIsServerListOpen(false)}
        servers={servers}
        activeServerId={activeServer.id}
        onSelectServer={handleSelectServer}
        onOpenAddServer={() => setIsAddServerOpen(true)}
        onExportConfig={(srv) => handleOpenExport(srv)}
        onDeleteServer={handleDeleteServer}
        onPingAll={handlePingAll}
        isPinging={isPinging}
      />

      <ProxyProfilesModal
        isOpen={isProxyProfilesOpen}
        profiles={proxyProfiles}
        activeProfileId={activeProxyId}
        onClose={() => setIsProxyProfilesOpen(false)}
        onSave={(profile) => {
          setProxyProfiles((current) => [profile, ...current.filter((item) => item.id !== profile.id)]);
          setActiveProxyId(profile.id);
        }}
        onSelect={(profile) => {
          setActiveProxyId(profile.id);
          if (connectionStateRef.current === 'connected' || connectionStateRef.current === 'reconnecting') {
            reconnectWantedRef.current = false;
            try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
            void VpnBridge.disconnect().catch(() => undefined);
            setConnectionState('disconnected');
          }
        }}
        onDelete={(id) => {
          setProxyProfiles((current) => current.filter((profile) => profile.id !== id));
          if (activeProxyId === id) {
            setActiveProxyId(null);
            if (connectionStateRef.current === 'connected' || connectionStateRef.current === 'reconnecting') {
              reconnectWantedRef.current = false;
              try { localStorage.setItem(STORAGE_RECONNECT_INTENT_KEY, 'false'); } catch { /* storage may be unavailable */ }
              void VpnBridge.disconnect().catch(() => undefined);
              setConnectionState('disconnected');
            }
          }
        }}
      />

      <ConfigExportModal
        isOpen={isConfigExportOpen}
        onClose={() => setIsConfigExportOpen(false)}
        server={serverForExport}
      />

      <SettingsModal
        isOpen={isSettingsOpen}
        onClose={() => setIsSettingsOpen(false)}
        settings={settings}
        onSaveSettings={(newSettings) => setSettings(newSettings)}
        onResetServers={handleResetServers}
        onExportBackup={handleExportBackup}
        onImportBackup={handleImportBackup}
        onOpenVpnSettings={() => { void VpnBridge.openVpnSettings().catch((error) => console.warn('Could not open Android VPN settings:', error)); }}
      />

      <ConnectionHistoryModal
        isOpen={isHistoryOpen}
        onClose={() => setIsHistoryOpen(false)}
        entries={connectionLogs}
        onClear={clearConnectionLogs}
      />

      <NavigationDrawer
        isOpen={isMenuOpen}
        onClose={() => setIsMenuOpen(false)}
        onOpenServers={() => setIsServerListOpen(true)}
        onOpenAddServer={() => setIsAddServerOpen(true)}
        onOpenProxyProfiles={() => setIsProxyProfilesOpen(true)}
        onOpenExport={() => handleOpenExport(activeServer)}
        onOpenSettings={() => setIsSettingsOpen(true)}
        onOpenDiagnostics={() => setIsDiagnosticsOpen(true)}
        onOpenAppExclusions={() => setIsAppExclusionsOpen(true)}
        onOpenShizzi={() => {
          void ShizziBridge.open().catch(() => {
            void ShizziBridge.openShizuku().catch(() => undefined);
          });
        }}
        onOpenShizziPermissions={() => {
          void ShizziBridge.requestBatteryExemption().catch(() => {
            void ShizziBridge.open();
          });
        }}
        excludedAppsCount={settings.excludedApplications.length}
        isConnected={connectionState === 'connected'}
      />

      <DiagnosticsModal
        isOpen={isDiagnosticsOpen}
        onClose={() => setIsDiagnosticsOpen(false)}
        connectionState={connectionState}
        activeServerName={activeServer.name}
        endpoint={activeServer.endpoint}
        serverCount={servers.length}
        telemetry={telemetry}
        mtu={settings.mtu}
        keepalive={settings.keepalive}
        excludedAppsCount={settings.excludedApplications.length}
        bypassLanRoute={settings.bypassLanRoute}
        autoReconnect={settings.autoReconnect}
      />

      <AppExclusionsModal
        isOpen={isAppExclusionsOpen}
        onClose={() => setIsAppExclusionsOpen(false)}
        applications={installedApplications}
        selectedPackages={settings.excludedApplications}
        onSave={(packages) => setSettings((current) => ({ ...current, excludedApplications: packages }))}
      />

    </div>
  );
}
