import React from 'react';
import { X, Server, Plus, QrCode, Settings, Activity, ExternalLink, Cloud, ShieldOff, Zap, BatteryCharging, Stethoscope } from 'lucide-react';

interface NavigationDrawerProps {
  isOpen: boolean;
  onClose: () => void;
  onOpenServers: () => void;
  onOpenAddServer: () => void;
  onOpenProxyProfiles: () => void;
  onOpenExport: () => void;
  onOpenSettings: () => void;
  onOpenDiagnostics: () => void;
  onOpenAppExclusions: () => void;
  onOpenShizzi: () => void;
  onOpenShizziPermissions: () => void;
  excludedAppsCount: number;
  isConnected: boolean;
}

export const NavigationDrawer: React.FC<NavigationDrawerProps> = ({
  isOpen,
  onClose,
  onOpenServers,
  onOpenAddServer,
  onOpenProxyProfiles,
  onOpenExport,
  onOpenSettings,
  onOpenDiagnostics,
  onOpenAppExclusions,
  onOpenShizzi,
  onOpenShizziPermissions,
  excludedAppsCount,
  isConnected,
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-stretch justify-start bg-black/70 backdrop-blur-sm animate-in fade-in duration-200">
      <div 
        role="dialog"
        aria-modal="true"
        className="w-full max-w-xs bg-[#070b1e] border-r border-slate-800 h-full p-5 flex flex-col text-left shadow-2xl relative animate-in slide-in-from-left duration-300"
      >
        {/* Top Header */}
        <div className="flex items-center justify-between pb-4 border-b border-slate-800">
          <button
            onClick={onClose}
            className="p-1.5 rounded-xl bg-slate-800/80 hover:bg-slate-700 text-slate-400 hover:text-white transition-colors"
          >
            <X className="w-5 h-5" />
          </button>

          <div className="flex items-center gap-2">
            <div className="flex flex-col text-left">
              <span className="text-base font-black text-white">NexaLink</span>
              <span className="text-[10px] text-cyan-400 font-mono">WireGuard VPN & Smart Tethering</span>
            </div>
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-cyan-500 to-blue-600 flex items-center justify-center shadow-lg">
              <Cloud className="w-5 h-5 text-white" />
            </div>
          </div>
        </div>

        {/* Connection status banner */}
        <div className="my-4 p-3 rounded-2xl bg-slate-900/80 border border-slate-800/80 flex items-center justify-between">
          <span className={`text-xs font-bold ${isConnected ? 'text-emerald-400' : 'text-slate-400'}`}>
            {isConnected ? 'Connected to encrypted tunnel' : 'Disconnected'}
          </span>
          <div className="flex items-center gap-1.5">
            <span className="text-xs text-slate-300">Tunnel Status:</span>
            <span className={`w-2.5 h-2.5 rounded-full ${isConnected ? 'bg-emerald-400 animate-pulse' : 'bg-rose-500'}`} />
          </div>
        </div>

        {/* Nav Items */}
        <nav className="flex-1 space-y-1.5 overflow-y-auto">
          <button
            onClick={() => {
              onClose();
              onOpenServers();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-slate-500 text-[10px]">MANAGE</span>
            <div className="flex items-center gap-2.5">
              <span>Server List</span>
              <Server className="w-4 h-4 text-cyan-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenAddServer();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-cyan-400 text-[10px] bg-cyan-950/60 px-1.5 py-0.5 rounded border border-cyan-500/30">NEW</span>
            <div className="flex items-center gap-2.5">
              <span>Add WireGuard Server</span>
              <Plus className="w-4 h-4 text-cyan-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenProxyProfiles();
            }}
            className="w-full p-3 rounded-xl hover:bg-violet-950/60 text-slate-200 hover:text-violet-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-violet-300 text-[10px] bg-violet-950/70 px-1.5 py-0.5 rounded border border-violet-500/30">SING-BOX</span>
            <div className="flex items-center gap-2.5">
              <span>Proxy Profiles</span>
              <Zap className="w-4 h-4 text-violet-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenExport();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-slate-500 text-[10px]">.conf / QR</span>
            <div className="flex items-center gap-2.5">
              <span>Export Configuration</span>
              <QrCode className="w-4 h-4 text-blue-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenSettings();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-slate-500 text-[10px]">DNS / MTU</span>
            <div className="flex items-center gap-2.5">
              <span>Network Settings</span>
              <Settings className="w-4 h-4 text-fuchsia-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenDiagnostics();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-slate-500 text-[10px]">REPORT</span>
            <div className="flex items-center gap-2.5">
              <span>Network Diagnostics</span>
              <Stethoscope className="w-4 h-4 text-cyan-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenAppExclusions();
            }}
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-fuchsia-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="rounded-full bg-fuchsia-400 px-2 py-0.5 text-[10px] font-black text-slate-950">{excludedAppsCount}</span>
            <div className="flex items-center gap-2.5">
              <span>App Exclusions</span>
              <ShieldOff className="w-4 h-4 text-fuchsia-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenShizzi();
            }}
            className="w-full p-3 rounded-xl hover:bg-violet-950/60 text-slate-200 hover:text-violet-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-violet-300 text-[10px] bg-violet-950/70 px-1.5 py-0.5 rounded border border-violet-500/30">Shizuku</span>
            <div className="flex items-center gap-2.5">
              <span>Shizzi Tethering (No Root)</span>
              <Zap className="w-4 h-4 text-violet-400" />
            </div>
          </button>

          <button
            onClick={() => {
              onClose();
              onOpenShizziPermissions();
            }}
            className="w-full p-3 rounded-xl hover:bg-amber-950/50 text-slate-200 hover:text-amber-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-amber-300 text-[10px] bg-amber-950/60 px-1.5 py-0.5 rounded border border-amber-500/30">PERMISSIONS</span>
            <div className="flex items-center gap-2.5">
              <span>Shizzi & Battery Permissions</span>
              <BatteryCharging className="w-4 h-4 text-amber-400" />
            </div>
          </button>

          <a
            href="https://github.com/Alkhalidi84/oodiproxy-shizzi"
            target="_blank"
            rel="noopener noreferrer"
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <ExternalLink className="w-3.5 h-3.5 text-slate-500" />
            <div className="flex items-center gap-2.5">
              <span>Project Repository</span>
              <Activity className="w-4 h-4 text-cyan-400" />
            </div>
          </a>

          <a
            href="https://github.com/Alkhalidi84"
            target="_blank"
            rel="noopener noreferrer"
            className="w-full p-3 rounded-xl hover:bg-slate-800/70 text-slate-200 hover:text-cyan-300 flex items-center justify-between text-xs font-bold transition-colors"
          >
            <span className="text-cyan-400 font-mono text-[11px]">@Alkhalidi84</span>
            <div className="flex items-center gap-2.5">
              <span>Mustafa Al-Khalidi · Developer</span>
              <Activity className="w-4 h-4 text-cyan-400" />
            </div>
          </a>
        </nav>

        {/* Drawer footer */}
        <div className="pt-4 border-t border-slate-800 text-center">
          <div className="text-[11px] text-slate-400 font-bold">NexaLink</div>
          <div className="text-[10px] text-slate-500 font-mono mt-0.5">WireGuard VPN & Smart Tethering</div>
        </div>
      </div>
      <div className="flex-1" onClick={onClose} />
    </div>
  );
};
