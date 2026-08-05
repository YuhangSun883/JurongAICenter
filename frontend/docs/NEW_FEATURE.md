# 新增功能的标准流程

> 每加一个功能，**严格按下面 7 步走**，少一步后期整合必乱。

## 1. 在 `src/api/config.ts` 登记

```ts
export const APIS = [
  // ... 现有
  'image',  // ← 新增
] as const;
```

## 2. 在 `src/types/<name>.ts` 写类型契约

```ts
// src/types/image.ts
export interface CreateImageRequest { /* ... */ }
export interface CreateImageResponse { /* ... */ }
export interface ImageTask { /* ... */ }
```

## 3. 在 `src/api/<name>.real.ts` 写真实后端调用

```ts
import { request } from '@/lib/http';
import type { CreateImageRequest, CreateImageResponse } from '@/types/image';

const API = '/api/images';

export async function create(req: CreateImageRequest): Promise<CreateImageResponse> {
  return request<CreateImageResponse>(API, { method: 'POST', body: req });
}
```

## 4. 在 `src/api/<name>.mock.ts` 写本地假实现

```ts
export async function create(req: CreateImageRequest): Promise<CreateImageResponse> {
  return delay({ taskId: 'img_' + Date.now(), estimatedCredits: 5 });
}
```

## 5. 在 `src/api/<name>.ts` 写 USE_MOCK 分发

```ts
import { USE_MOCK } from './config';
import * as real from './image.real';
import * as mock from './image.mock';

export const imageApi = {
  create: (req: Parameters<typeof real.create>[0]) =>
    USE_MOCK ? mock.create(req) : real.create(req),
};
```

## 6. 在 `src/api/index.ts` 导出

```ts
export { imageApi } from './image';
```

## 7. 在 `docs/API.md` 加表 + `docs/CHANGELOG.md` 加一行

`docs/API.md` 顶部表格加一行：
```
| `image` 图片生成 | ✅ mock | `src/api/image.ts` | §5 |
```

文档末尾新增小节：
```md
## 5. 图片生成 (`src/api/image.ts`)
| ✅ | 方法 | 路径 | ... |
```

`docs/CHANGELOG.md` 加：
```md
## 2026-08-XX image · 新增图片生成领域
- 新增：POST /api/images  → 创建图片生成任务
- 新增：GET  /api/images/:id  → 查询任务详情
```

---

## 检查清单

每次新增完成后，对照打钩：

- [ ] `src/api/config.ts` 登记了
- [ ] `src/types/<name>.ts` 类型写完
- [ ] `src/api/<name>.real.ts` 真实后端调用写完
- [ ] `src/api/<name>.mock.ts` mock 写完
- [ ] `src/api/<name>.ts` USE_MOCK 分发写完
- [ ] `src/api/index.ts` 导出
- [ ] `docs/API.md` 接口表 + 顶部导航
- [ ] `docs/CHANGELOG.md` 一条记录
- [ ] 业务组件 import：`import { imageApi } from '@/api'`
