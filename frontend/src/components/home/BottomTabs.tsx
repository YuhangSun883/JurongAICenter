'use client';

import { useState } from 'react';
import { cn } from '@/lib/utils';

const TABS = ['发现', '视频', '图片', '画布'] as const;

export function BottomTabs() {
  const [active, setActive] = useState<(typeof TABS)[number]>('发现');
  return (
    <div className="mt-8 flex items-center gap-1">
      {TABS.map((t) => (
        <button
          key={t}
          onClick={() => setActive(t)}
          className={cn(
            'rounded-full px-4 py-1.5 text-sm transition',
            active === t
              ? 'bg-brand text-white shadow-glow'
              : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
          )}
        >
          {t}
        </button>
      ))}
    </div>
  );
}
