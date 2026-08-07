# API 文档

> **API 真理源**。所有端点契约、请求/响应、错误码、curl 示例都在这里。
> 与 README.md 区别：README 讲项目定位 / 架构 / 状态；API 讲"**怎么调**"。
> 维护者：所有人（**加/改端点必须同步更新本文件**）。

## 0. 约定

- **Base URL**：
  - 本地 dev：`http://localhost:8080`
  - 生产（待部署）：`http://192.140.163.161:18080`
- **鉴权**：`Authorization: Bearer <accessToken>`（除 `/api/auth/*` 和 `/api/health`）
- **响应格式**：`{code: int, message: string, data: <T>|null}`
- **错误码**分段：见 §11

---

## 1. Auth（无需 JWT）

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| POST | `/api/auth/register` | `{email, password, displayName?}` | `{code, message, data: AuthResponse}` | ✅ 已实现 |
| POST | `/api/auth/login` | `{email, password}` | `{code, message, data: AuthResponse}` | ✅ 已实现 |
| POST | `/api/auth/refresh` | `{refreshToken}` | `{code, message, data: AuthResponse}` | ✅ 已实现 |

`AuthResponse`:
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "userId": 1,
  "email": "user@example.com",
  "role": "USER"
}
```

---

## 2. User（需 JWT）

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| GET | `/api/users/me` | — | `{code, message, data: UserResponse}` | ✅ 已实现 |
| PATCH | `/api/users/me` | `{displayName?, password?}` | `{code, message, data: UserResponse}` | ✅ 已实现 |
| GET | `/api/users/me/quota` | — | `{code, message, data: {credits, monthlyQuota, quotaUsed, plan}}` | ✅ 已实现 |
| GET | `/api/users/me/groups` | — | `{code, message, data: GroupResponse[]}` | ✅ 已实现 |

`UserResponse`:
```json
{
  "id": 1,
  "email": "user@example.com",
  "displayName": "...",
  "role": "USER",
  "credits": 0,
  "monthlyQuota": 50,
  "quotaUsed": 0,
  "plan": "FREE"
}
```

---

## 3. 配额（提前预留，**当前不关联"使用一次扣费多少"**）

> **B 任务**（Phase 8 之前）：**只做查询，不做扣费**。Phase 8 才写完整 Billing。

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| GET | `/api/users/me/quota` | — | `{code, message, data: {credits, monthlyQuota, quotaUsed, plan}}` | ✅ 已实现（B6/B7）|

`data` 字段直接从 `users` 表读 `credits / monthly_quota / quota_used / plan`。

---

## 4. Workflow（需 JWT）

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| GET | `/api/workflows` | `?page=1&pageSize=20` | `{code, message, data: WorkflowResponse[]}` | ✅ 部分实现（`listByUser` TODO(C)） |
| POST | `/api/workflows` | `{name, description?, graphJson, isPublic?, isTemplate?}` | `{code, message, data: WorkflowResponse}` | ✅ 已实现 |
| GET | `/api/workflows/{id}` | — | `{code, message, data: WorkflowResponse}` | ✅ 已实现 |
| PATCH | `/api/workflows/{id}` | `{name?, description?, graphJson?, isPublic?, isTemplate?}` | `{code, message, data: WorkflowResponse}` | ✅ 已实现 |
| DELETE | `/api/workflows/{id}` | — | `{code, message}` | ✅ 已实现 |

`WorkflowResponse`:
```json
{
  "id": 1,
  "name": "...",
  "description": "...",
  "graphJson": "{...}",
  "thumbnailUrl": null,
  "isPublic": false,
  "createdAt": "2026-08-02T10:00:00",
  "updatedAt": "2026-08-02T10:00:00"
}
```

---

## 5. Generation（需 JWT）

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| POST | `/api/generate` | `{workflowId, params?}` | `{code, message, data: GenerateResponse}` | ✅ 已实现 |
| GET | `/api/jobs` | `?page=1&pageSize=20` | `{code, message, data: JobResponse[]}` | ✅ 部分实现（listJobs TODO(C)） |
| GET | `/api/jobs/{id}` | — | `{code, message, data: JobResponse}` | ✅ 已实现 |
| DELETE | `/api/jobs/{id}` | — | `{code, message}` | 📋 计划中（C 任务） |
| GET | `/api/jobs/{id}/result/{filename}` | — | 文件流 或 302 redirect 到 MinIO presigned URL | 📋 计划中（C 任务） |

`GenerateRequest`:
```json
{
  "workflowId": 1,
  "params": { /* 可选：用户覆盖的 workflow 参数 */ }
}
```

`GenerateResponse`:
```json
{
  "jobId": 1,
  "status": "PENDING"
}
```

`JobResponse`:
```json
{
  "id": 1,
  "workflowId": 1,
  "templateId": null,
  "status": "RUNNING",
  "creditsCost": 0,
  "durationMs": null,
  "resultUrls": null,
  "errorMessage": null,
  "createdAt": "2026-08-02T10:00:00",
  "completedAt": null
}
```

`status` 取值：`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`

---

## 6. Health（无需 JWT）

| 方法 | 路径 | 请求 | 响应 | 状态 |
|------|------|------|------|------|
| GET | `/api/health` | — | `{status: "ok", service: "aicenter-backend", version: "0.1.0"}` | ✅ 已实现 |

> **不是** `/actuator/health`（Spring Boot actuator 端点**未启用**）—— 走自定义 `/api/health`。

---

## 6.5 Media 资产库 / 素材（需 JWT）

> **2026-08-07 落地**（V8 migration + `MediaController` + `MediaLibraryController`）。详见 `frontend/docs/CHANGELOG.md`。
>
> 资产库是用户维度的容器，注册时自动建 2 个系统默认库：
> - **"我的资产"** (`type=system-uploaded`)：装用户上传的素材
> - **"AI 生成结果"** (`type=system-ai`)：装 AI 生成的素材
>
> 用户可建自定义库 (`type=custom`)，可重命名/删除；系统库**不可**重命名/删除。
> 删除自定义库时**级联删除**库内素材（同时删除 MinIO 对象）。

### 6.5.1 资产库

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| GET    | `/api/media/libraries` | — | `{code, message, data: MediaLibraryResponse[]}` | ✅ 已实现 |
| POST   | `/api/media/libraries` | `CreateLibraryRequest` | `{code, message, data: MediaLibraryResponse}` | ✅ 已实现 |
| PATCH  | `/api/media/libraries/{id}` | `RenameLibraryRequest` | `{code, message, data: MediaLibraryResponse}` | ✅ 已实现 |
| DELETE | `/api/media/libraries/{id}` | — | `{code, message}` | ✅ 已实现 |

```json
// MediaLibraryResponse
{
  "id": 1,
  "name": "我的资产",
  "type": "system-uploaded",   // system-uploaded / system-ai / custom
  "iconKey": "folder",
  "description": null,
  "sortOrder": 0,
  "assetCount": 12,
  "createdAt": "2026-08-01T10:00:00",
  "updatedAt": "2026-08-07T12:00:00"
}

// CreateLibraryRequest
{ "name": "工作素材", "iconKey": "folder", "description": "可选" }

// RenameLibraryRequest
{ "name": "工作素材 2", "iconKey": "folder" }
```

**系统库保护**：对 `type=system-*` 的库做 `PATCH` / `DELETE` 返回 `7003=MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY`。

### 6.5.2 素材

| 方法 | 路径 | 请求体 | 响应 | 状态 |
|------|------|--------|------|------|
| GET    | `/api/media/assets?libraryId=&type=&source=&keyword=&page=&pageSize=` | — | `{code, message, data: PageResult<MediaAssetResponse>}` | ✅ 已实现 |
| GET    | `/api/media/assets/{id}` | — | `{code, message, data: MediaAssetResponse}` | ✅ 已实现 |
| POST   | `/api/media/assets` | `multipart/form-data (file, libraryId?)` | `{code, message, data: MediaUploadResponse}` | ✅ 已实现 |
| PATCH  | `/api/media/assets/{id}` | `PatchAssetRequest` | `{code, message, data: MediaAssetResponse}` | ✅ 已实现 |

> **上传类型白名单**（`POST /api/media/assets`）：
> - 图片：`image/jpeg | image/png | image/gif | image/webp | image/bmp`，扩展名 `jpg/jpeg/png/gif/webp/bmp`
> - 视频：`video/mp4 | video/webm | video/quicktime | video/x-msvideo | video/x-matroska`，扩展名 `mp4/webm/mov/avi/mkv`
> - 音频：`audio/mpeg | audio/wav | audio/ogg | audio/aac | audio/x-m4a | audio/mp4`，扩展名 `mp3/wav/ogg/m4a/aac`
> - **校验规则**：MIME 与扩展名都必须在白名单内且**指向同一类别**（image/video/audio），否则返回 `7300 MEDIA_ASSET_TYPE_INVALID`。
> - **黑名单**（直接拒绝）：可执行（exe/msi/bat/sh/jar/apk 等）、脚本（js/php/jsp 等）、文档（pdf/doc/xls/ppt 等）、压缩包（zip/rar/7z 等）、配置文件/证书（env/pem/key 等）、HTML/SVG/XML 等。
| DELETE | `/api/media/assets/{id}` | — | `{code, message}` | ✅ 已实现 |
| POST   | `/api/media/assets/batch-delete` | `BatchDeleteAssetsRequest` | `{code, message, data: {deleted, requested}}` | ✅ 已实现 |

```json
// MediaAssetResponse（列表 / 详情 / 改名返回）
{
  "id": 100,
  "libraryId": 1,
  "libraryName": "我的资产",
  "type": "image",            // image / video / audio
  "source": "uploaded",       // uploaded / ai-generated
  "name": "商品-主图-01.png",
  "mimeType": "image/png",
  "sizeBytes": 102400,
  "width": 800,
  "height": 800,
  "durationSec": null,
  "url": "https://minio/...?X-Amz-Signature=...",   // 24h 预签名 URL
  "sourceTool": "upload",     // upload / video / image / canvas / agent
  "sourceTaskId": null,
  "createdAt": "2026-08-07T10:00:00",
  "updatedAt": "2026-08-07T10:00:00"
}

// MediaUploadResponse
{ "id": 100, "url": "https://...", "name": "商品-主图-01.png", "type": "image", "size": 102400 }

// PatchAssetRequest
{ "name": "商品-主图-01-改.png" }

// BatchDeleteAssetsRequest
{ "ids": [100, 101, 102] }
```

**上传限制**（`MediaServiceImpl.checkSize`）：
- 图片 ≤ **20 MB** → 超限返 `7021=MEDIA_FILE_TOO_LARGE`
- 视频 ≤ **200 MB**
- 音频 ≤ **50 MB**
- 空文件返 `7022=MEDIA_FILE_EMPTY`
- 不支持的 MIME/扩展名返 `7011=MEDIA_ASSET_TYPE_INVALID`
- 未指定 `libraryId` → 默认进 "我的资产" 库

**MinIO 存储**：
- 对象 Key 格式：`media/{userId}/{yyyy-MM}/{uuid}.{ext}`
- 列表/详情返回的 `url` 是 **24h 预签名 URL**（`StorageService.getPresignedUrl`）
- 前端不要把 `url` 持久化到 localStorage，跨页面/跨天会失效

### 6.5.3 错误码

| code | 含义 |
|------|------|
| 7001 | `MEDIA_LIBRARY_NAME_DUPLICATE` 库名重复（同一用户下唯一）|
| 7002 | `MEDIA_LIBRARY_NOT_FOUND` 库不存在或不属于当前用户 |
| 7003 | `MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY` 系统库不可改/删 |
| 7010 | `MEDIA_ASSET_NOT_FOUND` 素材不存在或不属于当前用户 |
| 7011 | `MEDIA_ASSET_TYPE_INVALID` 文件类型不支持 |
| 7020 | `MEDIA_UPLOAD_FAILED` MinIO 上传失败 |
| 7021 | `MEDIA_FILE_TOO_LARGE` 文件超过大小限制 |
| 7022 | `MEDIA_FILE_EMPTY` 文件为空 |

### 6.5.4 业务接入点

- **注册自动建库**：`AuthServiceImpl` 在 `register` 成功后调用 `MediaLibraryService.createDefaultLibraries(userId)`，建 2 个系统库
- **AI 生成落库**：`GenerationServiceImpl` 在生成完成回调中调用 `MediaService.recordAiGenerated(...)`，把产物写入 "AI 生成结果" 库（`source=ai-generated`，`sourceTool` 标记为 video/image/canvas/agent）
- **库级联删除**：`MediaLibraryServiceImpl.deleteLibrary` 先调 `MediaService.deleteAssetsByLibrary` 把库内素材和 MinIO 对象清干净，再软删库

---

## 7. Billing（Phase 8）

> **计划中**（Phase 8）：完整扣费 / 流水 / 套餐。Phase 8 之前不实现。

| 方法 | 路径 | 请求 | 响应 | 状态 |
|------|------|------|------|------|
| GET | `/api/billing/logs` | `?page=1&pageSize=20&type?` | `{code, message, data: BillingLogResponse[]}` | 📋 Phase 8 |
| GET | `/api/billing/plans` | — | `{code, message, data: Plan[]}` | 📋 Phase 8 |

---

## 8. Customer 客户分组（Phase 9）— 管理员后台

> **已实现**（V5 migration + AdminController 一起落地）。本节描述的是**管理员视角**的分组管理 API；
> 普通用户自己的"我的分组"接口见 §2 User（`GET /api/users/me/groups`）。

### 8.1 通用规则

- 所有 `/api/admin/**` 端点需 `ROLE_ADMIN` —— 无 ADMIN 角色返 403
- 角色变更（USER ↔ ADMIN）后，**被改用户必须重新登录**（或调 `/api/auth/refresh`）才能获得新 role，
  旧的 access token 在 2h 内仍带旧 role —— 文档约束下游前端须在 UI 提示用户「角色已变更，请重新登录」
- 账号启停（disabled）变更**即时**生效，下一次 login/refresh 校验数据库
- 所有写操作记录到 `admin_audit_logs` 表
- Default 分组的不可变性：`isDefault=true` 的分组不可删除、不可关闭 `isDefault` 标志
- 创建/将一个分组设为 `isDefault=true` 时，自动把其他分组的 `isDefault` 重置为 false

> 📌 **前端对接速查（必读）**
>
> | 易错点 | 正确 | 错误 |
> |--------|------|------|
> | 搜索用户字段 | `?displayName=xxx` | ❌ `?keyword=` `?name=` `?email=` |
> | 分页大小 | `?pageSize=20` | ❌ `?size=20` |
> | 页码 | `?page=1` | - |
> | 改角色接口 | `PATCH /users/{id}/role` | ❌ `PUT` |
> | 启停账号接口 | `PATCH /users/{id}/disabled` | ❌ `PUT` |
> | 修改分组 | `PATCH /groups/{id}` | ❌ `PUT` |
> | 角色变更生效方式 | 用户**重新登录**或调用 `/api/auth/refresh` | - |

### 8.2 用户管理（Admin → User）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users` | 搜索用户（分页） |
| GET | `/api/admin/users/{id}` | 获取单个用户完整信息 |
| PATCH | `/api/admin/users/{id}/role` | 修改角色 USER↔ADMIN（**严禁改自己**） |
| PATCH | `/api/admin/users/{id}/disabled` | 启停账号（**严禁禁自己**） |

#### 8.2.1 `GET /api/admin/users`

搜索参数（全部可选）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `displayName` | string | 否 | - | `display_name` **模糊匹配**（SQL `LIKE '%xxx%'`），不是 email |
| `role` | string | 否 | - | `USER` / `ADMIN` 精确匹配 |
| `disabled` | boolean | 否 | - | `true` / `false` |
| `page` | int | 否 | 1 | 页码，从 1 开始 |
| `pageSize` | int | 否 | 20 | 每页条数，上限 100 |

> ⚠️ **易踩坑点（前端必读）**
>
> 1. 搜索字段是 `displayName`，**不是** `keyword`、不是 `name`、不是 `email`。
>    错误示例：`?keyword=张三` 会被 Spring 直接忽略，等同于「不传 displayName」查全部。
> 2. 分页参数是 `pageSize`，**不是** `size`。
>    错误示例：`?size=5` 会被 Spring 直接忽略，按默认 20 条返回。
>    如果你看到「明明传了 size=5 却返回 20 条」，就是这个原因。
> 3. **任何拼写错误的参数都会被静默忽略**，Spring 不会返回 400，所以查不出来时请先检查参数名拼写。
> 4. 不传任何参数 = 查全部用户，按 id 倒序（最新注册在前）。

**正确用法示例**：

```http
GET /api/admin/users?displayName=张三&page=1&pageSize=20
```

```http
GET /api/admin/users?role=ADMIN&disabled=false&page=1&pageSize=50
```

**错误用法（静默失败，不要这样写）**：

```http
# ❌ keyword 不会被识别
GET /api/admin/users?keyword=张三

# ❌ size 不会被识别，按 20 条返回
GET /api/admin/users?displayName=张三&size=5

# ❌ 搜 email 后端不会返回结果（displayName 只匹配 display_name 字段）
GET /api/admin/users?displayName=user@example.com
```

返回 `data`：`{items: AdminUserListItem[], total: int, page: int, pageSize: int}`

`AdminUserListItem`:

```json
{
  "id": 1,
  "email": "user@example.com",
  "displayName": "昵称",
  "role": "USER",
  "disabled": 0,
  "credits": 0,
  "monthlyQuota": 50,
  "quotaUsed": 0,
  "plan": "FREE",
  "createdAt": "2026-08-05T10:00:00",
  "groupIds": [1],
  "groupNames": ["Default"]
}
```

#### 8.2.2 `PATCH /api/admin/users/{id}/role`

请求体：
```json
{ "role": "ADMIN" }
```

返回：`data` 为新角色字符串（`"USER"` 或 `"ADMIN"`）。

错误码：
- `6002 ADMIN_CANNOT_CHANGE_OWN_ROLE` — 严禁改自己
- `6009 INVALID_ROLE_VALUE` — role 不是 USER / ADMIN
- `2001 USER_NOT_FOUND` — 用户 id 不存在

**注意**：被改用户的现有 token 在 2h 内仍带旧 role。前端需提示用户重新登录。

#### 8.2.3 `PATCH /api/admin/users/{id}/disabled`

请求体：
```json
{ "disabled": true }
```

返回：`data` 为新 disabled 值（0 或 1）。

错误码：
- `6003 ADMIN_CANNOT_DISABLE_SELF`
- `2001 USER_NOT_FOUND`
- `9001 INVALID_PARAM` — disabled 字段为空

**注意**：禁用的用户**仍可 refresh token 直到 2h 后过期**，但 login 路径返回 2002 USER_DISABLED。
如果要立即生效（不只是 login 路径），需要让后端每次请求都查库重 role — 留 Phase 8 升级。

### 8.3 客户分组管理（Admin → Group）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/groups` | 列出全部分组（分页，含成员数） |
| GET | `/api/admin/groups/{id}` | 分组详情 |
| POST | `/api/admin/groups` | 创建分组 |
| PATCH | `/api/admin/groups/{id}` | 修改分组（PATCH） |
| DELETE | `/api/admin/groups/{id}` | 删除分组（软删，Default 不可删） |
| GET | `/api/admin/groups/{id}/members` | 列出分组成员（分页） |
| POST | `/api/admin/groups/{id}/members` | 加成员（`{userId}`） |
| DELETE | `/api/admin/groups/{id}/members/{userId}` | 移除成员 |

`AdminGroupResponse`:
```json
{
  "id": 1,
  "name": "Default",
  "description": "默认分组，所有新用户自动加入",
  "color": "#909399",
  "isDefault": true,
  "memberCount": 5,
  "createdAt": "...",
  "updatedAt": "..."
}
```

错误码：
- `6004 GROUP_NAME_DUPLICATE` — name 已存在
- `6005 GROUP_IS_DEFAULT_CANNOT_DELETE`
- `6006 GROUP_IS_DEFAULT_CANNOT_UNSET`
- `6007 USER_ALREADY_IN_GROUP`
- `6008 USER_NOT_IN_GROUP`
- `9404 NOT_FOUND` — 分组或用户不存在

---

## 9. Customer 客户分组（已合并到 §8.3）

> 旧版 §8 的 `/api/customer/**` 管理员 API 已**整合到 §8.3 的 `/api/admin/groups`**，保持向后兼容只到 v1；下版本移除 `/api/customer/groups/*`。
> 普通用户的 `GET /api/users/me/groups` 仍保留（见 §2）。

---

## 10. Workflow 模板（**未来**做）

> **计划中**：根目录创建 `workflows/` 子目录 + 3 个开箱即用 workflow JSON 模板（产品图 / 动漫风 / 图生视频）。由 C 在 Phase 5 阶段做。

**模板示例**（`workflows/01-product-photo.json`）：

```json
{
  "3": {
    "class_type": "KSampler",
    "inputs": { "seed": 42, "steps": 20, "cfg": 7, "sampler_name": "euler", "scheduler": "normal", "denoise": 1.0, "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["8", 0] }
  },
  "6": { "class_type": "CLIPTextEncode", "inputs": { "text": "a product photo on white background", "clip": ["4", 1] } }
}
```

---

## 11. 错误码分段

| 段 | 模块 | 例 |
|---|------|-----|
| 0 | 通用 | `0=success` |
| **9xxx** | **Common** | `9999=INTERNAL_ERROR / 9001=INVALID_PARAM / 9401=UNAUTHORIZED / 9403=FORBIDDEN / 9404=NOT_FOUND` |
| 1xxx | Auth | `1001=EMAIL_ALREADY_EXISTS / 1002=INVALID_CREDENTIALS / 1101=TOKEN_EXPIRED / 1102=INVALID_TOKEN` |
| 2xxx | User | `2001=USER_NOT_FOUND / 2002=USER_DISABLED` |
| 3xxx | Generation | `3001=WORKFLOW_INVALID / 3002=COMFYUI_UNREACHABLE / 3003=COMFYUI_REJECTED / 3004=QUOTA_INSUFFICIENT` |
| 4xxx | Workflow | `4001=WORKFLOW_NOT_FOUND / 4002=WORKFLOW_ACCESS_DENIED` |
| 5xxx | Billing | `5001=BILLING_NOT_ENABLED`（Phase 8）|
| 6xxx | Admin | `6001=ADMIN_OPERATION_DENIED / 6002=ADMIN_CANNOT_CHANGE_OWN_ROLE / 6003=ADMIN_CANNOT_DISABLE_SELF / 6004=GROUP_NAME_DUPLICATE / 6005=GROUP_IS_DEFAULT_CANNOT_DELETE / 6006=GROUP_IS_DEFAULT_CANNOT_UNSET / 6007=USER_ALREADY_IN_GROUP / 6008=USER_NOT_IN_GROUP / 6009=INVALID_ROLE_VALUE` |
| 7xxx | Media | `7001=MEDIA_LIBRARY_NAME_DUPLICATE / 7002=MEDIA_LIBRARY_NOT_FOUND / 7003=MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY / 7010=MEDIA_ASSET_NOT_FOUND / 7011=MEDIA_ASSET_TYPE_INVALID / 7020=MEDIA_UPLOAD_FAILED / 7021=MEDIA_FILE_TOO_LARGE / 7022=MEDIA_FILE_EMPTY` |

返回格式：
```json
{ "code": 2001, "message": "用户不存在", "data": null }
```

---

## 12. curl 请求示例

```bash
# ===== 1. 注册 =====
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Passw0rd!","displayName":"Test User"}'

# 返: { "code": 0, "message": "success", "data": { "accessToken": "...", "refreshToken": "...", "user": {...} } }

# ===== 2. 登录 =====
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"Passw0rd!"}'

# 取出 accessToken（jq 或 python）
TOKEN=$(curl -s ... | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")

# ===== 3. 查自己 =====
curl http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN"

# ===== 4. 配额查询 =====
curl http://localhost:8080/api/users/me/quota \
  -H "Authorization: Bearer $TOKEN"

# ===== 5. 创建 workflow =====
curl -X POST http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "test workflow",
    "graphJson": "{\"3\":{\"class_type\":\"KSampler\",\"inputs\":{...}}}"
  }'

# ===== 6. 提交生成 =====
curl -X POST http://localhost:8080/api/generate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"workflowId": 1}'

# 返: { "code": 0, "message": "success", "data": { "jobId": 1, "status": "PENDING" } }

# ===== 7. 轮询查任务 =====
curl http://localhost:8080/api/jobs/1 \
  -H "Authorization: Bearer $TOKEN"

# 返: { ..., "data": { "id": 1, "status": "RUNNING", ... } }
# 几分钟后 status → "COMPLETED"，resultUrls 字段会有值

# ===== 8. 健康检查 =====
curl http://localhost:8080/api/health

# 返: { "status": "ok", "service": "aicenter-backend", "version": "0.1.0" }

# ===== 9. 修改个人信息 (PATCH) =====
curl -X PATCH http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"新昵称","password":"NewPass123"}'

# ===== 10. 查询当前用户配额 =====
curl http://localhost:8080/api/users/me/quota \
  -H "Authorization: Bearer $TOKEN"

# ===== 11. 查询当前用户所属分组 =====
curl http://localhost:8080/api/users/me/groups \
  -H "Authorization: Bearer $TOKEN"

# ===== 12. 分页查询我的 workflow（C11） =====
curl "http://localhost:8080/api/workflows?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# 返: { "code": 0, "data": [ {"id": 1, "name": "...", "graphJson": "...", ...}, ... ] }

# ===== 13. 查询官方模板列表 =====
curl http://localhost:8080/api/workflows/templates \
  -H "Authorization: Bearer $TOKEN"

# 返: { "code": 0, "data": [ {"id": 1, "name": "产品图生成", ...}, {"id": 2, "name": "动漫风格图", ...}, {"id": 3, "name": "图生视频", ...} ] }

# ===== 14. 用模板提交生成 =====
curl -X POST http://localhost:8080/api/generate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"workflowId": 1, "inputs": {"prompt": "red sneakers on white background"}}'

# 返: { "code": 0, "data": { "jobId": 3, "status": "RUNNING", "comfyuiPromptId": "abc-123-..." } }

# ===== 15. 获取任务产物（C8） =====
curl -L http://localhost:8080/api/jobs/3/result/jurong_product_00001_.png \
  -H "Authorization: Bearer $TOKEN"

# 302 重定向到 MinIO 签名 URL，浏览器/curl -L 自动跟随下载文件
# 任务未完成时返: { "code": 3005, "message": "任务尚未完成" }

# ===== 16. 删除任务（C9） =====
# RUNNING 状态会先调 ComfyUI /interrupt 取消
curl -X DELETE http://localhost:8080/api/jobs/3 \
  -H "Authorization: Bearer $TOKEN"

# 返: { "code": 0, "data": { "jobId": 3, "status": "CANCELLED" } }
# 已完成的任务删除后返 status: "DELETED"（数据保留可回滚）

# ===== 17. 上传图片到 ComfyUI（图生图前置） =====
curl -X POST http://localhost:8080/api/comfyui/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "image=@/path/to/my_photo.jpg"

# 返: { "code": 0, "data": { "filename": "my_photo_00001_.png", "originalName": "my_photo.jpg" } }
# 拿 filename 后存到 workflow 的 LoadImage 节点 image 字段

# ===== 18. 图生图完整流程 =====
# 18a. 上传图片
curl -X POST http://localhost:8080/api/comfyui/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "image=@/path/to/photo.jpg"
# → { "data": { "filename": "photo_00001_.png" } }

# 18b. 创建图生图 workflow
curl -X POST http://localhost:8080/api/workflows \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "I2I workflow",
    "graphJson": "{\"1\":{\"class_type\":\"LoadImage\",\"inputs\":{\"image\":\"photo_00001_.png\"}},\"2\":{\"class_type\":\"JurongImageToImage\",\"inputs\":{\"image\":[\"1\",0],\"prompt\":\"{{prompt}}\",\"size\":\"1024x1024\"}},\"3\":{\"class_type\":\"SaveImage\",\"inputs\":{\"images\":[\"2\",0],\"filename_prefix\":\"jurong_i2i\"}}}"
  }'
# → { "data": { "id": 5, ... } }

# 18c. 提交生成
curl -X POST http://localhost:8080/api/generate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"workflowId": 5, "inputs": {"prompt": "transform to watercolor painting"}}'

# ===== 19. 管理员搜索用户（按 displayName 模糊） =====
# 注：必须用 ROLE_ADMIN 的 token（用 ADMIN 账户登录拿）
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@jurong.local","password":"admin123"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")

curl "http://localhost:8080/api/admin/users?displayName=%E4%BA%A7%E5%93%81&page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# → {"code":0,"data":{"items":[{...AdminUserListItem...}],"total":1,"page":1,"pageSize":20}}

# ===== 20. 管理员修改用户角色（USER → ADMIN） =====
curl -X PATCH http://localhost:8080/api/admin/users/2/role \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"role":"ADMIN"}'
# → {"code":0,"data":"ADMIN"}
# 期望前端提示："角色已变更，请重新登录后生效"

# 改自己返 6002（用 ADMIN 自己的 userId 替换 {ADMIN_ID}）：
ADMIN_ID=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@jurong.local","password":"admin123"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['userId'])")
curl -X PATCH "http://localhost:8080/api/admin/users/$ADMIN_ID/role" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"role":"USER"}'
# → {"code":6002,"message":"不能修改自己的角色","data":null}

# ===== 21. 管理员启停账号 =====
curl -X PATCH http://localhost:8080/api/admin/users/2/disabled \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"disabled":true}'
# → {"code":0,"data":1}
# 禁自己返 6003：
# curl -X PATCH .../users/<自己id>/disabled -d '{"disabled":true}'
# → {"code":6003,"message":"不能禁用自己的账号","data":null}

# ===== 22. 管理员创建分组 =====
curl -X POST http://localhost:8080/api/admin/groups \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"VIP","description":"付费用户","color":"#F56C6C","isDefault":false}'
# → {"code":0,"data":{"id":2,"name":"VIP",...}}

# 创建 name 重名返 6004：
# curl ... -d '{"name":"Default"}'
# → {"code":6004,"message":"分组名称已存在：Default","data":null}

# ===== 23. 管理员往分组加成员 =====
curl -X POST http://localhost:8080/api/admin/groups/2/members \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"userId":3}'
# → {"code":0,"message":"success","data":null}
# 用户已在返 6007

# ===== 24. 删除 Default 分组（应被拒） =====
curl -X DELETE http://localhost:8080/api/admin/groups/1 \
  -H "Authorization: Bearer $ADMIN_TOKEN"
# → {"code":6005,"message":"默认分组不可删除...","data":null}

# ===== 25. 普通 USER token 访问 admin 端点（返 403） =====
curl "http://localhost:8080/api/admin/users" \
  -H "Authorization: Bearer $TOKEN"
# HTTP 403（Spring Security 在 filter 链直接拒绝；不走 BusinessException）

# ===== 26. 列出我的资产库（注册时自动建 2 个系统库） =====
curl http://localhost:8080/api/media/libraries \
  -H "Authorization: Bearer $TOKEN"
# 返: { "code": 0, "data": [
#   { "id": 1, "name": "我的资产", "type": "system-uploaded", "assetCount": 0, ... },
#   { "id": 2, "name": "AI 生成结果", "type": "system-ai", "assetCount": 0, ... }
# ] }

# ===== 27. 上传素材到「我的资产」(未传 libraryId 默认进 system-uploaded) =====
curl -X POST http://localhost:8080/api/media/assets \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/photo.png"
# 返: { "code": 0, "data": { "id": 100, "url": "https://minio/...", "name": "photo.png", "type": "image", "size": 102400 } }
# 超 20M 返 7021，空文件返 7022，类型不支持返 7011

# ===== 28. 上传素材到指定 custom 库 =====
curl -X POST http://localhost:8080/api/media/assets \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/clip.mp4" \
  -F "libraryId=3"
# video ≤ 200M, audio ≤ 50M

# ===== 29. 分页查询素材 =====
curl "http://localhost:8080/api/media/assets?libraryId=1&type=image&page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
# 返: { "code": 0, "data": { "items": [...], "total": 12, "page": 1, "pageSize": 20 } }

# ===== 30. 新建 custom 库 =====
curl -X POST http://localhost:8080/api/media/libraries \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"工作素材","iconKey":"folder","description":"可选"}'
# 重名返 7001

# ===== 31. 重命名库（系统库会返 7003） =====
curl -X PATCH http://localhost:8080/api/media/libraries/3 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"工作素材 2","iconKey":"folder"}'

# ===== 32. 删除 custom 库（级联删素材 + MinIO 对象） =====
curl -X DELETE http://localhost:8080/api/media/libraries/3 \
  -H "Authorization: Bearer $TOKEN"
# 系统库返 7003

# ===== 33. 批量删除素材 =====
curl -X POST http://localhost:8080/api/media/assets/batch-delete \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ids":[100,101,102]}'
# 返: { "code": 0, "data": { "deleted": 3, "requested": 3 } }
```

---

## 13. 维护规则

**加 / 改端点必须**：
1. **先改本文件**（API.md 真理源）—— 改完才能动代码
2. **加 curl 示例**（§12 模板）
3. **测试通过**（单元 + 集成 + 手测）
4. **PR 描述里贴 curl 输出**

**改端点时**：
1. 旧版本先在 PR 描述列（破坏性变更要 BUMP version）
2. 至少 1 人 review
3. 同步更新 README.md 如果涉及架构层面

---

**最后更新**：2026-08-07 by developerC（新增 §6.5 资产库 / 素材 + 7xxx 错误码 + curl §26-33）
