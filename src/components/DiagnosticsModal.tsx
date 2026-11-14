import React from 'react';
import { Activity, Download, ShieldCheck, Wifi, X } from 'lucide-react';
import VpnBridge, { VpnTrafficStats } from '../utils/vpnBridge';
import ShizziBridge, { ShizziStatus } from '../utils/shizziBridge';
import { ConnectionState, NetworkTelemetry } from '../types';

interface DiagnosticsModalProps {
  isOpen: boolean;
  onClose: () => void;
  connectionState: ConnectionState;
  activeServerName: string;
  endpoint: string;
  serverCount: number;
  telemetry: NetworkTelemetry;
  mtu: number;
  keepalive: number;
  excludedAppsCount: number;
  bypassLanRoute: boolean;
  autoReconnect: boolean;
}

export const DiagnosticsModal: React.FC<DiagnosticsModalProps> = (props) => {
  const [vpn, setVpn] = React.useState<VpnTrafficStats | null>(null);
  const [shizuku, setShizuku] = React.useState<ShizziStatus | null>(null);
  const [checkedAt, setCheckedAt] = React.useState<number | null>(null);
  const [error, setError] = React.useState('');

  const refresh = React.useCallback(async () => {
    setError('');
    const [vpnResult, shizukuResult] = await Promise.allSettled([
      VpnBridge.getTrafficStats(),
      ShizziBridge.status(),
    ]);
    if (vpnResult.status === 'fulfilled') setVpn(vpnResult.value);
    else setError('Native VPN diagnostics are unavailable.');
    if (shizukuResult.status === 'fulfilled') setShizuku(shizukuResult.value);
    setCheckedAt(Date.now());
  }, []);

  React.useEffect(() => {
    if (props.isOpen) void refresh();
  }, [props.isOpen, refresh]);

  if (!props.isOpen) return null;

  const report = {
    product: 'NexaLink',
    generatedAt: new Date().toISOString(),
    connection: {
      uiState: props.connectionState,
      nativeTunnelConnected: vpn?.connected ?? null,
      serverName: props.activeServerName,
      endpoint: props.endpoint,
      serverCount: props.serverCount,
      downloadBytes: vpn?.downloadBytes ?? null,
      uploadBytes: vpn?.uploadBytes ?? null,
      latestHandshakeEpochMs: vpn?.latestHandshakeEpochMs ?? null,
      internetRttMs: props.telemetry.pingMs || null,
    },
    shizuku: shizuku ? {
      installed: shizuku.installed,
      running: shizuku.running,
      permissionGranted: shizuku.permissionGranted,
      uid: shizuku.uid,
    } : null,
    settings: {
      mtu: props.mtu,
      keepalive: props.keepalive,
      excludedAppsCount: props.excludedAppsCount,
      bypassLanRoute: props.bypassLanRoute,
      autoReconnect: props.autoReconnect,
    },
    privacy: 'This report excludes private keys, public keys, full configuration contents and credentials.',
  };

  const downloadReport = () => {
    const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'nexalink-diagnostics.json';
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const row = (label: string, value: string) => (
    <div className="flex items-center justify-between gap-3 border-b border-slate-800 py-2 text-xs">
      <span className="text-slate-400">{label}</span>
      <span className="text-right font-mono text-slate-100 break-all">{value}</span>
    </div>
  );

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/80 p-4 backdrop-blur-md">
      <section role="dialog" aria-modal="true" aria-labelledby="diagnostics-title" className="flex max-h-[90vh] w-full max-w-lg flex-col overflow-hidden rounded-3xl border border-cyan-500/30 bg-[#080d24] p-5 shadow-2xl">
        <header className="flex items-center justify-between border-b border-slate-800 pb-3">
          <button onClick={props.onClose} aria-label="Close diagnostics" className="rounded-xl bg-slate-800 p-2 text-slate-300"><X className="h-4 w-4" /></button>
          <div className="flex items-center gap-2"><h2 id="diagnostics-title" className="text-lg font-bold text-white">Network Diagnostics</h2><Activity className="h-5 w-5 text-cyan-400" /></div>
        </header>
        <div className="flex-1 space-y-4 overflow-y-auto py-4">
          <section className="rounded-2xl border border-slate-800 bg-slate-900/50 p-3">
            <h3 className="mb-2 flex items-center justify-end gap-2 text-sm font-bold text-cyan-300">VPN Tunnel <ShieldCheck className="h-4 w-4" /></h3>
            {row('UI state', props.connectionState)}
            {row('Native tunnel', vpn ? (vpn.connected ? 'Connected' : 'Disconnected') : 'Not checked')}
            {row('Active server', props.activeServerName)}
            {row('Endpoint', props.endpoint)}
            {row('Download', vpn ? String(vpn.downloadBytes) + ' bytes' : 'Unavailable')}
            {row('Upload', vpn ? String(vpn.uploadBytes) + ' bytes' : 'Unavailable')}
            {row('Latest handshake', vpn?.latestHandshakeEpochMs ? new Date(vpn.latestHandshakeEpochMs).toLocaleString() : 'Not reported')}
            {row('Internet RTT', props.telemetry.pingMs ? props.telemetry.pingMs + ' ms' : 'No sample')}
          </section>
          <section className="rounded-2xl border border-slate-800 bg-slate-900/50 p-3">
            <h3 className="mb-2 flex items-center justify-end gap-2 text-sm font-bold text-violet-300">Shizuku <Wifi className="h-4 w-4" /></h3>
            {row('Installed', shizuku ? String(shizuku.installed) : 'Not checked')}
            {row('Running', shizuku ? String(shizuku.running) : 'Not checked')}
            {row('Permission', shizuku ? String(shizuku.permissionGranted) : 'Not checked')}
            {row('UID', shizuku ? String(shizuku.uid) : 'Not reported')}
          </section>
          <section className="rounded-2xl border border-slate-800 bg-slate-900/50 p-3">
            <h3 className="mb-2 text-right text-sm font-bold text-cyan-300">Configuration</h3>
            {row('WireGuard MTU', String(props.mtu))}
            {row('Persistent keepalive', String(props.keepalive) + ' s')}
            {row('Excluded applications', String(props.excludedAppsCount))}
            {row('LAN route bypass', String(props.bypassLanRoute))}
            {row('Auto reconnect preference', String(props.autoReconnect))}
            {row('Saved servers', String(props.serverCount))}
          </section>
          <p className="text-[11px] leading-relaxed text-slate-500">The diagnostic export omits private keys and VPN credentials. Values unavailable from Android are shown as unavailable, not estimated.</p>
          {error && <p role="status" className="text-xs text-amber-300">{error}</p>}
          {checkedAt && <p className="text-[10px] text-slate-500">Last checked: {new Date(checkedAt).toLocaleString()}</p>}
        </div>
        <footer className="flex justify-between gap-2 border-t border-slate-800 pt-3">
          <button onClick={downloadReport} className="flex items-center gap-2 rounded-xl bg-cyan-600 px-4 py-2 text-xs font-bold text-white"><Download className="h-4 w-4" />Export report</button>
          <button onClick={() => void refresh()} className="rounded-xl bg-slate-800 px-4 py-2 text-xs font-semibold text-slate-200">Refresh</button>
        </footer>
      </section>
    </div>
  );
};
