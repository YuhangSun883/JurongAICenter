// 视频生成 —— 业务侧统一入口
import { USE_MOCK } from './config';
import * as real from './video.real';
import * as mock from './video.mock';

export const videoApi = {
  generateScript: (req: Parameters<typeof real.generateScript>[0]) =>
    USE_MOCK ? mock.generateScript(req) : real.generateScript(req),
  create: (req: Parameters<typeof real.create>[0]) =>
    USE_MOCK ? mock.create(req) : real.create(req),
  createImageToVideo: (imageUrl: string, prompt: string, duration: number, resolution: string) =>
    real.createImageToVideo(imageUrl, prompt, duration, resolution),
  getTask: (id: string) =>
    USE_MOCK ? mock.getTask(id) : real.getTask(id),
  listTasks: (q?: Parameters<typeof real.listTasks>[0]) =>
    USE_MOCK ? mock.listTasks(q) : real.listTasks(q),
  cancel: (id: string) =>
    USE_MOCK ? mock.cancel(id) : real.cancel(id),
  retry: (id: string) =>
    USE_MOCK ? mock.retry(id) : real.retry(id),
};
