import { registerPlugin } from '@capacitor/core';

export interface InstalledApp {
  packageName: string;
  label: string;
  icon?: string;
}

export interface VpnConnectOptions {
  serverName: string;
  endpoint: string;
  dns?: string;
  address?: string;
  privateKey?: string;
  publicKey?: string;
  allowedIPs?: string;
  bypassLanRoute?: boolean;
  excludedApplications?: string[];
  mtu?: number;
  keepalive?: number;
}

export interface VpnTrafficStats {
  connected: boolean;
  downloadBytes: number;
  uploadBytes: number;
  latestHandshakeEpochMs: number;
  available: boolean;
}

export interface ParsedProxyProfile {
  protocol: 'vless' | 'vmess' | 'trojan';
  name: string;
  server: string;
  port: number;
  configJson: string;
}

export interface VpnBridgePlugin {
  parseProxyProfile(options: { link: string }): Promise<ParsedProxyProfile>;
  isVpnConnected(): Promise<{ connected: boolean }>;
  getTrafficStats(): Promise<VpnTrafficStats>;
  listApplications(): Promise<{ applications: InstalledApp[] }>;
  connect(options: VpnConnectOptions): Promise<{ status: string }>;
  connectProxy(options: { link: string; bypassLanRoute?: boolean; excludedApplications?: string[] }): Promise<{ status: string }>;
  loadSecureProxyProfiles(): Promise<{ profilesJson: string }>;
  saveSecureProxyProfiles(options: { profilesJson: string }): Promise<void>;
  disconnect(): Promise<{ status: string }>;
  openVpnSettings(): Promise<void>;
}

const VpnBridge = registerPlugin<VpnBridgePlugin>('VpnBridge', {
  web: {
    parseProxyProfile: async () => { throw new Error('Proxy profile parsing is available in the NexaLink Android app.'); },
    isVpnConnected: async () => ({ connected: false }),
    getTrafficStats: async () => ({ connected: false, downloadBytes: 0, uploadBytes: 0, latestHandshakeEpochMs: 0, available: false }),
    listApplications: async () => ({ applications: [] }),
    // Browser preview has no Android VpnService. Never report a fake connection.
    connect: async () => { throw new Error('VPN connections are available in the NexaLink Android app.'); },
    connectProxy: async () => { throw new Error('Proxy connections are available in the NexaLink Android app.'); },
    loadSecureProxyProfiles: async () => ({ profilesJson: localStorage.getItem('nexalink_proxy_profiles_v1') || '' }),
    saveSecureProxyProfiles: async ({ profilesJson }) => { localStorage.setItem('nexalink_proxy_profiles_v1', profilesJson); },
    disconnect: async () => ({ status: 'disconnected' }),
    openVpnSettings: async () => undefined,
  },
});

export default VpnBridge;
