// 工作台全局状态：脚本内容、参考素材、参数、任务列表。
// 这里只放"跨组件共享"的状态，单组件内部状态用 useState 即可。

import { create } from 'zustand';
import type {
  AspectRatio,
  AudioMode,
  Duration,
  ReferenceMedia,
  Resolution,
  VideoModel,
  VideoTask,
} from '@/types/video';

interface WorkbenchState {
  // —— 输入区
  script: string;
  setScript: (s: string) => void;

  // —— 视频设置
  model: VideoModel;
  setModel: (m: VideoModel) => void;
  aspectRatio: AspectRatio;
  setAspectRatio: (v: AspectRatio) => void;
  resolution: Resolution;
  setResolution: (v: Resolution) => void;
  duration: Duration;
  setDuration: (v: Duration) => void;
  audioMode: AudioMode;
  setAudioMode: (v: AudioMode) => void;

  // —— 参考素材
  references: ReferenceMedia[];
  addReference: (m: ReferenceMedia) => void;
  removeReference: (id: string) => void;

  // —— 任务
  tasks: VideoTask[];
  setTasks: (t: VideoTask[]) => void;
  upsertTask: (t: VideoTask) => void;
  selectedTaskId: string | null;
  selectTask: (id: string | null) => void;

  // —— UI
  isSubmitting: boolean;
  setSubmitting: (b: boolean) => void;
}

export const useWorkbenchStore = create<WorkbenchState>((set) => ({
  script: '',
  setScript: (s) => set({ script: s }),

  model: 'Seedance-2.0-VIP',
  setModel: (model) => set({ model }),
  aspectRatio: '9:16',
  setAspectRatio: (aspectRatio) => set({ aspectRatio }),
  resolution: '720p',
  setResolution: (resolution) => set({ resolution }),
  duration: 15,
  setDuration: (duration) => set({ duration }),
  audioMode: 'with-audio',
  setAudioMode: (audioMode) => set({ audioMode }),

  references: [],
  addReference: (m) =>
    set((s) => (s.references.length >= 15 ? s : { references: [...s.references, m] })),
  removeReference: (id) =>
    set((s) => ({ references: s.references.filter((r) => r.id !== id) })),

  tasks: [],
  setTasks: (tasks) => set({ tasks }),
  upsertTask: (t) =>
    set((s) => {
      const idx = s.tasks.findIndex((x) => x.id === t.id);
      if (idx === -1) return { tasks: [t, ...s.tasks] };
      const next = s.tasks.slice();
      next[idx] = t;
      return { tasks: next };
    }),
  selectedTaskId: null,
  selectTask: (id) => set({ selectedTaskId: id }),

  isSubmitting: false,
  setSubmitting: (isSubmitting) => set({ isSubmitting }),
}));

/** 解析脚本里 @token 引用到的素材 id 列表 */
export function parseReferences(script: string, refs: ReferenceMedia[]): string[] {
  const ids = new Set<string>();
  for (const r of refs) {
    if (script.includes('@' + r.token)) ids.add(r.id);
  }
  return Array.from(ids);
}
