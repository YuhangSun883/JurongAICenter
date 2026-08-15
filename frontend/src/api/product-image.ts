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
  listRoles: () =>
    USE_MOCK ? mock.listRoles() : real.listRoles(),
  createAnalysis: (req: Parameters<typeof real.createAnalysis>[0]) =>
    USE_MOCK ? mock.createAnalysis(req) : real.createAnalysis(req),
  getAnalysis: (id: string) =>
    USE_MOCK ? mock.getAnalysis(id) : real.getAnalysis(id),
};

export type {
  ProductImageModel,
  FormatOption,
  ResolutionOption,
  ProductImageExample,
  ProductImageTask,
  ProductImageFormat,
  ProductImageResolution,
  ProductImageAnalysisTask,
  ProductImageAnalysisItem,
  ProductImageAnalysisSection,
} from '@/types/product-image';