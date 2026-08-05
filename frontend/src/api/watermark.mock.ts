import type {
  RemoveWatermarkRequest,
  RemoveWatermarkResponse,
  WatermarkTask,
} from '@/types/watermark';

const delay = <T>(value: T, ms = 240) =>
  new Promise<T>((resolve) => setTimeout(() => resolve(value), ms));

export async function removeWatermark(
  _req: RemoveWatermarkRequest
): Promise<RemoveWatermarkResponse> {
  return delay({
    taskId: `wm_${Date.now()}`,
    estimatedCredits: 0,
  });
}

export async function listWatermarkTasks(): Promise<WatermarkTask[]> {
  return delay([]);
}
