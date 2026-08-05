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
- **错误码**分段：见 §10

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
  "user": { "id": 1, "email": "user@example.com", "displayName": "...", "role": "USER" }
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

## 7. Billing（Phase 8）

> **计划中**（Phase 8）：完整扣费 / 流水 / 套餐。Phase 8 之前不实现。

| 方法 | 路径 | 请求 | 响应 | 状态 |
|------|------|------|------|------|
| GET | `/api/billing/logs` | `?page=1&pageSize=20&type?` | `{code, message, data: BillingLogResponse[]}` | 📋 Phase 8 |
| GET | `/api/billing/plans` | — | `{code, message, data: Plan[]}` | 📋 Phase 8 |

---

## 8. Customer 客户分组（Phase 9）

> **计划中**（Phase 9）。实体在 `customer/entity/` 已建好（`UserGroup` / `UserGroupMember`），**API 暂不实现**。

| 方法 | 路径 | 请求 | 响应 | 状态 |
|------|------|------|------|------|
| GET | `/api/customer/groups` | — | `{code, message, data: Group[]}` | 📋 Phase 9 |
| POST | `/api/customer/groups` | `{name, description?, color?}` | `{code, message, data: Group}` | 📋 Phase 9 |
| PATCH | `/api/customer/groups/{id}` | `{name?, description?, color?}` | `{code, message, data: Group}` | 📋 Phase 9 |
| DELETE | `/api/customer/groups/{id}` | — | `{code, message}` | 📋 Phase 9 |
| GET | `/api/customer/groups/{id}/members` | — | `{code, message, data: UserResponse[]}` | 📋 Phase 9 |
| POST | `/api/customer/groups/{id}/members` | `{userId}` | `{code, message}` | 📋 Phase 9 |
| DELETE | `/api/customer/groups/{id}/members/{userId}` | — | `{code, message}` | 📋 Phase 9 |
| GET | `/api/users/me/groups` | — | `{code, message, data: GroupResponse[]}` | ✅ 已实现（B10）|

---

## 9. Workflow 模板（**未来**做）

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

## 10. 错误码分段

| 段 | 模块 | 例 |
|---|------|-----|
| 0 | 通用 | `0=success` |
| **9xxx** | **Common** | `9999=INTERNAL_ERROR / 9001=INVALID_PARAM / 9401=UNAUTHORIZED / 9403=FORBIDDEN / 9404=NOT_FOUND` |
| 1xxx | Auth | `1001=EMAIL_ALREADY_EXISTS / 1002=INVALID_CREDENTIALS / 1101=TOKEN_EXPIRED / 1102=INVALID_TOKEN` |
| 2xxx | User | `2001=USER_NOT_FOUND / 2002=USER_DISABLED` |
| 3xxx | Generation | `3001=WORKFLOW_INVALID / 3002=COMFYUI_UNREACHABLE / 3003=COMFYUI_REJECTED / 3004=QUOTA_INSUFFICIENT` |
| 4xxx | Workflow | `4001=WORKFLOW_NOT_FOUND / 4002=WORKFLOW_ACCESS_DENIED` |
| 5xxx | Billing | `5001=BILLING_NOT_ENABLED`（Phase 8）|

返回格式：
```json
{ "code": 2001, "message": "用户不存在", "data": null }
```

---

## 11. curl 请求示例

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
```

---

## 12. 维护规则

**加 / 改端点必须**：
1. **先改本文件**（API.md 真理源）—— 改完才能动代码
2. **加 curl 示例**（§11 模板）
3. **测试通过**（单元 + 集成 + 手测）
4. **PR 描述里贴 curl 输出**

**改端点时**：
1. 旧版本先在 PR 描述列（破坏性变更要 BUMP version）
2. 至少 1 人 review
3. 同步更新 README.md 如果涉及架构层面

---

**最后更新**：2026-08-02 by  developerC
