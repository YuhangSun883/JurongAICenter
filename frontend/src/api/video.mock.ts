// 前端内置的 mock：让前端不依赖后端也能跑起来。
// 后端就绪后把 .env 的 NEXT_PUBLIC_USE_MOCK 改成 false 即可。

import type {
  CreateVideoRequest,
  CreateVideoResponse,
  GenerateVideoScriptRequest,
  GenerateVideoScriptResponse,
  ListTasksQuery,
  ListTasksResponse,
  VideoTask,
  TaskStatus,
} from '@/types/video';

const tasks = new Map<string, VideoTask>();
const listeners = new Map<string, Set<(t: VideoTask) => void>>();

const MOCK_SCRIPT =
  '以电商投流短视频风格生成一条 30 秒脚本：开场 3 秒突出商品卖点，中段展示模特使用场景和细节特写，结尾加入明确购买引导，整体节奏轻快、画面干净、适合信息流广告。';

function uid() {
  return `task_${Math.random().toString(36).slice(2, 10)}`;
}

function estimateCredits(req: CreateVideoRequest): number {
  const base = req.duration * 2;
  return req.model.includes('VIP') ? base * 1.5 : base;
}

function delay<T>(value: T, ms = 300): Promise<T> {
  return new Promise((r) => setTimeout(() => r(value), ms));
}

function emit(t: VideoTask) {
  listeners.get(t.id)?.forEach((cb) => cb(t));
}

function tick(taskId: string) {
  const t = tasks.get(taskId);
  if (!t) return;
  if (t.status === 'queued' && Date.now() - t.createdAt > 1500) {
    t.status = 'running';
    t.updatedAt = Date.now();
    emit(t);
  }
  if (t.status === 'running') {
    t.progress = Math.min(100, t.progress + Math.random() * 8 + 2);
    t.updatedAt = Date.now();
    if (t.progress >= 100) {
      t.status = 'succeeded';
      t.resultUrl =
        'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4';
      t.thumbnailUrl = 'https://picsum.photos/seed/' + taskId + '/640/360';
    }
    emit(t);
  }
}

// 启动一个全局 tick 模拟服务推进
let tickerStarted = false;
function ensureTicker() {
  if (tickerStarted) return;
  tickerStarted = true;
  setInterval(() => {
    for (const id of tasks.keys()) tick(id);
  }, 700);
}

export async function create(req: CreateVideoRequest): Promise<CreateVideoResponse> {
  ensureTicker();
  const id = uid();
  const now = Date.now();
  const task: VideoTask = {
    id,
    status: 'queued',
    progress: 0,
    request: req,
    estimatedCredits: estimateCredits(req),
    createdAt: now,
    updatedAt: now,
  };
  tasks.set(id, task);
  return delay({ taskId: id, estimatedCredits: task.estimatedCredits });
}

export async function generateScript(
  req: GenerateVideoScriptRequest
): Promise<GenerateVideoScriptResponse> {
  const duration = req.duration;
  const suffix = req.brief?.trim() ? ` 重点结合用户补充要求：${req.brief.trim()}` : '';
  return delay(
    {
      script: MOCK_SCRIPT.replace('30 秒', `${duration} 秒`) + suffix,
      model: req.model,
      creditsEstimated: 0,
    },
    650
  );
}

export async function getTask(id: string): Promise<VideoTask> {
  ensureTicker();
  const t = tasks.get(id);
  if (!t) throw new Error('task not found: ' + id);
  return delay(t, 200);
}

export async function listTasks(q: ListTasksQuery = {}): Promise<ListTasksResponse> {
  ensureTicker();
  let items = Array.from(tasks.values()).sort((a, b) => b.createdAt - a.createdAt);
  if (q.status) items = items.filter((t) => t.status === q.status);
  const page = q.page ?? 1;
  const pageSize = q.pageSize ?? 20;
  const start = (page - 1) * pageSize;
  return delay({ items: items.slice(start, start + pageSize), total: items.length });
}

export async function cancel(id: string): Promise<void> {
  const t = tasks.get(id);
  if (!t) return;
  if (t.status === 'queued' || t.status === 'running') {
    t.status = 'failed';
    t.error = '已取消';
    t.updatedAt = Date.now();
    emit(t);
  }
}

export async function retry(id: string): Promise<CreateVideoResponse> {
  const t = tasks.get(id);
  if (!t) throw new Error('task not found');
  return create(t.request);
}

/** 订阅任务更新（用于把 mock 推进也能即时推给前端，预留 SSE 接入点） */
export function subscribeTask(id: string, cb: (t: VideoTask) => void): () => void {
  const set = listeners.get(id) ?? new Set();
  set.add(cb);
  listeners.set(id, set);
  return () => set.delete(cb);
}
