'use client';

import { useEffect, useState, useCallback } from 'react';
import { Sidebar } from '@/components/home/Sidebar';
import { ChatHistory, type ChatSession } from '@/components/agent/ChatHistory';
import { ChatComposer } from '@/components/agent/ChatComposer';
import { InsufficientCreditsDialog } from '@/components/common/InsufficientCreditsDialog';
import { agentApi } from '@/api/agent';
import type { AgentMessage } from '@/types/agent';
import { Coins, Menu, Settings } from 'lucide-react';

/** 把后端的 AgentSession 适配成 ChatHistory 需要的 ChatSession */
function toChatSession(s: { id: string; title: string; updatedAt: number; pinned?: boolean }): ChatSession {
  return {
    id: s.id,
    title: s.title,
    pinned: s.pinned,
    updatedAt: s.updatedAt,
  };
}

export default function AgentPage() {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [credits, setCredits] = useState(0);
  const [sending, setSending] = useState(false);
  // 积分不足弹窗
  const [insufficient, setInsufficient] = useState<{ remaining?: number; required?: number }>({
    remaining: undefined, required: undefined,
  });

  /** 拉会话列表 */
  const refreshSessions = useCallback(async () => {
    try {
      const res = await agentApi.listSessions({ pageSize: 50 });
      setSessions(res.items.map(toChatSession));
    } catch (err) {
      // 后端接口可能未实装（如 /agent/sessions），静默兑底
      console.warn('[agent] listSessions failed:', err);
      setSessions([]);
    }
  }, []);

  /** 拉某会话的消息 */
  const refreshMessages = useCallback(async (sessionId: string) => {
    try {
      const res = await agentApi.listMessages({ sessionId, pageSize: 50 });
      setMessages(res.items);
    } catch (err) {
      console.warn('[agent] listMessages failed:', err);
      setMessages([]);
    }
  }, []);

  /** 拉积分 */
  const refreshCredits = useCallback(async () => {
    try {
      const c = await agentApi.getCredits();
      setCredits(c.used);
    } catch (err) {
      console.warn('[agent] getCredits failed:', err);
      setCredits(0);
    }
  }, []);

  useEffect(() => {
    refreshSessions();
    refreshCredits();
  }, [refreshSessions, refreshCredits]);

  useEffect(() => {
    if (activeId) refreshMessages(activeId);
    else setMessages([]);
  }, [activeId, refreshMessages]);

  /** 新建对话 */
  async function handleNew() {
    const { session } = await agentApi.createSession();
    await refreshSessions();
    setActiveId(session.id);
  }

  /** 重命名对话 */
  async function handleRename(id: string, title: string) {
    const trimmed = title.trim();
    if (!trimmed) return;
    try {
      await agentApi.renameSession({ sessionId: id, title: trimmed });
      await refreshSessions();
    } catch (err) {
      console.warn('[agent] rename failed:', err);
    }
  }

  /** 删除对话 */
  async function handleDelete(id: string) {
    try {
      await agentApi.deleteSession(id);
      // 如果删的是当前选中 → 清空消息区
      if (activeId === id) {
        setActiveId(null);
        setMessages([]);
      }
      await refreshSessions();
    } catch (err) {
      console.warn('[agent] delete failed:', err);
    }
  }

  /** 发送消息 —— 先 checkCredits，再乐观更新请求 send */
  async function handleSend(content: string, attachmentIds: string[]) {
    if (!content.trim() || sending) return;

    // 1) 前端粗算：长度 + 素材数量 → estimated
    const estimated = Math.max(1, Math.ceil(content.length / 100) + attachmentIds.length);

    // 2) 问后端够不够
    try {
      const check = await agentApi.checkCredits({
        action: 'agent-send',
        estimated,
        context: { length: content.length, attachments: attachmentIds.length },
      });

      if (check.status === 'insufficient') {
        setInsufficient({ remaining: check.remaining, required: check.required });
        return; // 不发送，弹充值弹窗
      }
    } catch {
      // 校验接口挂了就先放过，让 send 兜底
    }

    // 3) 乐观更新：先显示用户消息（temp id，等真实返回后被替换）
    const optimisticUserId = 'temp-user-' + Date.now();
    const optimisticUserMsg: AgentMessage = {
      id: optimisticUserId,
      sessionId: activeId ?? '',
      role: 'user',
      content,
      createdAt: Date.now(),
    };
    setMessages((prev) => [...prev, optimisticUserMsg]);

    // 4) 助手“思考中”占位气泡（加重试点提示）
    const thinkingMsgId = 'temp-thinking-' + Date.now();
    setMessages((prev) => [
      ...prev,
      {
        id: thinkingMsgId,
        sessionId: activeId ?? '',
        role: 'assistant',
        content: '正在思考...',
        createdAt: Date.now(),
      },
    ]);

    // 5) 真正发送
    setSending(true);
    try {
      const res = await agentApi.send({
        sessionId: activeId,
        content,
        attachmentIds,
      });
      setActiveId(res.sessionId);
      await refreshSessions();
      await refreshMessages(res.sessionId); // 用真实消息替换乐观消息
      await refreshCredits();
    } catch (err) {
      // 发送失败：移除乐观消息 + 提示
      setMessages((prev) => prev.filter((m) => m.id !== optimisticUserId && m.id !== thinkingMsgId));
      console.error('[agent] send failed:', err);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="min-h-screen pl-[72px]">
      <Sidebar
        bottom={
          <>
            <button className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand" title="积分">
              <span className="grid h-9 w-9 place-items-center rounded-xl bg-brand-50 text-brand">
                <Coins className="h-4 w-4" />
              </span>
              <span className="text-[10px]">{credits}</span>
            </button>
            <button className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand" title="订阅">
              <span className="grid h-9 w-9 place-items-center rounded-xl hover:bg-brand-50">
                <span className="text-base">💎</span>
              </span>
              <span className="text-[10px]">订阅</span>
            </button>
            <button className="flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand" title="菜单">
              <span className="grid h-9 w-9 place-items-center rounded-xl hover:bg-brand-50">
                <Menu className="h-4 w-4" />
              </span>
            </button>
            <button className="mt-1 flex w-12 flex-col items-center gap-0.5 rounded-xl py-1.5 text-fg-muted hover:text-brand" title="设置">
              <span className="grid h-9 w-9 place-items-center rounded-xl hover:bg-brand-50">
                <Settings className="h-4 w-4" />
              </span>
            </button>
          </>
        }
      />

      <div className="flex h-screen">
        <ChatHistory
          sessions={sessions}
          activeId={activeId}
          collapsed={collapsed}
          onToggle={() => setCollapsed((v) => !v)}
          onSelect={setActiveId}
          onNew={handleNew}
          onRename={handleRename}
          onDelete={handleDelete}
        />

        <main className="relative flex flex-1 flex-col">
          {/* 消息流 */}
          <div className="flex-1 overflow-auto px-6 py-6">
            {messages.length === 0 ? (
              <div className="grid h-full place-items-center text-center text-fg-subtle">
                <div>
                  <div className="mx-auto mb-3 grid h-12 w-12 place-items-center rounded-2xl bg-brand-50 text-2xl">🤖</div>
                  <div className="text-sm text-fg">开始一段新对话</div>
                  <div className="mt-1 text-xs">我可以帮你写脚本、生成图片、整理电商素材</div>
                </div>
              </div>
            ) : (
              <div className="mx-auto max-w-3xl space-y-4">
                {messages.map((m) => (
                  <div
                    key={m.id}
                    className={
                      m.role === 'user'
                        ? 'ml-auto max-w-[80%] rounded-2xl bg-brand-50 px-4 py-2.5 text-sm text-fg'
                        : 'mr-auto max-w-[80%] rounded-2xl bg-bg-soft px-4 py-2.5 text-sm text-fg'
                    }
                  >
                    {m.content}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="border-t border-bg-line/60 bg-bg-card/60 px-6 pb-6 pt-3 backdrop-blur-md">
            <ChatComposer
              creditsUsed={credits}
              onSend={handleSend}
              sending={sending}
            />
            <p className="mt-3 text-center text-[11px] text-fg-subtle">
              聚融 是一款 AI 工具，其回答未必正确无误。
            </p>
          </div>
        </main>
      </div>

      {/* 积分不足弹窗 —— 由 checkCredits 触发 */}
      <InsufficientCreditsDialog
        open={insufficient.remaining !== undefined}
        onClose={() => setInsufficient({ remaining: undefined, required: undefined })}
        onPaid={() => { refreshCredits(); setInsufficient({ remaining: undefined, required: undefined }); }}
        remaining={insufficient.remaining}
        required={insufficient.required}
      />
    </div>
  );
}
