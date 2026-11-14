import React from 'react';
import { Shield } from 'lucide-react';
import { ProtocolType } from '../types';

interface ProtocolSelectorProps {
  selectedProtocol: ProtocolType;
  onSelectProtocol: (protocol: ProtocolType) => void;
}

export const ProtocolSelector: React.FC<ProtocolSelectorProps> = ({
  selectedProtocol,
  onSelectProtocol,
}) => (
  <div className="w-full rounded-2xl p-3 bg-[#090d24]/80 backdrop-blur-md border border-slate-800/80 select-none">
    <div className="text-left text-xs font-bold text-slate-300 mb-2.5 px-1">
      VPN Protocol
    </div>
    <div className="grid grid-cols-1 gap-2">
      <button
        type="button"
        onClick={() => onSelectProtocol('WireGuard')}
        id="btn-proto-wireguard"
        aria-pressed={selectedProtocol === 'WireGuard'}
        className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-xl font-bold text-xs transition-all active:scale-95 ${
          selectedProtocol === 'WireGuard'
            ? 'bg-gradient-to-r from-blue-600 via-cyan-600 to-blue-600 text-white shadow-[0_0_15px_rgba(6,182,212,0.4)] border border-cyan-400/40'
            : 'bg-slate-900/70 text-slate-300 border border-slate-800'
        }`}
      >
        <Shield className="w-4 h-4" />
        <span>WireGuard</span>
        <span className="ml-1 text-[10px] font-medium opacity-75">Available</span>
      </button>
    </div>
    <p className="mt-2 px-1 text-[11px] leading-relaxed text-slate-500">
      VLESS, VMess and Trojan support will appear after their native VPN engines are integrated and tested.
    </p>
  </div>
);
