'use client';

import { useEffect, useRef, useState } from 'react';
import { Sparkles, ChevronDown } from 'lucide-react';
import { createPortal } from 'react-dom';
import { cn } from '@/lib/utils';
import { SegmentGroup } from './SegmentGroup';

/**
 * 设置弹窗（Popover）
 * 风格对标 da-ai.cc（截图）：
 *   - 触发器：✨ + 第一行小标签（label） + 第二行粗体主值（value） + 右上箭头
 *   - 弹窗向上展开：白色 + 阴影 + 圆角，分组横排按钮（带 ✓）
 *   - 失焦/选中：自动关闭
 *
 * @example
 *   <SettingsPopover
 *     triggerLabel="图片设置"
 *     triggerValue="1K · JPEG"
 *     groups={[
 *       { label: '分辨率', valueKey: '1K', options: [...], onChange },
 *       { label: '输出格式', valueKey: 'JPEG', options: [...], onChange },
 *     ]}
 *   />
 */

export interface SettingsPopoverGroup<T> {
  label: string;
  valueKey: string;
  options: readonly T[];
  onChange: (k: string) => void;
  getKey?: (o: T) => string;
  getLabel?: (o: T) => string;
}

interface Props {
  triggerLabel: string;
  triggerValue: string;
  triggerIcon?: React.ReactNode;
  groups: SettingsPopoverGroup<any>[];
  className?: string;
}

export function SettingsPopover({ triggerLabel, triggerValue, triggerIcon, groups, className }: Props) {
  const [open, setOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const triggerRef = useRef<HTMLDivElement>(null);
  const popRef = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ bottom: number; left: number; width: number }>({ bottom: 0, left: 0, width: 0 });

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!open || !triggerRef.current) return;
    const rect = triggerRef.current.getBoundingClientRect();
    // 弹窗向上展开：用 bottom 定位
    // 宽度：先等于触发器宽度（minWidth），弹窗可能更宽（按内容撑开）
    setPos({
      bottom: window.innerHeight - rect.top + 4,
      left: rect.left,
      width: rect.width,
    });
    const onClick = (e: MouseEvent) => {
      if (
        popRef.current && !popRef.current.contains(e.target as Node) &&
        triggerRef.current && !triggerRef.current.contains(e.target as Node)
      ) setOpen(false);
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
    <>
      {/* 触发器（两行布局：第一行小标签 + 第二行粗体主值） */}
      <div
        ref={triggerRef}
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'flex w-full cursor-pointer items-center gap-2 rounded-lg border border-bg-line bg-white px-3 py-1.5 text-fg transition select-none',
          open ? 'border-brand/50' : 'hover:border-brand/40'
        )}
      >
        {triggerIcon ?? <Sparkles className="h-3.5 w-3.5 flex-none text-fg-subtle" />}
        <div className="flex min-w-0 flex-1 flex-col text-left leading-tight">
          <span className="truncate text-[10px] text-fg-muted">{triggerLabel}</span>
          <span className="truncate font-medium">{triggerValue}</span>
        </div>
        <ChevronDown
          className={cn('h-3.5 w-3.5 flex-none text-fg-subtle transition-transform', open && 'rotate-180')}
        />
      </div>

      {/* 弹窗（向上展开） */}
      {mounted && open && createPortal(
        <div
          ref={popRef}
          style={{ bottom: pos.bottom, left: pos.left, minWidth: pos.width, width: 'max-content', maxWidth: '90vw' }}
          className={cn('fixed z-[1000] rounded-xl border border-bg-line bg-white p-3 shadow-2xl', className)}
        >
          <div className="space-y-3">
            {groups.map((g, i) => (
              <div key={i}>
                <p className="mb-1.5 text-xs font-medium text-fg-muted">{g.label}</p>
                <SegmentGroup
                  value={g.valueKey}
                  options={g.options}
                  onChange={g.onChange}
                  getKey={g.getKey}
                  getLabel={g.getLabel}
                  size="md"
                />
              </div>
            ))}
          </div>
        </div>,
        document.body
      )}
    </>
  );
}