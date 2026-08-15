'use client';

import { useEffect, useRef, useState } from 'react';
import { ChevronDown, User, Check } from 'lucide-react';
import { cn } from '@/lib/utils';

/** 阻止滚动事件冒泡到父级 + 阻止 overscroll 触发父级滚动 */
function stopScrollPropagation(e: React.WheelEvent<HTMLDivElement>) {
  const target = e.currentTarget;
  if (!target) return;
  // 滚到底/顶时不让父级接着滚
  const atTop = target.scrollTop === 0;
  const atBottom = target.scrollHeight - target.clientHeight - target.scrollTop <= 1;
  if ((e.deltaY < 0 && atTop) || (e.deltaY > 0 && atBottom)) {
    e.stopPropagation();
    e.preventDefault();
  }
}

export interface RoleCategory {
  key: string;
  label: string;
}

interface Props {
  value: string;
  options: RoleCategory[];
  onChange: (key: string) => void;
}

export function RoleCategorySelect({ value, options, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

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

  const current = options.find((o) => o.key === value);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'btn-ghost h-9 px-3',
          open && 'border-brand/50 text-fg'
        )}
      >
        <User className="h-3.5 w-3.5 text-fg-subtle" />
        {current?.label ?? '选择分类'}
        <ChevronDown
          className={cn('h-3.5 w-3.5 transition', open && 'rotate-180')}
        />
      </button>

      {open && (
        <div
          className="card scrollbar-hidden absolute left-0 top-[calc(100%+6px)] z-50 w-[180px] max-h-[280px] overflow-y-auto overscroll-contain p-1 shadow-2xl"
          onWheel={stopScrollPropagation}
          role="listbox"
        >
          {options.map((o) => {
            const active = o.key === value;
            return (
              <button
                key={o.key}
                type="button"
                onClick={() => { onChange(o.key); setOpen(false); }}
                role="option"
                aria-selected={active}
                className={cn(
                  'flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm transition',
                  active
                    ? 'bg-brand-50 text-brand'
                    : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
                )}
              >
                <User className="h-3.5 w-3.5" />
                <span className="flex-1 truncate">{o.label}</span>
                {active && <Check className="h-3.5 w-3.5" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
