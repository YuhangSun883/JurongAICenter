import { USE_MOCK } from './config';
import * as real from './watermark.real';
import * as mock from './watermark.mock';

export const watermarkApi = {
  removeWatermark: (req: Parameters<typeof real.removeWatermark>[0]) =>
    USE_MOCK ? mock.removeWatermark(req) : real.removeWatermark(req),
  listTasks: () =>
    USE_MOCK ? mock.listWatermarkTasks() : real.listWatermarkTasks(),
};
