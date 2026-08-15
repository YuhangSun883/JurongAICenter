import { X } from 'lucide-react';
import type { ReactNode } from 'react';

interface ConsoleModalProps {
  title: string;
  children: ReactNode;
  footer: ReactNode;
  onClose: () => void;
}

export function ConsoleModal({ title, children, footer, onClose }: ConsoleModalProps) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm">
      <div className="w-full max-w-[520px] rounded-lg border border-cyan-300/25 bg-[#07101d] text-cyan-50 shadow-[0_0_90px_rgba(34,211,238,0.18)]">
        <div className="flex min-h-12 items-center justify-between border-b border-cyan-300/15 px-5 py-3">
          <h3 className="text-base font-semibold text-white">{title}</h3>
          <button
            type="button"
            onClick={onClose}
            className="grid h-8 w-8 place-items-center rounded-md text-cyan-100/55 hover:bg-white/5 hover:text-white"
            title="关闭"
          >
            <X size={16} />
          </button>
        </div>
        <div className="space-y-4 px-5 py-4">{children}</div>
        <div className="flex justify-end gap-2 border-t border-cyan-300/15 px-5 py-4">{footer}</div>
      </div>
    </div>
  );
}

export function ModalField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-2 block text-xs text-cyan-100/58">{label}</span>
      {children}
    </label>
  );
}
