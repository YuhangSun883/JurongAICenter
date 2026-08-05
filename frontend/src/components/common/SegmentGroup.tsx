'use client';

import { cn } from '@/lib/utils';

/**
 * 横排按钮组（Segmented Control）
 * 风格：圆角按钮 + 蓝边选中态 + 选中后右侧 ✓ 图标
 *
 * @example
 *   <SegmentGroup
 *     value={resolution}
 *     options={RESOLUTIONS}
 *     onChange={setResolution}
 *     getLabel={(o) => o.label}
 *     getKey={(o) => o.key}
 *   />
 */

interface Props<T> {
  value: string;
  options: readonly T[];
  onChange: (v: string) => void;
  /** 提取 value（默认取整个对象） */
  getKey?: (o: T) => string;
  /** 渲染标签（默认 String(o)） */
  getLabel?: (o: T) => string;
  /** 触发器的渲染尺寸 */
  size?: 'sm' | 'md';
  /** 自定义 trigger className（覆盖默认） */
  itemClassName?: string;
  /** 标签上方的小标签（如"分辨率" / "输出格式"） */
  label?: string;
}

export function SegmentGroup<T>({
  value,
  options,
  onChange,
  getKey,
  getLabel,
  size = 'md',
  itemClassName,
  label,
}: Props<T>) {
  const h = size === 'sm' ? 'h-8 text-xs' : 'h-10 text-sm';
  const flex = size === 'sm' ? 'min-w-[80px]' : 'min-w-[100px]';

  const getK = (o: T) => (getKey ? getKey(o) : String(o));
  const getL = (o: T) => (getLabel ? getLabel(o) : String(o));

  return (
    <div>
      {label && <p className="mb-1.5 text-xs font-medium text-fg-muted">{label}</p>}
      <div className="flex w-full gap-2">
        {options.map((o) => {
          const k = getK(o);
          const l = getL(o);
          const active = k === value;
          return (
            <button
              key={k}
              type="button"
              onClick={() => onChange(k)}
              className={cn(
                'group relative inline-flex flex-1 items-center justify-center rounded-xl border-2 bg-white transition',
                h,
                flex,
                active
                  ? 'border-brand text-fg shadow-soft'
                  : 'border-bg-line text-fg-muted hover:border-brand/40',
                itemClassName
              )}
            >
              <span className="truncate">{l}</span>
              {active && (
                <span className="absolute right-2 top-1/2 -translate-y-1/2">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" className="text-brand">
                    <circle cx="12" cy="12" r="10" fill="currentColor" />
                    <path d="M9 12.5l2 2 4-4" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" fill="none" />
                  </svg>
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}