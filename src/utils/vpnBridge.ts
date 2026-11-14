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
}

export interface VpnBridgePlugin {
  isVpnConnected(): Promise<{ connected: boolean }>;
  getTrafficStats(): Promise<VpnTrafficStats>;
  listApplications(): Promise<{ applications: InstalledApp[] }>;
  connect(options: VpnConnectOptions): Promise<{ status: string }>;
  disconnect(): Promise<{ status: string }>;
  openVpnSettings(): Promise<void>;
}

const VpnBridge = registerPlugin<VpnBridgePlugin>('VpnBridge', {
  web: {
    isVpnConnected: async () => ({ connected: false }),
    getTrafficStats: async () => ({ connected: false, downloadBytes: 0, uploadBytes: 0, latestHandshakeEpochMs: 0 }),
    listApplications: async () => ({ applications: [] }),
    // Browser preview has no Android VpnService. Never report a fake connection.
    connect: async () => { throw new Error('VPN connections are available in the NexaLink Android app.'); },
    disconnect: async () => ({ status: 'disconnected' }),
    openVpnSettings: async () => undefined,
  },
});

export default VpnBridge;
