'use client';

import { useEffect, useRef, useState } from 'react';
import { ChevronDown, Check, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';

/** 选项类型：纯字符串 OR { label, hint?, selectedLabel? } 富文本 */
export type StyledSelectOption =
  | string
  | { label: string; hint?: string; selectedLabel?: string };

interface Props {
  value: string;
  options: readonly StyledSelectOption[];
  onChange: (v: string) => void;
  placeholder?: string;
  className?: string;
  /** 触发器高度（默认 h-9） */
  size?: 'sm' | 'md';
  /** 是否显示左侧 sparkle 图标（默认 true） */
  showIcon?: boolean;
  /** 触发器第一行的小标签（如"模型" / "图片设置"） */
  label?: string;
}

function getLabel(o: StyledSelectOption) {
  return typeof o === 'string' ? o : o.label;
}
function getHint(o: StyledSelectOption): string | undefined {
  return typeof o === 'string' ? undefined : o.hint;
}
function getDisplayLabel(o: StyledSelectOption) {
  if (typeof o === 'string') return o;
  return o.selectedLabel ?? o.label;
}

export function StyledSelect({ value, options, onChange, placeholder, className, size = 'md', showIcon = true, label }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  const isSingle = options.length <= 1;

  useEffect(() => {
    if (!open || isSingle) return;
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
  }, [open, isSingle]);

  const h = size === 'sm' ? 'h-10 text-xs' : 'h-12 text-sm';

  const currentOpt = options.find((o) => getLabel(o) === value);
  const triggerLabel = currentOpt ? getDisplayLabel(currentOpt) : value;
  const triggerHint = currentOpt ? getHint(currentOpt) : undefined;

  return (
    <div ref={ref} className={cn('relative w-full', className)}>
      {/* 触发器 */}
      <button
        type="button"
        disabled={isSingle}
        onClick={() => !isSingle && setOpen((v) => !v)}
        className={cn(
          'flex w-full items-center gap-2 rounded-lg border border-bg-line bg-white px-3 text-fg outline-none transition',
          h,
          isSingle && 'cursor-default',
          open ? 'border-brand/50' : !isSingle && 'hover:border-brand/40',
          !value && 'text-fg-subtle'
        )}
      >
        {showIcon && <Sparkles className="h-3.5 w-3.5 flex-none text-fg-subtle" />}
        <div className="flex min-w-0 flex-1 flex-col text-left leading-tight">
          {label && <span className="truncate text-[10px] text-fg-muted">{label}</span>}
          <div className="flex min-w-0 items-baseline gap-1 truncate">
            <span className="truncate font-medium">{value || placeholder || '请选择'}</span>
            {triggerHint && <span className="flex-none truncate text-fg-subtle">{triggerHint}</span>}
          </div>
        </div>
        {!isSingle && (
          <ChevronDown
            className={cn('h-3.5 w-3.5 flex-none text-fg-subtle transition-transform', open && 'rotate-180')}
          />
        )}
      </button>

      {/* 下拉面板 */}
      {open && !isSingle && (
        <div
          role="listbox"
          className="absolute left-0 right-0 top-[calc(100%+4px)] z-50 max-h-[260px] overflow-auto rounded-lg border border-bg-line bg-white p-1 shadow-2xl"
        >
          {options.map((o) => {
            const label = getLabel(o);
            const hint = getHint(o);
            const active = label === value;
            return (
              <button
                key={label}
                type="button"
                onClick={() => { onChange(label); setOpen(false); }}
                role="option"
                aria-selected={active}
                className={cn(
                  'relative flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left text-sm transition',
                  active
                    ? 'bg-bg-soft text-fg'
                    : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
                )}
              >
                {showIcon && <Sparkles className="h-3.5 w-3.5 flex-none text-fg-subtle" />}
                <div className="flex min-w-0 flex-1 items-baseline gap-1 truncate">
                  <span className="truncate font-medium">{label}</span>
                  {hint && <span className="flex-none truncate text-fg-subtle">{hint}</span>}
                </div>
                {active && <Check className="h-3.5 w-3.5 flex-none text-fg" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}