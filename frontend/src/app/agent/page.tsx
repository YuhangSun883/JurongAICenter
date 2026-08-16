'use client';

import { useEffect, useState, useCallback, useMemo } from 'react';
import { useRouter } from 'next/navigation';
import { Sidebar } from '@/components/home/Sidebar';
import { ChatHistory, type ChatSession } from '@/components/agent/ChatHistory';
import { ChatComposer } from '@/components/agent/ChatComposer';
import { InsufficientCreditsDialog } from '@/components/common/InsufficientCreditsDialog';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { agentApi } from '@/api/agent';
import type { AgentMessage, AgentToolCall } from '@/types/agent';
import { stripToolCall } from '@/lib/stripToolCall';
import { Coins, Menu, Settings } from 'lucide-react';

/** toolCall.action 到目标路由的映射 */
const TOOL_ROUTES: Record<string, { route: string; label: string }> = {
  'jump-to-image':              { route: '/ai-image',                label: '图片生成' },
  'jump-to-image-edit':         { route: '/ai-image',                label: '图生图（编辑）' },
  'jump-to-video':              { route: '/ai-video',                label: '视频生成' },
  'jump-to-image-to-video':     { route: '/ai-video',                label: '图生视频' },
  'jump-to-product-image':      { route: '/tools/product-image',     label: '生成电商套图' },
  'jump-to-watermark-remover':  { route: '/tools/watermark-remover', label: '水印擦除' },
  'jump-to-subtitle-remover':   { route: '/tools/subtitle-remover',  label: '字幕擦除' },
  'jump-to-image-enhancer':     { route: '/tools/image-enhancer',    label: '画质增强' },
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
  // 2026-08-16:渲染时最终兜底,所有 assistant 消息的 content 强制 stripToolCall 一次
  // (无论 setMessages 时 m.content 写的是 raw 还是 clean,这里都再剥一次,确保无 JSON 残留)
  const safeMessages = useMemo(
    () => (messages ?? []).map(m =>
      m.role === 'assistant'
        ? { ...m, content: stripToolCall(m.content || '').clean }
        : m
    ),
    [messages]
  );
  const [credits, setCredits] = useState(0);
  const [sending, setSending] = useState(false);
  // 积分不足弹窗
  const [insufficient, setInsufficient] = useState<{ remaining?: number; required?: number }>({
    remaining: undefined, required: undefined,
  });
  // AI 跳转建议弹窗（toolCall）
  const [pendingToolCall, setPendingToolCall] = useState<AgentToolCall | null>(null);
  // 2026-08-14:不再需要 useMaterials —— handleSend 现在直接接收完整 PickedMedia(含 url)
  //   之前的反查逻辑有闭包陷阱:ChatComposer addMaterials 异步 setState → handleSend 触发时
  //   page.tsx 的 materials 还是旧值 → find() 找不到 → attachmentInfos 为空 → 图片不显示

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
      // 兜底:历史 AI 回复里也可能残留 toolCall JSON,显示前统一剥离
      setMessages(
        res.items.map((m) =>
          m.role === 'assistant' ? { ...m, content: stripToolCall(m.content || '').clean } : m
        )
      );
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

  /** 发送消息 —— 先 checkCredits，再乐观更新请求 send
   *  2026-08-14:第二个参数改成 PickedMedia[] 完整素材(原 attachmentIds: string[]),
   *  这样可以拿到 url 直接渲染图片,不用再去 useMaterials 反查 */
  async function handleSend(content: string, pickedAttachments: Array<{ id: string; type: 'image' | 'video' | 'audio'; url: string; name: string }>) {
    if (!content.trim() || sending) return;
    const attachmentIds = pickedAttachments.map((m) => m.id);
    // 2026-08-14 增强:用户没手动上传图时,自动从历史消息里取最近 AI 回复中的图片附件
    //   这样 "把这只猫的毛色变成白色" 这种图生图意图能被正确识别
    let effectiveAttachmentIds = attachmentIds;
    if (!attachmentIds || attachmentIds.length === 0) {
      // 倒序遍历 messages,找最近一条带图片附件的 AI 回复
      for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i];
        if (m.role === 'assistant' && Array.isArray(m.attachments) && m.attachments.length > 0) {
          effectiveAttachmentIds = m.attachments.map((a: any) => a.id);
          console.log('[agent] auto-injected attachmentIds from previous AI reply:', effectiveAttachmentIds);
          break;
        }
      }
    }

    // 1) 前端粗算：长度 + 素材数量 → estimated
    const estimated = Math.max(1, Math.ceil(content.length / 100) + attachmentIds.length);

    // 2) 问后端够不够
    try {
      const check = await agentApi.checkCredits({
        action: 'agent-send',
        estimated,
        context: { length: content.length, attachments: effectiveAttachmentIds.length },
      });

      if (check.status === 'insufficient') {
        setInsufficient({ remaining: check.remaining, required: check.required });
        return; // 不发送，弹充值弹窗
      }
    } catch {
      // 校验接口挂了就先放过，让 send 兜底
    }

    // 3) 乐观更新：先显示用户消息（temp id，等真实返回后被替换）
    // 2026-08-14 修复:用入参 pickedAttachments 直接构造 attachments(有 url)
    //   之前从 materials.find() 反查遇到闭包陷阱(addMaterials 是异步 setState),
    //   现在直接用入参数据,简单可靠
    const attachmentInfos = (pickedAttachments || []).map((m) => ({
      id: m.id,
      type: m.type,
      url: m.url,
      name: m.name,
    }));
    const optimisticUserId = 'temp-user-' + Date.now();
    const optimisticUserMsg: AgentMessage = {
      id: optimisticUserId,
      sessionId: activeId ?? '',
      role: 'user',
      content,
      createdAt: Date.now(),
      attachments: attachmentInfos.length > 0 ? attachmentInfos : undefined,
    };
    setMessages((prev) => [...prev, optimisticUserMsg]);

    // 4) 助手"思考中"占位气泡（加重试点提示）
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

    // 5) 真正发送(流式:逐字更新 AI 气泡)
    //    2026-08-14 修复:如果 activeId 为 null(还没建会话),先建一个
    //      ⚠️ 不要立即 setActiveId!否则 useEffect 立刻 refreshMessages(新空会话)
    //        → setMessages([]) → 乐观消息被清空 → 视觉上"对话消失"
    //      改用 workingSessionId 局部变量,等 sendStream 走完再用 meta.sessionId 切
    setSending(true);
    let workingSessionId = activeId;
    let didCreateSession = false;
    try {
      if (!workingSessionId) {
        const { session } = await agentApi.createSession();
        workingSessionId = session.id;
        didCreateSession = true;
        // ⚠️ 不要 setActiveId/refreshSessions,等 sendStream 完成后一起做
      }

      const res = await agentApi.sendStream({
        sessionId: workingSessionId,
        content,
        attachmentIds: effectiveAttachmentIds,
      });

      if (!res.ok) {
        // 2026-08-14 修复:把后端业务码透传给用户(避免 catch 块默默移除乐观消息)
        const errText = await res.text().catch(() => '');
        throw new Error(`HTTP ${res.status} ${res.statusText} - ${errText.slice(0, 200)}`);
      }

      if (!res.body) throw new Error('No response body from stream');

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let accumulated = '';        // 累积的 AI 文本(不含 [META])
      let meta: any = null;         // 流结束时的元数据
      let loggedToolCall = false;   // 是否已打印过 toolCall 调试日志(避免刷屏)
      let loggedFirstToken = false; // 是否已打印过首个 token(用于确认后端格式)
      // 2026-08-14 兜底:90s 内 reader 没收到任何数据就主动中断,refreshMessages 拉 DB
      //   (避免 Next.js 代理 buffer 或网络层卡死导致 UI 一直"正在思考")
      const streamTimeoutMs = 90_000;
      let lastChunkAt = Date.now();

      const watchdog = setInterval(() => {
        if (Date.now() - lastChunkAt > streamTimeoutMs) {
          console.warn('[agent] SSE stream watchdog timeout, aborting reader');
          reader.cancel().catch(() => {});
          clearInterval(watchdog);
        }
      }, 5_000);

      // 2026-08-14 关键修复:前端 reader 拿到的是 SSE 协议原文("data: <token>\n\n" 多行拼接),
      //   之前的代码把整段当 LLM 文本直接显示,导致 "data: 好 data: 的..." 污染。
      //   现在按 \n\n 切事件 → 每个事件里所有 data: 行拼成 token → 累积到 accumulated。
      let sseBuffer = '';

      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          lastChunkAt = Date.now();
          sseBuffer += decoder.decode(value, { stream: true });

          // 按 \n\n 切出"完整事件",最后一个可能不完整,留给下个循环
          const events = sseBuffer.split('\n\n');
          sseBuffer = events.pop() ?? '';

          for (const ev of events) {
            if (!ev.trim()) continue;
            // 提取本事件的所有 data: 行,拼接成 token
            // (Spring SseEmitter.event().data(token) 生成 "data: <token>\n\n",
            //  正常情况一个事件只有一行 data;这里按规范把多行 data 用 \n 拼接)
            const dataLines = ev
              .split('\n')
              .filter(line => line.startsWith('data:'))
              .map(line => line.substring(5).replace(/^ /, ''));  // 去 "data:" 后可能的前导空格
            if (dataLines.length === 0) continue;
            const token = dataLines.join('\n');
            if (!token) continue;
            accumulated += token;
          }

          // 检测 [META] 标记(only in 累积文本里查)
          const metaIdx = accumulated.indexOf('[META]');
          if (metaIdx >= 0) {
            const metaJson = accumulated.substring(metaIdx + 6);
            accumulated = accumulated.substring(0, metaIdx);
            try {
              meta = JSON.parse(metaJson);
            } catch (e) {
              console.warn('[agent] failed to parse META:', e);
            }
          }

          // 更新 AI 气泡 —— 实时剥离工具调用 JSON（避免用户看到"无关内容"闪一下再消失）
          // stripToolCall 内部已处理 [META] 切尾 + toolCall/tool_call/function_call/action/actions 多 key
          // 2026-08-16:如果 meta 已包含 toolCall(已识别为跳转意图),直接折叠成简短提示
          //   避免流式过程中显示协议性引导语("好的,跳转...")和潜在残留 JSON
          const isToolCallReady = !!(meta && meta.toolCall && TOOL_ROUTES[meta.toolCall.action]);
          const displayText = isToolCallReady
            ? 'AI 已理解你的需求'
            : stripToolCall(accumulated).clean;
          setMessages(prev => prev.map(m =>
            m.id === thinkingMsgId
              ? { ...m, content: displayText, _folded: isToolCallReady }
              : m
          ));
        }
      } finally {
        clearInterval(watchdog);
        // 流结束:打印完整 accumulated,用于诊断"无关内容"来源
        console.log('[agent] stream ended. FINAL accumulated raw =', JSON.stringify(accumulated));
        console.log('[agent] stream ended. FINAL clean =', JSON.stringify(stripToolCall(accumulated).clean));
        console.log('[agent] stream ended. FINAL meta =', JSON.stringify(meta));
      }

      // 6) 流结束,处理元数据
      if (meta) {
        if (didCreateSession) {
          // 新建的会话:切到新会话,useEffect 触发 refreshMessages(此时 DB 已有 2 条消息)
          setActiveId(meta.sessionId);
        }
        if (meta.toolCall && TOOL_ROUTES[meta.toolCall.action]) {
          // 2026-08-16 关键修复:触发跳转弹窗时,把对应的 AI 气泡内容折叠成"AI 已理解"
          // 避免流式累积中的协议性引导语("好的,跳转...")和潜在残留 JSON 显示给用户
          setMessages(prev => prev.map(m =>
            m.id === thinkingMsgId
              ? { ...m, content: 'AI 已理解你的需求', _folded: true }
              : m
          ));
          setPendingToolCall({
            action: meta.toolCall.action,
            prompt: meta.toolCall.prompt,
            reason: meta.toolCall.reason,
            attachmentIds: effectiveAttachmentIds,  // 流式拒收图,这里用于透传
          });
        }
        await refreshSessions();
        await refreshCredits();
      } else if (didCreateSession) {
        // 异常路径但新建了会话:主动 refreshMessages(避免 UI 一直是乐观消息)
        await refreshMessages(workingSessionId);
      }
    } catch (err) {
      // 2026-08-14 修复:失败时不要移除乐观消息,改成把思考气泡改成错误提示
      // 之前的逻辑会清空用户消息,导致"对话发出去立马消失"
      const errMsg = err instanceof Error ? err.message : String(err);
      console.error('[agent] sendStream failed:', err);
      if (didCreateSession) {
        // 即使失败也切到新会话,让用户能看到自己发的消息(避免下一轮又新建一个)
        setActiveId(workingSessionId);
        await refreshSessions();
      }
      setMessages(prev => prev.map(m =>
        m.id === thinkingMsgId
          ? { ...m, content: `⚠️ 发送失败: ${errMsg}` }
          : m
      ));
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
            {(safeMessages ?? []).length === 0 ? (
              <div className="grid h-full place-items-center text-center text-fg-subtle">
                <div>
                  <div className="mx-auto mb-3 grid h-12 w-12 place-items-center rounded-2xl bg-brand-50 text-2xl">🤖</div>
                  <div className="text-sm text-fg">开始一段新对话</div>
                  <div className="mt-1 text-xs">我可以帮你写脚本、生成图片、整理电商素材</div>
                </div>
              </div>
            ) : (
              <div className="mx-auto max-w-3xl space-y-4">
                {(safeMessages ?? []).map((m) => (
                  <div
                    key={m.id}
                    className={
                      m.role === 'user'
                        ? 'ml-auto max-w-[80%] rounded-2xl bg-brand-50 px-4 py-2.5 text-sm text-fg'
                        : 'mr-auto max-w-[80%] rounded-2xl bg-bg-soft px-4 py-2.5 text-sm text-fg'
                    }
                  >
                    {/* 用户消息：先显示附件,再显示文字 */}
                    {m.role === 'user' && m.attachments && m.attachments.length > 0 && (
                      <div className="mb-2 flex flex-wrap gap-2">
                        {m.attachments.map((att, idx) => (
                          // 2026-08-15:视频附件用 <video> 标签,避免 <img> 解析 mp4 失败
                          att.type === 'video' ? (
                            <video
                              key={idx}
                              src={att.url || ''}
                              className="max-h-40 max-w-[200px] rounded-lg object-cover bg-black"
                              muted
                              playsInline
                              preload="metadata"
                              onLoadedData={(e) => {
                                // 加载完元数据后跳到第 0.1s 截一帧作为气泡内封面,避免黑屏
                                const v = e.currentTarget;
                                v.currentTime = 0.1;
                              }}
                            />
                          ) : (
                            <img
                              key={idx}
                              src={att.url || ''}
                              alt={att.name || 'attachment'}
                              className="max-h-40 max-w-[200px] rounded-lg object-cover"
                              loading="lazy"
                            />
                          )
                        ))}
                      </div>
                    )}
                    {m.role === 'assistant' ? stripToolCall(m.content || '').clean : m.content}
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
