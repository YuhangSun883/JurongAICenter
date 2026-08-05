export interface WatermarkRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface RemoveWatermarkRequest {
  fileId: string;
  regions: WatermarkRegion[];
}

export interface RemoveWatermarkResponse {
  taskId: string;
  estimatedCredits: number;
}

export interface WatermarkTask {
  id: string;
  status: 'queued' | 'running' | 'succeeded' | 'failed';
  progress: number;
  sourceFileName: string;
  resultUrl?: string;
  error?: string;
  createdAt: number;
}
