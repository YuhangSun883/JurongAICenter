import { USE_MOCK } from './config';
import * as real from './product-image.real';
import * as mock from './product-image.mock';

export const productImageApi = {
  listModels: () =>
    USE_MOCK ? mock.listModels() : real.listModels(),
  listResolutions: () =>
    USE_MOCK ? mock.listResolutions() : real.listResolutions(),
  listFormats: () =>
    USE_MOCK ? mock.listFormats() : real.listFormats(),
  listExamples: () =>
    USE_MOCK ? mock.listExamples() : real.listExamples(),
  createTask: (req: Parameters<typeof real.createProductImageTask>[0]) =>
    USE_MOCK ? mock.createProductImageTask(req) : real.createProductImageTask(req),
  getTask: (id: string) =>
    USE_MOCK ? mock.getProductImageTask(id) : real.getProductImageTask(id),
};

export type {
  ProductImageModel,
  FormatOption,
  ResolutionOption,
  ProductImageExample,
  ProductImageTask,
  ProductImageFormat,
  ProductImageResolution,
} from '@/types/product-image';