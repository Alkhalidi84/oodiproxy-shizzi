import React from 'react';
import { Shield, Waypoints } from 'lucide-react';

export type VpnEngineChoice = 'wireguard' | 'sing-box';

interface ProtocolSelectorProps {
  selectedEngine: VpnEngineChoice;
  onSelectEngine: (engine: VpnEngineChoice) => void;
  disabled?: boolean;
}

export const ProtocolSelector: React.FC<ProtocolSelectorProps> = ({
  selectedEngine, onSelectEngine, disabled = false,
}) => (
  <section className="w-full rounded-2xl border border-slate-800/80 bg-[#090d24]/80 p-3 backdrop-blur-md" aria-label="VPN engine">
    <div className="mb-2 px-1 text-left text-xs font-bold uppercase tracking-wider text-slate-300">VPN Engine</div>
    <div className="grid grid-cols-2 gap-2">
      <button type="button" disabled={disabled} onClick={() => onSelectEngine('wireguard')}
        aria-pressed={selectedEngine === 'wireguard'} id="btn-engine-wireguard"
        className={`flex items-center justify-center gap-2 rounded-xl border px-3 py-3 text-sm font-bold transition-all disabled:cursor-not-allowed disabled:opacity-50 ${selectedEngine === 'wireguard' ? 'border-cyan-400/60 bg-cyan-500/20 text-cyan-100 shadow-[0_0_14px_rgba(6,182,212,0.18)]' : 'border-slate-800 bg-slate-900/70 text-slate-300 hover:border-slate-600'}`}>
        <Shield className="h-4 w-4" /> WireGuard
      </button>
      <button type="button" disabled={disabled} onClick={() => onSelectEngine('sing-box')}
        aria-pressed={selectedEngine === 'sing-box'} id="btn-engine-singbox"
        className={`flex items-center justify-center gap-2 rounded-xl border px-3 py-3 text-sm font-bold transition-all disabled:cursor-not-allowed disabled:opacity-50 ${selectedEngine === 'sing-box' ? 'border-violet-400/60 bg-violet-500/20 text-violet-100 shadow-[0_0_14px_rgba(139,92,246,0.18)]' : 'border-slate-800 bg-slate-900/70 text-slate-300 hover:border-slate-600'}`}>
        <Waypoints className="h-4 w-4" /> Sing-box
      </button>
    </div>
    <p className="mt-2 px-1 text-[11px] leading-relaxed text-slate-500">
      Choose one VPN engine. Only one engine can own Android's VPN interface at a time.
    </p>
  </section>
);
