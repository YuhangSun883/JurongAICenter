'use client';

import { useState } from 'react';
import { Edit, Plus, PanelLeftClose, PanelLeftOpen, MessageSquare, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface ChatSession {
  id: string;
  title: string;
  pinned?: boolean;
  active?: boolean;
  updatedAt: number;
}

interface ChatHistoryProps {
  sessions: ChatSession[];
  activeId: string | null;
  collapsed: boolean;
  onToggle: () => void;
  onSelect: (id: string) => void;
  onNew: () => void;
  onRename?: (id: string) => void;
}

export function ChatHistory({
  sessions,
  activeId,
  collapsed,
  onToggle,
  onSelect,
  onNew,
  onRename,
}: ChatHistoryProps) {
  if (collapsed) {
    return (
      <div className="flex w-[60px] flex-col border-r border-bg-line bg-bg-card/60">
        <button
          onClick={onToggle}
          className="m-2 grid h-9 w-9 place-items-center rounded-lg text-fg-muted hover:bg-bg-soft hover:text-fg"
          title="展开历史"
        >
          <PanelLeftOpen className="h-4 w-4" />
        </button>
        <button
          onClick={onNew}
          className="mx-2 mb-2 grid h-9 w-9 place-items-center rounded-lg border border-dashed border-bg-line text-fg-muted hover:border-brand/50 hover:text-brand"
          title="新对话"
        >
          <Edit className="h-4 w-4" />
        </button>
      </div>
    );
  }

  return (
    <div className="flex w-[240px] flex-col border-r border-bg-line bg-bg-card/60">
      {/* 头部：标题 + 折叠 */}
      <div className="flex h-12 items-center justify-between border-b border-bg-line/60 px-3">
        <div className="text-sm font-medium text-fg">历史对话</div>
        <button
          onClick={onToggle}
          className="grid h-7 w-7 place-items-center rounded-md text-fg-muted hover:bg-bg-soft hover:text-fg"
          title="收起"
        >
          <PanelLeftClose className="h-4 w-4" />
        </button>
      </div>

      {/* 新对话 */}
      <div className="p-2">
        <button
          onClick={onNew}
          className={cn(
            'flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-sm transition',
            'bg-bg-soft text-fg hover:bg-brand-50 hover:text-brand'
          )}
        >
          <Edit className="h-3.5 w-3.5" />
          新对话
        </button>
      </div>

      {/* 列表 */}
      <div className="flex-1 overflow-auto px-2">
        {sessions.length === 0 ? (
          <EmptyState />
        ) : (
          <ul className="space-y-1">
            {sessions.map((s) => {
              const active = s.id === activeId;
              return (
                <li key={s.id}>
                  <button
                    onClick={() => onSelect(s.id)}
                    className={cn(
                      'group flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm transition',
                      active
                        ? 'bg-brand-50 text-brand'
                        : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
                    )}
                  >
                    <MessageSquare className="h-3.5 w-3.5 flex-none" />
                    <span className="truncate">{s.title || '新对话'}</span>
                    {s.pinned && <span className="ml-auto text-[10px] text-fg-subtle">置顶</span>}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}

function EmptyState() {
  return (
    <div className="grid place-items-center px-3 py-10 text-center text-xs text-fg-subtle">
      <Sparkles className="mb-2 h-5 w-5 text-brand" />
      开启一段新对话吧
    </div>
  );
}
