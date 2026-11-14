import React, { useState } from 'react';
import { Check, Clipboard, Link2, Plus, Radio, Trash2, X } from 'lucide-react';
import VpnBridge, { ParsedProxyProfile } from '../utils/vpnBridge';

export interface SavedProxyProfile extends ParsedProxyProfile {
  id: string;
  link: string;
  createdAt: number;
}

interface ProxyProfilesModalProps {
  isOpen: boolean;
  profiles: SavedProxyProfile[];
  activeProfileId: string | null;
  onClose: () => void;
  onSave: (profile: SavedProxyProfile) => void;
  onSelect: (profile: SavedProxyProfile) => void;
  onDelete: (id: string) => void;
}

export const ProxyProfilesModal: React.FC<ProxyProfilesModalProps> = ({
  isOpen, profiles, activeProfileId, onClose, onSave, onSelect, onDelete,
}) => {
  const [link, setLink] = useState('');
  const [preview, setPreview] = useState<ParsedProxyProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!isOpen) return null;

  const inspectLink = async () => {
    if (!link.trim()) {
      setError('Paste a VLESS, VMess or Trojan share link first.');
      setPreview(null);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const parsed = await VpnBridge.parseProxyProfile({ link: link.trim() });
      setPreview(parsed);
    } catch (cause) {
      setPreview(null);
      setError(cause instanceof Error ? cause.message : 'This proxy link could not be parsed.');
    } finally {
      setBusy(false);
    }
  };

  const saveProfile = () => {
    if (!preview) return;
    onSave({
      ...preview,
      id: 'proxy-' + Date.now().toString(36),
      link: link.trim(),
      createdAt: Date.now(),
    });
    setLink('');
    setPreview(null);
  };

  const pasteClipboard = async () => {
    try {
      const value = await navigator.clipboard.readText();
      if (value) {
        setLink(value.trim());
        setPreview(null);
        setError(null);
      }
    } catch {
      setError('Clipboard access is unavailable. Paste the link into the field.');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200">
      <section role="dialog" aria-modal="true" aria-labelledby="proxy-profiles-title"
        className="relative w-full max-w-lg max-h-[90vh] overflow-hidden flex flex-col rounded-3xl border border-violet-500/30 bg-[#080d24] p-5 shadow-[0_0_50px_rgba(139,92,246,0.18)] text-left">
        <header className="flex items-center justify-between border-b border-slate-800 pb-3">
          <button type="button" onClick={onClose} aria-label="Close"
            className="rounded-xl bg-slate-800 p-2 text-slate-300 hover:bg-slate-700"><X className="h-5 w-5" /></button>
          <div className="flex items-center gap-2">
            <div><h2 id="proxy-profiles-title" className="text-lg font-bold text-white">Proxy Profiles</h2>
              <p className="text-[10px] text-violet-300">Sing-box · VLESS · VMess · Trojan</p></div>
            <div className="rounded-xl border border-violet-500/30 bg-violet-500/10 p-2 text-violet-300"><Radio className="h-5 w-5" /></div>
          </div>
        </header>

        <div className="flex-1 overflow-y-auto space-y-4 py-4">
          <div className="space-y-2 rounded-2xl border border-slate-800 bg-slate-900/60 p-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold text-white">Import share link</span>
              <button type="button" onClick={() => void pasteClipboard()}
                className="flex items-center gap-1 rounded-lg bg-slate-800 px-2.5 py-1.5 text-[10px] font-semibold text-slate-300 hover:bg-slate-700">
                <Clipboard className="h-3 w-3" /> Paste
              </button>
            </div>
            <textarea value={link} onChange={event => { setLink(event.target.value); setPreview(null); setError(null); }}
              rows={4} dir="ltr" spellCheck={false}
              placeholder="vless://…  |  vmess://…  |  trojan://…"
              className="w-full resize-y rounded-xl border border-slate-700 bg-[#050816] p-3 font-mono text-xs text-cyan-200 outline-none focus:border-violet-400" />
            <button type="button" disabled={busy || !link.trim()} onClick={() => void inspectLink()}
              className="flex w-full items-center justify-center gap-2 rounded-xl bg-violet-600 px-4 py-2.5 text-xs font-bold text-white transition hover:bg-violet-500 disabled:opacity-50">
              <Link2 className="h-4 w-4" /> {busy ? 'Checking link…' : 'Validate profile'}
            </button>
            {error && <p role="alert" className="text-xs text-rose-300">{error}</p>}
            {preview && <div className="space-y-2 rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-3">
              <div className="flex items-center gap-2 text-emerald-300"><Check className="h-4 w-4" /><span className="text-xs font-bold">Profile parsed successfully</span></div>
              <div className="grid grid-cols-2 gap-2 text-xs">
                <div><span className="text-slate-500">Protocol</span><p className="font-semibold uppercase text-white">{preview.protocol}</p></div>
                <div><span className="text-slate-500">Port</span><p className="font-mono text-white">{preview.port}</p></div>
                <div className="col-span-2"><span className="text-slate-500">Profile</span><p className="truncate font-semibold text-white">{preview.name}</p></div>
                <div className="col-span-2"><span className="text-slate-500">Server</span><p dir="ltr" className="truncate font-mono text-cyan-200">{preview.server}</p></div>
              </div>
              <button type="button" onClick={saveProfile}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-2.5 text-xs font-bold text-white hover:bg-emerald-500">
                <Plus className="h-4 w-4" /> Save profile
              </button>
            </div>}
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between"><h3 className="text-xs font-bold text-white">Saved profiles</h3><span className="text-[10px] text-slate-500">{profiles.length} profiles</span></div>
            {profiles.length === 0 && <p className="rounded-xl border border-dashed border-slate-700 p-4 text-center text-xs text-slate-500">No proxy profiles saved yet.</p>}
            {profiles.map(profile => <div key={profile.id}
              className={`flex items-center gap-2 rounded-xl border p-3 ${activeProfileId === profile.id ? 'border-violet-400/50 bg-violet-500/10' : 'border-slate-800 bg-slate-900/50'}`}>
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-bold text-white">{profile.name}</p>
                <p dir="ltr" className="truncate text-[10px] font-mono text-slate-400">{profile.protocol.toUpperCase()} · {profile.server}:{profile.port}</p>
                {activeProfileId === profile.id && <span className="text-[9px] font-bold text-violet-300">SELECTED</span>}
              </div>
              <button type="button" onClick={() => { onSelect(profile); onClose(); }}
                className="rounded-lg bg-violet-600 px-3 py-2 text-[10px] font-bold text-white hover:bg-violet-500">Use</button>
              <button type="button" aria-label={`Delete ${profile.name}`} onClick={() => onDelete(profile.id)}
                className="rounded-lg bg-slate-800 p-2 text-slate-400 hover:bg-rose-950 hover:text-rose-300"><Trash2 className="h-4 w-4" /></button>
            </div>)}
          </div>
        </div>
        <footer className="flex justify-end border-t border-slate-800 pt-3">
          <button type="button" onClick={onClose} className="rounded-xl bg-slate-800 px-5 py-2 text-xs font-bold text-white hover:bg-slate-700">Close</button>
        </footer>
      </section>
    </div>
  );
};
