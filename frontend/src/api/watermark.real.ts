import { request } from '@/lib/http';
import type {
  RemoveWatermarkRequest,
  RemoveWatermarkResponse,
  WatermarkTask,
} from '@/types/watermark';

export async function removeWatermark(
  req: RemoveWatermarkRequest
): Promise<RemoveWatermarkResponse> {
  return request<RemoveWatermarkResponse>('/api/tools/watermark-remover/tasks', {
    method: 'POST',
    body: req,
  });
}

export async function listWatermarkTasks(): Promise<WatermarkTask[]> {
  return request<WatermarkTask[]>('/api/tools/watermark-remover/tasks');
}
