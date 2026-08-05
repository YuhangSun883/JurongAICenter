// 创作任务 —— 前端 mock 实现（演示用，真实功能由后端提供）
import type {
  CreateCreationRequest,
  CreationTask,
  AgentChatRequest,
  AgentChatResponse,
  CreationType,
} from './creations';

const tasks: CreationTask[] = [];

export function create(req: CreateCreationRequest): Promise<CreationTask> {
  const task: CreationTask = {
    taskId: `task_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    type: req.type,
    status: 'pending',
    createdAt: Date.now(),
  };
  tasks.unshift(task);
  // 模拟异步
  return new Promise((resolve) => {
    setTimeout(() => {
      task.status = 'running';
      setTimeout(() => {
        task.status = 'success';
        task.resultUrl =
          req.type === 'video'
            ? 'https://example.com/mock-video.mp4'
            : 'https://example.com/mock-image.jpg';
      }, 1500);
      resolve(task);
    }, 200);
  });
}

export function getTask(id: string): Promise<CreationTask> {
  const t = tasks.find((x) => x.taskId === id);
  if (!t) return Promise.reject(new Error('Task not found'));
  return Promise.resolve(t);
}

export function listTasks(q?: { type?: CreationType }): Promise<CreationTask[]> {
  const list = q?.type ? tasks.filter((t) => t.type === q.type) : tasks;
  return Promise.resolve(list);
}

/** Agent 对话：返回固定 mock 回复（真实回复由后端 Agent 服务生成） */
export function agentChat(req: AgentChatRequest): Promise<AgentChatResponse> {
  return Promise.resolve({
    conversationId: req.conversationId || `conv_${Date.now()}`,
    reply: `[mock] 已收到：${req.message.slice(0, 20)}...`,
    actions: [],
  });
}

/**
 * 积分校验（mock 默认返回 insufficient，演示"积分不足"弹窗效果）
 * 真实校验由后端实现：判断用户积分是否足以执行该 action
 */
export function checkCredits(_req: {
  action: string;
}): Promise<{ status: 'ok' | 'insufficient'; remaining: number; required: number }> {
  return Promise.resolve({ status: 'insufficient', remaining: 0, required: 100 });
}