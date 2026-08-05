'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronDown, Check } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface InlineSelectOption {
  value: string;
  label: string;
  icon?: ReactNode;
  /** 在弹层中显示在该项下方的灰色描述（可选） */
  description?: string;
}

interface Props {
  value: string;
  options: InlineSelectOption[];
  onChange: (v: string) => void;
  /** 触发器左侧图标（如 ImageIcon） */
  icon?: ReactNode;
  /** 触发器主文案 className（覆盖） */
  className?: string;
  /** 弹层顶部的小标题（可选，如"创作类型"） */
  popoverTitle?: string;
}

/**
 * 小型内联下拉：
 * - 触发器：icon + 文字 + ChevronDown
 * - 弹层：可带小标题 + 选项（当前项右侧 ✓），可选项带 description
 */
export function InlineSelect({
  value,
  options,
  onChange,
  icon,
  className,
  popoverTitle,
}: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const current = options.find((o) => o.value === value);

  useEffect(() => {
    if (!open) return;
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className={cn('relative', className)}>
      {/* 触发器 */}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'flex items-center gap-1.5 rounded-lg px-2 py-1 text-xs text-fg-muted transition',
          'hover:bg-bg-soft hover:text-fg',
          open && 'bg-bg-soft text-fg'
        )}
      >
        {icon}
        <span>{current?.label ?? value}</span>
        <ChevronDown
          className={cn('h-3 w-3 transition-transform', open && 'rotate-180')}
        />
      </button>

      {/* 弹层 */}
      {open && (
        <div
          className={cn(
            'absolute left-0 top-[calc(100%+6px)] z-50 min-w-[200px] overflow-hidden rounded-xl border border-bg-line bg-white shadow-2xl'
          )}
        >
          {popoverTitle && (
            <div className="px-3 pt-2 text-[11px] text-fg-subtle">
              {popoverTitle}
            </div>
          )}
          <ul className="p-1">
            {options.map((o) => {
              const selected = o.value === value;
              return (
                <li key={o.value}>
                  <button
                    type="button"
                    onClick={() => {
                      onChange(o.value);
                      setOpen(false);
                    }}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition',
                      selected
                        ? 'bg-brand-50 text-brand'
                        : 'text-fg hover:bg-bg-soft'
                    )}
                  >
                    {o.icon && (
                      <span
                        className={cn(
                          'grid h-5 w-5 place-items-center',
                          selected ? 'text-brand' : 'text-fg-muted'
                        )}
                      >
                        {o.icon}
                      </span>
                    )}
                    <span className="flex-1 truncate">{o.label}</span>
                    {selected && <Check className="h-4 w-4 text-brand" />}
                  </button>
                  {o.description && (
                    <div className="-mt-1 mb-1 pl-9 pr-3 text-[11px] text-fg-subtle">
                      {o.description}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}