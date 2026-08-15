'use client';

import { useEffect, useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import { Sidebar } from '@/components/home/Sidebar';
import { ChatHistory, type ChatSession } from '@/components/agent/ChatHistory';
import { ChatComposer } from '@/components/agent/ChatComposer';
import { InsufficientCreditsDialog } from '@/components/common/InsufficientCreditsDialog';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { agentApi } from '@/api/agent';
import type { AgentMessage, AgentToolCall } from '@/types/agent';

/** toolCall.action 到目标路由的映射 */
const TOOL_ROUTES: Record<string, { route: string; label: string }> = {
  'jump-to-image':           { route: '/ai-image',          label: '图片生成' },
  'jump-to-image-edit':       { route: '/ai-image',          label: '图生图（编辑）' },
  'jump-to-video':            { route: '/ai-video',          label: '视频生成' },
  'jump-to-image-to-video':   { route: '/ai-video',          label: '图生视频' },
  'jump-to-product-image':    { route: '/tools/product-image', label: '商品套图' },
  'jump-to-image-enhancer':   { route: '/tools/image-enhancer', label: '图像增强' },
};

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
  const router = useRouter();
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
  // AI 跳转建议弹窗（toolCall）
  const [pendingToolCall, setPendingToolCall] = useState<AgentToolCall | null>(null);

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

  /** 用户确认 toolCall 跳转 → 携带 prompt + attachmentIds 跳到对应模块 */
  function handleConfirmToolCall() {
    const tc = pendingToolCall;
    if (!tc) return;
    const route = TOOL_ROUTES[tc.action];
    if (!route) {
      setPendingToolCall(null);
      return;
    }
    const params = new URLSearchParams();
    params.set('prefill', 'true');
    params.set('prompt', tc.prompt || '');
    if (tc.attachmentIds && tc.attachmentIds.length > 0) {
      params.set('attachmentIds', tc.attachmentIds.join(','));
    }
    setPendingToolCall(null);
    router.push(`${route.route}?${params.toString()}`);
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

      // 6) 如果 LLM 返回了 toolCall，弹确认框（不是直接跳，让用户决定）
      if (res.toolCall && TOOL_ROUTES[res.toolCall.action]) {
        setPendingToolCall(res.toolCall);
      }
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
      <Sidebar />

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
                    {/* 用户消息：先显示附件图片，再显示文字 */}
                    {m.role === 'user' && m.attachments && m.attachments.length > 0 && (
                      <div className="mb-2 flex flex-wrap gap-2">
                        {m.attachments.map((att, idx) => (
                          <img
                            key={idx}
                            src={att.url || ''}
                            alt={att.name || 'attachment'}
                            className="max-h-40 max-w-[200px] rounded-lg object-cover"
                            loading="lazy"
                          />
                        ))}
                      </div>
                    )}
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

      {/* AI 跳转建议弹窗（toolCall） */}
      <ConfirmDialog
        open={!!pendingToolCall}
        title="AI 建议跳转"
        description={
          pendingToolCall
            ? `AI 认为你想前往【${TOOL_ROUTES[pendingToolCall.action]?.label || pendingToolCall.action}】模块。\n\n意图：${pendingToolCall.prompt}${pendingToolCall.reason ? `\n\n理由：${pendingToolCall.reason}` : ''}${pendingToolCall.attachmentIds?.length ? `\n\n携带素材：${pendingToolCall.attachmentIds.length} 张图片（跳转后会自动填入）` : ''}`
            : ''
        }
        confirmText="前往生成"
        cancelText="取消"
        onConfirm={handleConfirmToolCall}
        onCancel={() => setPendingToolCall(null)}
      />
    </div>
  );
}
