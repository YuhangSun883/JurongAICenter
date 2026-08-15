// 用户提示词 API
import * as real from './prompt.real';

export type { SavePromptParams, UpdatePromptParams, UserPromptResult } from './prompt.real';

export const promptApi = {
  /**
   * 保存提示词
   */
  savePrompt: (params: import('./prompt.real').SavePromptParams) =>
    real.savePrompt(params),

  /**
   * 编辑提示词
   */
  updatePrompt: (id: number, params: import('./prompt.real').UpdatePromptParams) =>
    real.updatePrompt(id, params),

  /**
   * 获取当前用户的所有提示词
   */
  listPrompts: () => real.listPrompts(),

  /**
   * 使用提示词，使用次数+1
   */
  usePrompt: (id: number) => real.usePrompt(id),

  /**
   * 删除提示词
   */
  deletePrompt: (id: number) => real.deletePrompt(id),
};
