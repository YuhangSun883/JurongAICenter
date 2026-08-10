'use client';

import { useState } from 'react';
import { Edit, Plus, PanelLeftClose, PanelLeftOpen, MessageSquare, Sparkles, Trash2, Check, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';

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
  onRename?: (id: string, title: string) => Promise<void> | void;
  onDelete?: (id: string) => Promise<void> | void;
}

export function ChatHistory({
  sessions,
  activeId,
  collapsed,
  onToggle,
  onSelect,
  onNew,
  onRename,
  onDelete,
}: ChatHistoryProps) {
  /** 待删除的会话（null 表示不显示弹窗） */
  const [deleteTarget, setDeleteTarget] = useState<ChatSession | null>(null);

  /** 弹窗确认删除 */
  function handleConfirmDelete() {
    if (deleteTarget && onDelete) {
      Promise.resolve(onDelete(deleteTarget.id)).catch((err: unknown) =>
        console.warn('[chat-history] delete failed:', err)
      );
    }
    setDeleteTarget(null);
  }

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
          title="开启新对话"
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

      {/* 开启新对话 */}
      <div className="p-2">
        <button
          onClick={onNew}
          className={cn(
            'flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-sm transition',
            'bg-bg-soft text-fg hover:bg-brand-50 hover:text-brand'
          )}
        >
          <Plus className="h-3.5 w-3.5" />
          开启新对话
        </button>
      </div>

      {/* 列表 */}
      <div className="flex-1 overflow-auto px-2">
        {sessions.length === 0 ? (
          <EmptyState />
        ) : (
          <ul className="space-y-1">
            {sessions.map((s) => (
              <SessionItem
                key={s.id}
                session={s}
                active={s.id === activeId}
                onSelect={onSelect}
                onRename={onRename}
                onAskDelete={(sess) => setDeleteTarget(sess)}
              />
            ))}
          </ul>
        )}
      </div>

      {/* 删除确认弹窗 */}
      <ConfirmDialog
        open={!!deleteTarget}
        title="确认删除对话"
        description={
          deleteTarget
            ? `确认删除对话"${deleteTarget.title || '新对话'}"？\n删除后无法恢复。`
            : ''
        }
        confirmText="删除"
        danger
        onConfirm={handleConfirmDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}

interface SessionItemProps {
  session: ChatSession;
  active: boolean;
  onSelect: (id: string) => void;
  onRename?: (id: string, title: string) => Promise<void> | void;
  onAskDelete?: (session: ChatSession) => void;
}

function SessionItem({ session, active, onSelect, onRename, onAskDelete }: SessionItemProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(session.title || '新对话');

  /** 进入编辑模式 */
  function startEdit() {
    setDraft(session.title || '新对话');
    setEditing(true);
  }

  /** 提交重命名 */
  async function commitEdit() {
    const trimmed = draft.trim() || '新对话';
    setEditing(false);
    if (trimmed === (session.title || '新对话')) return;
    if (onRename) {
      try {
        await onRename(session.id, trimmed);
      } catch (err) {
        console.warn('[chat-history] rename failed:', err);
      }
    }
  }

  /** 取消编辑 */
  function cancelEdit() {
    setEditing(false);
    setDraft(session.title || '新对话');
  }

  /** 请求删除（弹窗状态由父组件 ChatHistory 持有） */
  function handleDelete() {
    if (onAskDelete) onAskDelete(session);
  }

  /** 编辑态：用 input + 保存/取消 */
  if (editing) {
    return (
      <li>
        <div
          className={cn(
            'flex items-center gap-1 rounded-lg px-2 py-1.5',
            'bg-brand-50 text-brand'
          )}
        >
          <MessageSquare className="h-3.5 w-3.5 flex-none" />
          <input
            autoFocus
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                commitEdit();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                cancelEdit();
              }
            }}
            onBlur={commitEdit}
            className="flex-1 bg-transparent text-sm outline-none placeholder:text-fg-subtle"
            placeholder="新对话"
            maxLength={255}
          />
          <button
            onMouseDown={(e) => e.preventDefault()}
            onClick={commitEdit}
            className="grid h-6 w-6 place-items-center rounded text-brand hover:bg-brand/10"
            title="保存"
          >
            <Check className="h-3.5 w-3.5" />
          </button>
          <button
            onMouseDown={(e) => e.preventDefault()}
            onClick={cancelEdit}
            className="grid h-6 w-6 place-items-center rounded text-fg-muted hover:bg-bg-soft"
            title="取消"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      </li>
    );
  }

  /** 默认态：hover 显示修改/删除图标 */
  return (
    <li>
      <div
        className={cn(
          'group flex items-center gap-2 rounded-lg px-2.5 py-2 text-left text-sm transition',
          active
            ? 'bg-brand-50 text-brand'
            : 'text-fg-muted hover:bg-bg-soft hover:text-fg'
        )}
      >
        <button
          onClick={() => onSelect(session.id)}
          className="flex flex-1 items-center gap-2 truncate text-left"
        >
          <MessageSquare className="h-3.5 w-3.5 flex-none" />
          <span className="truncate">{session.title || '新对话'}</span>
          {session.pinned && <span className="ml-auto text-[10px] text-fg-subtle">置顶</span>}
        </button>

        {/* Hover 操作按钮组 */}
        {(onRename || onAskDelete) && (
          <div
            className={cn(
              'flex items-center gap-0.5',
              active
                ? 'opacity-100' // active 状态始终可见
                : 'opacity-0 group-hover:opacity-100' // 非 active 状态 hover 才出
            )}
          >
            {onRename && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  startEdit();
                }}
                className="grid h-6 w-6 place-items-center rounded text-fg-muted hover:bg-brand/10 hover:text-brand"
                title="重命名"
              >
                <Edit className="h-3 w-3" />
              </button>
            )}
            {onAskDelete && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  handleDelete();
                }}
                className="grid h-6 w-6 place-items-center rounded text-fg-muted hover:bg-red-50 hover:text-red-500"
                title="删除"
              >
                <Trash2 className="h-3 w-3" />
              </button>
            )}
          </div>
        )}
      </div>
    </li>
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
