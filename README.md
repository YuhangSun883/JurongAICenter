# Jurong AICenter Backend — 项目总结

> AI 生图/生视频平台 · 内部工具 · v1

***

## 0. 项目信息

| 项         | 内容                                                |
| --------- | ------------------------------------------------- |
| **项目名**   | Jurong AICenter Backend（暂定）                       |
| **定位**    | 内部 AI 生图/生视频工具（Position A：内部用，但留出对外扩展能力）          |
| **目标用户**  | 团队内部 5-50 人                                       |
| **项目根目录** | `D:\Aworkstation\Work\JurongAICenterbackend`（本仓库） |
| **起始日期**  | 2026-08-01                                        |

***

## 1. 一句话定位

> 用户在浏览器画布上拖节点组合 AI 流水线（文生图 / 图生图 / 文生视频 / 图生视频 / 多图生视频），后端 Spring Boot 调度云端 ComfyUI 执行，所有模型调用走自家 NewAPI 中转站。自带用户管理、配额计费、任务历史。

***

## 2. 技术栈

| 层           | 选型                                                                  | 部署位置                         |
| ----------- | ------------------------------------------------------------------- | ---------------------------- |
| **后端框架**    | Spring Boot 3.3 / Java 21                                           | 本地开发 → 云生产                   |
| **ORM**     | MyBatis Plus 3.5                                                    | —                            |
| **数据库**     | MySQL 8.2 (JRAIC-mysql 容器)                                          | 云 (docker, port 33062)       |
| **缓存**      | Redis 7 (JRAIC-redis 容器)                                            | 云 (docker, port 63791)       |
| **对象存储**    | MinIO (JRAIC-minio 容器)                                              | 云 (docker, port 19000/19001) |
| **AI 引擎**   | ComfyUI `jurong-comfyui:local`（PyTorch CPU-only，**无 CUDA**，无本地模型权重） | **云**（docker, port 8188）     |
| **AI 节点包**  | jurong-api-nodes（自研 5 个 HTTP 节点）                                    | 云（跟随 ComfyUI mount）          |
| **上游 API**  | NewAPI 中转站（已有，钙离子/new-api 容器 port 3000）                             | 云 (docker)                   |
| **前端**      | Vue 3 + Vite + Element Plus + LiteGraph.js（嵌入画布）                    | 独立项目                         |
| **Web 服务器** | Nginx（反代 + SSL）                                                     | 云 (待 Phase 7 部署)             |

***

## 3. 架构

> **Spring Boot 是中间编排层**，不直接做 AI 推理（ComfyUI）、不直接调大模型（NewAPI 中转）、不直接存文件（MinIO）。它负责**业务逻辑**（用户 / 任务 / 工作流 / 配额）+ **服务编排**（调外部 HTTP + 库 + 对象存储）。

### 3.1 整体架构

```
┌───────────────────────────────────────────────────────────────────────┐
│ ① 浏览器 (Vue 3 + LiteGraph.js + Element Plus) — 独立项目             │
│    登录 / 画布 / 历史 / 配额                                              │
└──────────────────────────┬────────────────────────────────────────────┘
                           │ HTTPS + JWT (Authorization: Bearer ...)
┌──────────────────────────▼────────────────────────────────────────────┐
│ ② Spring Boot (本仓库)                                                  │
│    Auth / User / Generation / Workflow / Billing / Storage                │
│    ─业务逻辑 + 编排，不做 AI 推理 / 调模型 / 存文件─┘                  │
└──┬──────────┬──────────┬──────────┬──────────────────────────────────────┘
   │          │          │          │
   │ HTTP    │ HTTP     │ JDBC     │ Lettuce    │ S3 API
   │          │          │          │             │
┌──▼─────┐  ┌▼──────┐  ┌▼────────┐ ┌▼──────────┐ ┌▼──────────┐
│ ③ ComfyUI │  │ ④ NewAPI│  │ ⑤ JRAIC-  │  │ ⑥ JRAIC- │  │ ⑦ JRAIC- │
│ (8188)  │  │  (3000)│  │  mysql    │  │  redis    │  │  minio    │
│         │  │         │  │ (33062)  │  │ (63791)  │  │ (19000)   │
│ 5 节点  │  │ 中转站   │  │ MySQL 8  │  │ Redis 7  │  │ MinIO     │
└──┬─────┘  └────┬────┘  └──────────┘  └──────────┘  └──────────┘
   │             │
   │ HTTPS       │ HTTPS
┌──▼─────────┐  ┌▼──────────┐
│ ⑧ 节点包     │  │ ⑨ aicoming │
│ jurong-    │  │   .top     │
│ api-nodes  │  │ (上游模型)  │
│ (5 节点)  │  │            │
│ (本仓库 Python 子项目)  │
└─────────────┘  └────────────┘
```

### 3.2 服务依赖矩阵（**哪里调哪里**）

> **这一节是协作者的"地图"**。**谁负责哪部分、谁调谁、代码在哪**。

| # | 调用方 | 被调方 | 协议 | 代码位置（在哪调）| 用途 |
|---|--------|--------|------|------------------|------|
| 1 | 浏览器 | Spring Boot | HTTPS + JWT | `springboot/.../controller/*.java` | 用户操作（注册/登录/生成/查任务） |
| 2 | Spring Boot | ComfyUI | HTTP | `springboot/.../client/ComfyUIClient.java` | 提交/轮询生成任务 |
| 4 | Spring Boot | JRAIC-mysql | JDBC + MyBatis Plus | `application*.yml` `spring.datasource` | 业务数据（users / workflows / jobs） |
| 5 | Spring Boot | JRAIC-redis | Lettuce | `application*.yml` `spring.data.redis` | 业务缓存（session / quota / 临时任务状态） |
| 6 | Spring Boot | JRAIC-minio | S3 API | `springboot/.../service/StorageService.java` | 产物上传 / 下载 / 签名 URL |
| 7 | **ComfyUI 容器内节点包** | NewAPI | HTTPS | `jurong-api-nodes/api_client.py` | 调大模型（5 个节点统一封装） |
| 8 | ComfyUI 容器内节点包 | 节点包内部 | Python | `jurong-api-nodes/__init__.py` | 自定义节点逻辑（5 个 Jurong 节点） |
| 9 | NewAPI | aicoming.top | HTTPS | (中转站配置，非本项目) | 上游大模型 API |
| 10 | (独立 Vue 前端) | Spring Boot | HTTPS | 同 #1 | 独立前端项目（不在本仓库） |

**关键**：
- **Spring Boot 调 5 个外部服务**（ComfyUI / NewAPI / MySQL / Redis / MinIO + 浏览器）
- **节点包不归 Spring Boot 维护**——它在 ComfyUI 容器内**自己**调 NewAPI
- **NewAPI / aicoming 是上游链**——Spring Boot 不直接调 aicoming

### 3.3 典型请求时序（用户生成一张图的生命周期）

```
[1]  浏览器 → POST /api/generate (Bearer JWT + {workflow_id})
     ↓
[2]  Spring Boot: JwtAuthenticationFilter 验证 JWT
[3]  Spring Boot: GenerationController.generate()
[4]  Spring Boot: WorkflowService.load(workflow_id) → JRAIC-mysql (workflows 表)
[5]  Spring Boot: jobs INSERT (status=PENDING)
[6]  Spring Boot → ComfyUI: POST /prompt (workflow JSON)  ──HTTP─→
[7]  ComfyUI: 返 prompt_id
[8]  Spring Boot: jobs.prompt_id = prompt_id, status=RUNNING
[9]  Spring Boot: 启动后台 polling（@Async 线程）
     ↓
[10] Spring Boot 后台线程 → ComfyUI: GET /history/{prompt_id}（每 5s）──HTTP─→
[11] ComfyUI: 返 status + outputs（若 RUNNING 则继续等）
     ...轮询中...
[12] ComfyUI 触发节点包 (jurong-api-nodes) 内部执行
[13] 节点包 → NewAPI: POST /v1/videos  (multipart)        ──HTTPS─→
[14] NewAPI → aicoming.top                              ──HTTPS─→
[15] NewAPI: 返 task_id
[16] 节点包: 轮询 NewAPI /v1/videos/{task_id}              ──HTTPS─→
[17] NewAPI → aicoming.top: 轮询
[18] NewAPI 返视频 URL
[19] 节点包: 返 (tensor, video_path) 给 ComfyUI
[20] ComfyUI 写产物到 /app/output/
[21] Spring Boot polling: 状态变 COMPLETED
[22] Spring Boot: 下载视频 → StorageService.putObject       ──S3─→ JRAIC-minio
[23] Spring Boot: jobs UPDATE status=COMPLETED, result_urls=[...]
     ↓
[24] 浏览器 → GET /api/jobs/{id} 轮询
[25] Spring Boot: 返 jobs row + JRAIC-minio presigned URL
[26] 浏览器 → JRAIC-minio: GET 签名 URL（不经过 Spring Boot）──S3─→
[27] 浏览器展示图/视频
```

**关键路径注解**：
- **[4] 从 JRAIC-mysql 读 workflow JSON**
- **[6] Spring Boot 调 ComfyUI 提交任务**
- **[12-19] 节点包在 ComfyUI 容器内执行**——**不归 Spring Boot 维护**，**Spring Boot 只轮询结果**
- **[22] 产物存 JRAIC-minio**（业务对象存储，bucket `ai-platform`）
- **[26] 浏览器直连 JRAIC-minio**（presigned URL，**不中转流量**经 Spring Boot）

### 3.4 Spring Boot 内部模块依赖

```
Controller          ← 接收 HTTP 请求
   ↓
Service (interface) ← 业务逻辑入口
   ↓ 实现
ServiceImpl         ← 具体业务
   ↓
Client / Repository ← 外部集成
   ├─ ComfyUIClient → ComfyUI (HTTP)
   ├─ StorageService → JRAIC-minio (S3)
   └─ BaseMapper<T>  → JRAIC-mysql (JDBC)

异常：Service 抛 BusinessException(ErrorCode, message)
    ↓
GlobalExceptionHandler 统一处理
    ↓
返回 {code: int, message: string, data: null}
```

**代码位置映射**（在 `springboot/src/main/java/com/jurong/aicenter/`）：

| 层 | 包 | 文件 |
|----|----|------|
| Controller | `controller/` | `AuthController.java`, `GenerationController.java`, `WorkflowController.java`, `UserController.java`, `HealthController.java` |
| Service | `service/` | 接口（`AuthService.java` 等）+ `service/impl/` 实现（`AuthServiceImpl.java` 等）|
| Client | `client/` | `ComfyUIClient.java`（**仅此一个**） |
| Storage | `service/StorageService.java` + `config/MinioConfig.java` |
| Repository | `repository/` | MyBatis Plus `BaseMapper` 接口（`UserRepository.java` 等） |
| Entity | `entity/` | 用户/任务/工作流等 JPA 风格 POJO（`@TableName` 注解）|
| DTO | `dto/{auth,user,generation,job,workflow}/` | 请求/响应 DTO |
| Customer (V2) | `customer/{entity,repository}/` | 客户分组模块子包 |
| Security | `security/` | `JwtTokenProvider.java`, `JwtAuthenticationFilter.java` |
| Exception | `exception/` | `BusinessException.java`, `ErrorCode.java`, `GlobalExceptionHandler.java` |
| Config | `config/` | `SecurityConfig.java`, `WebClientConfig.java`, `MinioConfig.java` |

### 3.5 数据库 5+2 张表

| 表 | 阶段 | 字段摘要 |
|----|------|---------|
| `users` | V1 | id, email, password_hash, role, credits, monthly_quota, quota_used, plan |
| `workflows` | V1 | id, user_id, name, graph_json(JSON), is_template, is_public, thumbnail_url |
| `jobs` | V1 | id, user_id, workflow_id, status, prompt_id, inputs_snapshot(JSON), result_urls(JSON), credits_cost, duration_ms |
| `billing_logs` | V1（Phase 8 用） | id, user_id, job_id, type, credits_delta, balance_after |
| `user_groups` | V2 | id, name(unique), color, is_default |
| `user_group_members` | V2 | id, user_id, group_id, UNIQUE(user_id, group_id) |
| `templates_v` (view) | V1 | `workflows WHERE is_template OR is_public` |

ER：`users` 1→N `workflows` / `jobs` / `billing_logs`；`users` N↔M `user_groups` via `user_group_members`。

---

***

## 4. 目标文件夹结构

```
JurongAICenterbackend/
├── README.md                            ← 本文件（唯一文档 — ADR 024）
├── secrets.txt                          ← 链接信息 reference（不入 git，001）
├── docker-compose.yml                   ← 生产环境编排（待写）
├── nginx.conf                           ← Nginx 配置（待 Phase 7）
├── .gitignore                           ← 排除 secrets.txt + application*.yml
│
├── springboot/                          ← A 的代码（Spring Boot 后端）
│   ├── pom.xml
│   ├── Dockerfile
│   ├── .gitignore                       ← 排除 application-dev/prod/local.yml
│   ├── src/main/java/com/jurong/aicenter/
│   │   ├── AiCenterApplication.java
│   │   ├── config/                      ← Security, JWT, MyBatis, MinIO
│   │   ├── controller/                  ← AuthController, GenerationController, ...
│   │   ├── service/                     ← AuthService, GenerationService, ...
│   │   ├── repository/                  ← MyBatis Mapper 接口
│   │   ├── entity/                      ← 用户、任务、工作流、流水实体
│   │   ├── dto/                         ← 请求/响应 DTO
│   │   ├── client/                      ← ComfyUIClient（仅此）
│   │   ├── customer/                    ← 客户分组模块（entity + repository，子包）
│   │   │   ├── entity/                  ←   UserGroup, UserGroupMember
│   │   │   └── repository/              ←   UserGroupRepository, UserGroupMemberRepository
│   │   └── exception/                   ← 全局异常处理
│   ├── src/main/resources/
│   │   ├── application.yml              ← 公共部分（不敏感）
│   │   ├── application-dev.yml          ← 本地开发（明文，**不入 git**）
│   │   ├── application-prod.yml         ← 生产（明文，**不入 git**）
│   │   ├── mapper/                      ← MyBatis XML
│   │   └── db/migration/                ← Flyway 数据库迁移脚本
│   └── src/test/java/                   ← 单元测试
│
├── jurong-api-nodes/                    ← 自研 5 个 HTTP 节点（云端 mount 进 ComfyUI）
│   ├── __init__.py
│   ├── api_client.py
│   ├── text_to_image.py
│   ├── image_to_image.py
│   ├── text_to_video.py
│   ├── image_to_video.py
│   ├── multi_image_to_video.py
│   ├── pricing.py
│   └── README.md
│
├── workflows/                           ← C 的产出（开箱即用模板，未来）
│
├── artifacts/                           ← 凭据（不进 git）
│   └── .newapi_token.b64
│
└── (no docs/ dir — 决策已合并到 README.md 一份文档)
```

**部署到云端**（`/opt/aicenter/`）：

```
/opt/aicenter/
├── artifacts/                          ← .newapi_token.b64
├── comfyui-build/                      ← Docker 化根目录
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── ComfyUI/                        ← build context（45MB 缓存）
├── custom_nodes/
│   └── jurong-api-nodes/                ← 节点代码（mount volume 源）
├── output/                             ← 视频产物 mount 点
└── (no scripts/ — 主机 ComfyUI 已删)
```

***

## 5. Spring Boot 职责（详细）

### 5.1 后端做什么（**核心 8 模块 + 谁负责**）

| 模块 | 职责 | 谁负责 |
|------|------|------|
| **AuthController / AuthService** | 邮箱注册、密码登录（bcrypt）、JWT 签发/验证、refresh token | B |
| **UserController / UserService** | 当前用户信息查询、修改密码、配额查询 | B |
| **GenerationController / GenerationService** | **核心业务**：提交生成任务（workflow JSON）、轮询 ComfyUI 状态、下载产物、上传 MinIO、写入 jobs 表 | C |
| **WorkflowController / WorkflowService** | 用户保存/加载/删除 workflow JSON | C |
| **BillingController / BillingService** | 查询账单流水、套餐列表 | B（Phase 8）|
| **StorageService** | **核心集成**：MinIO 上传/下载/签名 URL 生成 | C |
| **ComfyUIClient** | **核心集成**：HTTP 客户端，调 ComfyUI `/prompt` 和 `/history/{id}` | A |


### 5.1 后端做什么

**核心模块**：

| 模块                                           | 职责                                                          |
| -------------------------------------------- | ----------------------------------------------------------- |
| **AuthController / AuthService**             | 邮箱注册、密码登录（bcrypt）、JWT 签发/验证、refresh token                   |
| **UserController / UserService**             | 当前用户信息查询、修改密码、配额查询                                          |
| **GenerationController / GenerationService** | 提交生成任务（workflow JSON）、轮询 ComfyUI 状态、下载产物、上传 MinIO、写入 jobs 表 |
| **WorkflowController / WorkflowService**     | 用户保存/加载/删除自己的 workflow JSON                                 |
| **BillingController / BillingService**       | 查询账单流水、套餐列表（Phase 8 加）                                      |
| **AdminController / AdminService**           | 管理员后台（Phase 2 加）                                            |
| **ComfyUIClient**                            | HTTP 客户端，调 ComfyUI `/prompt` 和 `/history/{id}`              |

| **QuotaService**                             | Redis 原子预扣、流水记账（**Phase 8 最后做**）                            |
| **StorageService**                           | MinIO 上传/下载，URL 生成                                          |

### 5.2 数据库设计（5 张表 + 2 张分组表）

```sql
-- 用户
users
  id, email, password_hash, display_name, role,
  credits INT DEFAULT 0,                  -- Phase 8 用
  monthly_quota INT DEFAULT 50,            -- Phase 8 用
  quota_used INT DEFAULT 0,
  quota_period_start DATE,
  plan VARCHAR(32) DEFAULT 'FREE',
  plan_expires_at, created_at, updated_at

-- 工作流（用户私有 + 模板）
workflows
  id, user_id, name, description,
  graph_json JSON,
  thumbnail_url, is_template, is_public,
  created_at, updated_at

-- 任务（每次"点生成"产生一个）
jobs
  id, user_id, workflow_id, template_id,
  comfyui_prompt_id, status (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED),
  inputs_snapshot JSON, graph_snapshot JSON,
  result_urls JSON, error_message,
  credits_cost INT, duration_ms INT,
  started_at, completed_at, created_at

-- 计费流水（Phase 8 加）
billing_logs
  id, user_id, job_id,
  type (CONSUME/RECHARGE/REFUND/GRANT/EXPIRE),
  credits_delta, balance_after,
  description, payment_id, created_at

-- 客户分组定义（V2）
user_groups
  id, name(唯一), description, color,    -- 标签色 hex
  is_default,                            -- 是否默认分组
  created_at, updated_at

-- 用户-分组多对多（V2）
user_group_members
  id, user_id, group_id,
  joined_at,                             -- 入组时间
  UNIQUE(user_id, group_id)              -- 防重复

-- 模板视图
templates_v (view)
  = workflows WHERE is_template OR is_public
```

**7 张表 ER 关系**：

- `users` 1 ↔ N `user_group_members` N ↔ 1 `user_groups`（多对多）
- `users` 1 ↔ N `workflows` / `jobs` / `billing_logs`（一对多）

### 5.3 API 端点（v1 暴露 12 个）

```
公开（无需 JWT）：
  POST   /api/auth/register
  POST   /api/auth/login
  POST   /api/auth/refresh

需 JWT：
  GET    /api/users/me
  PATCH  /api/users/me
  GET    /api/workflows
  POST   /api/workflows
  GET    /api/workflows/{id}
  PATCH  /api/workflows/{id}
  DELETE /api/workflows/{id}
  POST   /api/generate
  GET    /api/jobs
  GET    /api/jobs/{id}
  DELETE /api/jobs/{id}
  GET    /api/jobs/{id}/result/{filename}
  GET    /api/billing/logs                -- Phase 8
  GET    /api/billing/plans               -- Phase 8

Actuator：
  GET    /api/health
```

### 5.4 客户分组模块（Phase 9 准备）

> **当前状态**：实体类 + Mapper 已建好，**controller / service 暂未实现**，等 Phase 9 启动时再补。

**模块位置**：`com.jurong.aicenter.customer.*`（子包，避免污染主包）

**包结构**：

```
com.jurong.aicenter.customer/
├── entity/
│   ├── UserGroup.java         ← @TableName("user_groups")
│   └── UserGroupMember.java   ← @TableName("user_group_members")
└── repository/
    ├── UserGroupRepository.java         ← BaseMapper<UserGroup>
    └── UserGroupMemberRepository.java   ← BaseMapper<UserGroupMember>
```

**字段说明**：

| 字段                       | 说明                                 |
| ------------------------ | ---------------------------------- |
| `user_groups.name`       | 分组名称，全局唯一（uk\_name）                |
| `user_groups.color`      | 前端标签颜色，hex 格式（默认 `#909399`）        |
| `user_groups.is_default` | 是否默认分组，1 个；新用户注册时自动加入              |
| `user_group_members`     | `UNIQUE(user_id, group_id)` 防止重复加入 |

**待实现 API（Phase 9 规划）**：

```
需 JWT + ADMIN 角色：
  GET    /api/customer/groups              # 分组列表
  POST   /api/customer/groups              # 新建分组
  PATCH  /api/customer/groups/{id}         # 修改分组
  DELETE /api/customer/groups/{id}         # 删除分组（软删）
  GET    /api/customer/groups/{id}/members # 查看组成员
  POST   /api/customer/groups/{id}/members # 添加成员
  DELETE /api/customer/groups/{id}/members/{userId}  # 移除成员

任何登录用户：
  GET    /api/users/me/groups              # 我的分组
```

**初始化**：V2 迁移脚本会自动创建一个名为 `Default` 的默认分组，所有新用户可在 Phase 9 注册流程中加入。

###

***

## 6. 团队分工（3 名后端开发协作）

> **本项目所有 3 名开发者均为后端开发**（Spring Boot + 节点包 Python）。前端 Vue 项目是独立项目，不在本团队范围内。

### A（主开发 + 架构师，Advnce）

**职责**：

- 项目架构、决策、协调
- 数据库设计（5 张表 migration）
- Docker Compose / Nginx / 部署
- ComfyUIClient（HTTP 客户端）
- Spring Security / JWT / 全局异常 / 通用基础设施
- 集成测试 / 端到端验证
- 与前端团队对接 API
- 协调 B 和 C 的工作

**产出**：

- 项目根目录维护
- `springboot/` 通用模块（config / security / client / exception）
- `docker-compose.yml` / `docker-compose.dev.yml` / `nginx.conf`
- OpenAPI 文档
- 部署脚本

***

### B（后端开发 1）

**完整任务文档：[DEVELOPER_B.md](DEVELOPER_B.md)**（必读）

**职责摘要**：

- Auth 模块（注册/登录/JWT/refresh）— 完整
- User 模块（个人信息/修改）
- 配额查询端点 — **当前不关联"使用一次扣费多少"**，**仅读 quota 字段**（Phase 8 才写完整扣费）
- V2 客户分组（**只读**：GET /api/users/me/groups，Phase 9 之前）

***

### C（后端开发 2）

**完整任务文档：[DEVELOPER_C.md](DEVELOPER_C.md)**（必读）

**职责摘要**：

- Generation 模块（提交任务、polling ComfyUI、状态机、任务取消）
- Workflow 模块（保存/加载/列表/更新/删除 + 模板）
- Storage 模块（MinIO 上传/下载/签名 URL）
- **必读**：ComfyUI API + workflow JSON 结构 + 5 个自研节点（不写节点包代码）

***

### 协作规则（2026-08-02 拍板）

| 改动类型 | 谁可以动 | 流程 |
|---------|---------|------|
| **改文档**（README / API.md / DEVELOPER_*.md / secrets.txt）| 任何开发者 | 直接改 + 提交 PR，至少 1 人 review |
| **改项目配置**（application*.yml / pom.xml / Dockerfile / docker-compose）| **必须 A 同意** | 在 B/C 自己的任务文档里"提请 A"或私聊 A 商量 |
| **改节点包代码**（jurong-api-nodes/）| 节点包团队 | 不归本项目维护 |
| **改业务代码**（springboot/）| 自己负责的模块 | PR review 至少 1 人 |
| **改数据库 schema**（V*__*.sql）| 自己 | 改完发 PR，A review 兼容 |
| **改 docker compose / nginx / 部署脚本** | **A 主导** | B/C 协助 |

**为什么这样分**：配置是公共契约（影响所有人），文档是私有输入（不影响运行）。改配置得 A 同意避免冲突，改文档直接提交省时间。

### 全员共享（协作）

**aicoming-api-nodes 节点包**（A 主导，B+C 协助 review）：

> ⚠️ **Spring Boot 不直接调 NewAPI**（已删 NewApiClient.java）。节点包在 ComfyUI 容器内自己调 NewAPI。Spring Boot 只调 ComfyUI HTTP API。

| 子任务                        | 谁主导               | 谁 review |
| -------------------------- | ----------------- | -------- |
| `api_client.py`（NewAPI 封装） | A                 | B + C    |
| 5 个节点 Python 实现            | 3 人对半分（各 \~1.5 个） | 全员       |
| 3-5 个 workflow 模板设计        | C（最熟悉 ComfyUI）    | A + B    |
| 节点 README / 文档             | A                 | B + C    |
| 节点调试（在 ComfyUI 原生 UI 里）    | C                 | 全员       |

**代码 review 流程**：

- 任何人写的节点 / 后端代码，提交前至少 1 人 review
- 关键决策（架构、安全）由 A 拍板

***

### 不在本项目范围内

- **前端 Vue 项目**：独立项目，由前端团队负责（不在这 3 人范围）
- **运维 / DBA**：服务器由公司运维统一管理
- **设计师 / 产品**：内部使用，简单 UI 即可，不需要专职设计

***

## 7. 实施阶段

| Phase | 标题                           | 谁做            | 工期       | 状态                  |
| ----- | ---------------------------- | ------------- | -------- | ------------------- |
| **0** | 单节点验证（云 ComfyUI 跑通 1 个节点）    | A             | 1-2 天    | 🟡 进行中 (2026-08-01) |
| **1** | 5 节点完整版（5 个节点全部可用）           | A/B/C 协作      | 2 天      | 🟡 进行中 (2026-08-01) |
| **2** | Spring 工程骨架（pom + 5 张表 + 配置） | A 主导，B/C 协助   | 1 天      | 🟡 进行中 (2026-08-01) |
| **3** | Auth + User（无积分）             | B             | 2 天      | ⚪                   |
| **4** | Generation + Storage         | C             | 2 天      | ⚪                   |
| **5** | Workflow 存储                  | C             | 1 天      | ⚪                   |
| **6** | 前端联调（前端团队）                   | 外部            | (前端项目独立) | ⚪                   |
| **7** | Nginx + 收尾 + 试用              | A             | 1-2 天    | ⚪                   |
| **8** | 计费模块（最后）                     | B 主导 + A/C 协助 | 1 周      | ⚪ 未来                |

### Phase 0-1 部署状态（2026-08-02）

**已完成**：

- ✅ **JRAIC-mysql**（MySQL 8.2，port 33062）—— ai\_platform 库 + ai\_app 用户
- ✅ **JRAIC-redis**（Redis 7，port 63791）—— 无密码
- ✅ **JRAIC-minio**（MinIO，port 19000/19001）—— bucket `ai-platform` 创建
- ✅ **jurong-comfyui**（ComfyUI docker，port 8188）—— 5 节点全部注册 + 健康
- ✅ **节点代码 mount volume**：`/opt/aicenter/custom_nodes/jurong-api-nodes/` → 容器 `/app/custom_nodes/`，改代码 5 秒生效
- ✅ Token 通过 base64 文件 + 容器路径注入
- ✅ 端口 8188 / 33062 / 63791 / 19000/19001 全部公网可访问

**容器命名规范**：以 `JRAIC-` 前缀区分业务容器与新中转站容器（`mysql` / `redis` / `new-api` 是新中转站原生的，复用其 docker network）

**docker network**：`new-api_new-api-network`（bridge，172.19.0.0/16）

- `JRAIC-mysql` 172.19.0.5
- `JRAIC-redis` 172.19.0.6
- `JRAIC-minio` 172.19.0.7
- `jurong-comfyui` 8188
- `new-api` 3000 / `mysql` / `redis` / `1Panel-mysql-dvoS` / `1Panel-openresty-8oUb` / `aicoming-video-proxy`

**主机 ComfyUI 已删除**（2026-08-02 12:10）。**主机 Python 依赖（torch/opencv/soundfile 等）已 uninstall**（释放 \~10GB，主机 site-packages 从 6.2GB → 4GB）。**ComfyUI 完全是容器**。

**fallback 策略**：主机无 ComfyUI 备份，**容器挂了 = 用** **`docker compose up -d`** **重启**（在 `/opt/aicenter/comfyui-build/` 目录）。**节点代码备份**：`/opt/aicenter/custom_nodes/jurong-api-nodes/` 是 mount 源，**永不变**。

**已知问题**：

- ⚠️ **图像端点（文生图 / 图生图）**：当前 NewAPI 版本返回异步任务（`{"status":"submitted","task_id":"..."}`）而不是 URL。没有图像任务轮询端点。节点会抛清晰错误，等管理员升级 NewAPI 或换中转。
- ⚠️ **aicoming 响应慢**：实测 5+ 分钟仍卡在 `in_progress 50%`，可能是 aicoming 队列堆积或 token 配额问题。需要 admin token 查 `/api/user/self` 看余额。
- ✅ **视频端点支持 multipart + task polling**（在 aicoming-video-proxy 后端）

**部署文件**：

- `/opt/aicenter/comfyui-build/Dockerfile` — ComfyUI 镜像构建
- `/opt/aicenter/comfyui-build/docker-compose.yml` — 容器编排
- `/opt/aicenter/comfyui-build/ComfyUI/` — build context（45MB 缓存，重 build 时不用重新下载）
- 实际部署：docker compose up -d 起容器
- 启动脚本：**不存在**（docker 自动 restart always）

**端口分配**（确认 2026-08-02）：

| 容器                   | 端口               | 用途                   |
| -------------------- | ---------------- | -------------------- |
| jurong-comfyui       | 8188             | ComfyUI（业务）          |
| **JRAIC-mysql**      | **33062**        | 业务 MySQL（新部署）        |
| **JRAIC-redis**      | **63791**        | 业务 Redis（新部署）        |
| **JRAIC-minio**      | **19000/19001**  | 业务 MinIO（新部署）        |
| mysql                | 33060            | NewAPI 中转站 MySQL（已有） |
| 1Panel-mysql-dvoS    | 33061            | 1Panel MySQL（已有）     |
| redis                | 6379 (内)         | NewAPI 中转站 Redis（已有） |
| new-api              | 3000             | NewAPI 中转站（已有）       |
| aicoming-video-proxy | 8080             | 视频代理（已有）             |
| 1Panel-openresty     | 80/443           | 1Panel Web（已有）       |
| Spring Boot          | (待部署) 8080/18080 | 本地 8080 / 生产 18080   |

### Phase 2 骨架交付物（已完成 2026-08-01）

`springboot/` 目录已建好：

- `pom.xml` — Spring Boot 3.3 / Java 21 / MyBatis Plus / Flyway / JWT / MinIO 全依赖
- `Dockerfile` — 多阶段构建（builder + runtime）
- `src/main/resources/` — `application.yml` + dev/prod profiles + Flyway `V1__init.sql`
- `src/main/java/com/jurong/aicenter/`
  - `AiCenterApplication.java` — 启动类
  - `config/` — `SecurityConfig`, `WebClientConfig`, `MinioConfig`
  - `security/` — `JwtTokenProvider`, `JwtAuthenticationFilter`
  - `exception/` — `BusinessException`, `ErrorCode`, `GlobalExceptionHandler`
  - `entity/` — User, Workflow, Job, BillingLog
  - `repository/` — 4 个 MyBatis Mapper 接口
  - `dto/` — auth / user / workflow / generation / job
  - `client/` — `ComfyUIClient`（仅此一个，NewApiClient 已删 — 死代码）
  - `service/` + `service/impl/` — 接口 + 占位实现（带 `TODO(B)` / `TODO(C)`）
  - `controller/` — AuthController, UserController, GenerationController, WorkflowController, HealthController
- `src/test/` — `AiCenterApplicationTests`（验证 Spring 能启动）

**TODO 标记**：

- `TODO(B)` — Phase 3 B 负责（register/login/refresh 完整实现）
- `TODO(C)` — Phase 4-5 C 负责（Generation submit/poll + Workflow 列表/模板）

**验证方式**：

```bash
cd springboot
mvn test
# 期望：contextLoads() PASSED（说明 Spring 能装配所有 bean）
```

**本地启动**：

```powershell
cd D:\Aworkstation\Work\JurongAICenterbackend\springboot
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

启动后访问：

- `http://localhost:8080/api/health` — 健康检查
- `http://localhost:8080/swagger-ui.html` — OpenAPI 文档
- `http://localhost:8080/api/health` — Actuator

***

## 8. 不做的（v1 范围外）

| 不做                      | 原因              |
| ----------------------- | --------------- |
| 本地模型执行（SD/Flux）         | 4h8g 无 GPU，选了云端 |
| 实时中间帧预览                 | 云端 API 黑盒       |
| 模型微调（LoRA）              | aicoming 没暴露    |
| 支付集成                    | Phase 8 再说      |
| 多用户 ComfyUI workflow 隔离 | v1 接受共享         |
| 微信扫码登录                  | 需资质             |
| 管理员后台 UI                | v1 用 DB 手动      |
| WebSocket 实时进度          | 轮询代替            |
| 移动 App                  | 响应式 Web 够       |

***

## 9. 决策记录

| #   | 决策                     | 选择                                                                                                         | 日期         |
| --- | ---------------------- | ---------------------------------------------------------------------------------------------------------- | ---------- |
| 001 | 项目名                    | Jurong AICenter Backend（暂定）                                                                                | 2026-08-01 |
| 002 | 路径                     | `D:\Aworkstation\Work\JurongAICenterbackend`                                                               | 2026-08-01 |
| 003 | 定位                     | 内部工具，Position A                                                                                            | 2026-08-01 |
| 004 | 后端                     | Spring Boot 3.3 / Java 21                                                                                  | 2026-08-01 |
| 005 | AI 引擎                  | ComfyUI PyTorch CPU-only，无 CUDA，无本地模型权重                                                                    | 2026-08-01 |
| 006 | 模型 API                 | 走自家 NewAPI 中转站                                                                                             | 2026-08-01 |
| 007 | 部署                     | 单机 4h8g，DB/Redis/MinIO 全在云                                                                                 | 2026-08-01 |
| 008 | 数据库 / Redis / MinIO 位置 | 全部云上                                                                                                       | 2026-08-01 |
| 009 | 计费模块                   | 最后做（Phase 8）                                                                                               | 2026-08-01 |
| 010 | 注册即得积分                 | 取消，0 积分                                                                                                    | 2026-08-01 |
| 011 | NewAPI token 存储        | base64 文件 + 容器挂载                                                                                           | 2026-08-01 |
| 012 | dev/prod URL 切换        | 环境变量 `COMFYUI_BASE_URL`                                                                                    | 2026-08-01 |
| 013 | ComfyUI 端口暴露           | 开发期 `0.0.0.0:8188`，生产期 `127.0.0.1:8188`                                                                    | 2026-08-01 |
| 014 | 前端路由                   | 独立项目，前端团队负责（不在本项目 3 人范围）                                                                                   | 2026-08-01 |
| 015 | 团队组成                   | 3 名后端开发：A 主开发 / B 后端 / C 后端                                                                                | 2026-08-01 |
| 016 | 节点包协作                  | aicoming-api-nodes 由 A/B/C 共同维护                                                                            | 2026-08-01 |
| 017 | ComfyUI 依赖             | PyTorch CPU-only（约 200MB），无 CUDA，无本地模型权重                                                                   | 2026-08-01 |
| 018 | ComfyUI 全面 docker 化    | 主机 `/opt/comfyui` 删，节点代码 mount volume 路径：主机 `/opt/aicenter/custom_nodes/` → 容器 `/app/custom_nodes/`        | 2026-08-02 |
| 019 | aicenter 后端云服务         | JRAIC-mysql (33062) + JRAIC-redis (63791) + JRAIC-minio (19000/19001) 三个 docker 容器                         | 2026-08-02 |
| 020 | 配置策略明文化                | 不再环境变量，yml 直接明文 + 根目录 `secrets.txt` reference（不入 git，双份冗余）                                                 | 2026-08-02 |
| 021 | 容器命名规范                 | `JRAIC-` 前缀区分业务容器与新中转站容器                                                                                   | 2026-08-02 |
| 022 | mihomo 装机策略            | 用的时候再装，不常驻（之前装过 v1.19.29 + 测速 73ms 香港02）                                                                   | 2026-08-02 |
| 023 | 节点代码 mount 策略          | 主机 `/opt/aicenter/custom_nodes/` mount 容器 `/app/custom_nodes/`，改代码 5 秒生效，需 `docker restart jurong-comfyui` | 2026-08-02 |
| 024 | 文档归一策略                 | 只维护 README.md 一份（删 `docs/` 目录规划），yml + secrets.txt reference 在项目根                                          | 2026-08-02 |
| 025 | docker daemon proxy 禁用 | **永远不要**给 docker daemon 加全局 HTTP\_PROXY（会触发所有 `--restart always` 容器连锁重启，10:15 mysql 故障教训）                  | 2026-08-02 |

***

## 10. 立即可做的事

**Phase 0-2 已完成**（2026-08-02 ✅）：

- ✅ JRAIC-mysql/redis/minio 部署
- ✅ ComfyUI docker 化（jurong-comfyui 容器）
- ✅ 节点代码 mount volume
- ✅ Spring Boot 5 张表 migration + 基础架构
- ✅ secrets.txt 写入 + yml 明文化

**Phase 3（B 写 Auth）**：

```powershell
# 本地启动 spring boot
cd D:\Aworkstation\Work\JurongAICenterbackend\springboot
mvn spring-boot:run
# 访问 http://localhost:8080/swagger-ui
# curl http://localhost:8080/api/health
```

**本地联通要求**（已在 secrets.txt 里）：

- DB 192.140.163.161:33062 / ai\_app / \*\*\*  ✓
- Redis 192.140.163.161:63791（无密码）✓
- MinIO 192.140.163.161:19000 / aicenter / \*\*\*  ✓
- ComfyUI 192.140.163.161:8188 ✓
- NewAPI 192.140.163.161:3000 ✓

**Phase 3 谁先做**：

- B 启动 Auth 模块：在 `service/impl/AuthServiceImpl.java` 里填 `TODO(B)` 注册/登录/refresh 逻辑
- 完成后 `mvn test` 验证 contextLoads 仍通过
- PR review 后 merge

**A 同时**：

- 写 `docker-compose.yml`（springboot service 加入 JRAIC network）
- 准备 Spring Boot 镜像 build（Dockerfile 已建）
- 端到端测试：注册 → 登录 → 调 ComfyUI 生成 → 上传 MinIO

**B 同步**：

- 熟悉 Spring Security + JWT（参考 `security/JwtTokenProvider.java`）
- 等 Phase 3 骨架 ready 后开始写 Auth 完整逻辑

**C 同步**：

- 验证 ComfyUI 5 节点在 8188 跑（已经跑过）
- 准备 3 个开箱即用的 workflow 模板（看 `/docs/` 已经规划）
- 测一次端到端：登录 spring boot → POST /api/generate → 调 ComfyUI → 看结果

***

## 11. 接口约定（团队协作的关键）

### A 暴露给前端团队（外部 API 约定）

- **OpenAPI 文档**：自动生成于 `http://localhost:8080/swagger-ui`
- **鉴权**：JWT in `Authorization: Bearer <token>`
- **错误格式**：`{ code: int, message: string, data: null }`
- **分页格式**：`{ items: [], total: int, page: int, pageSize: int }`

### 节点包约定（A/B/C 共同遵守）

- **节点 I/O 类型**：STRING / IMAGE / VIDEO / INT / FLOAT / BOOLEAN
- **节点配额定价**：`pricing.py` 里的常量字典
- **节点返回值**：`return (tensor,)` 必须 tuple
- **节点错误**：抛 `Exception("message")`，ComfyUI 会捕获并显示
- **节点类名**：`AicomingXxxYyy` 大驼峰
- **CATEGORY**：`"Aicoming/图像"` 或 `"Aicoming/视频"`
- **节点错误信息**：用 `requests.raise_for_status()` 之前先 log

### 后端模块间接口（A ↔ B、A ↔ C、B ↔ C）

- **Service 层调用**：通过 `@Autowired` 注入，禁止跨模块直接访问数据库
- **DTO 约定**：请求 DTO 用 `XxxRequest`，响应 DTO 用 `XxxResponse`
- **错误码**：每个模块定义自己的错误码段
  - Auth：1xxx
  - User：2xxx
  - Generation：3xxx
  - Workflow：4xxx
  - Billing：5xxx（Phase 8）

### Git 协作约定

- **分支命名**：`feature/<name>` / `fix/<name>` / `docs/<name>`
- **Commit 前缀**：`feat:` / `fix:` / `docs:` / `refactor:` / `test:`
- **PR review**：至少 1 人 approve 才能 merge
- **main 分支**：保护，必须 PR + review

***

## 12. ComfyUI 入门（轻量引导）

> **不写死教程** — ComfyUI 迭代快，自己写的教程会过时。遇到问题直接问 AI（OpenClaw 会话）。

### 13. 遇到问题怎么问 AI

把下面这些问题原样丢给 AI（，**加上你的具体上下文**：

**上手类**：

- "我想跑 ComfyUI 本地试一下，给我最简的 docker run 命令"
- "ComfyUI 启动后浏览器打不开，帮我看下哪出问题了"

**节点开发类**：

- "我在 ComfyUI 写了一个节点，但画布里看不到，帮我看 __init__.py 哪里写错了"
- "ComfyUI 节点 return (tensor,) 但前端报错，帮我看下类型对不对"
- "我的节点要接收 IMAGE 输入，INPUT\_TYPES 怎么写"

**API 调用类**：

- "我想用 curl 调 ComfyUI /prompt 提交一个工作流，给我样例"
- "ComfyUI /history/{id} 返回结构是什么样的，帮我写个解析"
- "Spring Boot 怎么调 ComfyUI /prompt 接口，给我 Java 代码"

**业务类**：

- "我们的 aicoming 视频节点怎么改才能支持参考音频"
- "NewAPI 视频轮询接口返回结构是什么样的，解析一下"
- "workflow JSON 里怎么引用另一个节点的输出"

### 13.4 我们的项目特定提示词

AI 不知道的上下文，**告诉它**：

```
我们的项目背景：
- Spring Boot 后端在本地开发（Windows）
- ComfyUI 在云服务器（4h8g，CPU-only）
- 模型调用走自家 NewAPI 中转
- 5 个自定义节点在 aicoming-api-nodes/（封装 NewAPI 调用）
- 5 张 MySQL 表 + Redis + MinIO 全在云上
```

把这段粘在每个问题前面，AI 回答会更准。

***

**最后更新**：2026-08-02 13:15 by  

**本次变更总结**（2026-08-02）：

- ComfyUI 全面 docker 化（jurong-comfyui 容器 + 节点代码 mount volume）
- 部署 JRAIC-mysql / JRAIC-redis / JRAIC-minio 三个业务容器
- yml 配置明文化（不再环境变量） + secrets.txt reference
- 桌面清理 \~88 个临时脚本
- 文档整合：删 docs/ 目录规划，只维护 README.md
- 新增 7 条 ADR（018-025）

