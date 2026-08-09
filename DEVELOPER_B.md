# DEVELOPER\_B.md — B 任务文档

> 你的任务范围：**用户管理 + 客户分组（V2 实体已建） + 配额查询（预留**,不关联扣费**）+ Admin 模块（Phase 9 提前）。
> 与 A 一起维护 [API.md](API.md)，**编写/测试请求示例**。

## 0. 你是谁

- **B** = 后端开发者 1
- 负责模块：**Auth / User / 客户分组（V2）/ 配额查询**
- **不** 负责：Generation / Workflow / Storage（这是 C 的活） / 完整 Billing 扣费（Phase 8）
- 你的工作面是 **Spring Boot 后端 + JRAIC-mysql + JRAIC-redis**

## 1. 任务清单（按 Phase 顺序）

### Phase 3 — Auth + User（无积分） ★ 你的核心

| #      | 任务                             | 状态              | 详细                                         | 端点                        |
| ------ | ------------------------------ | --------------- | ------------------------------------------ | ------------------------- |
| **B1** | `AuthService.register()` 实现    | 📋 `TODO(B)` 占位 | bcrypt 加密 + email 唯一性 + 入 users 表 + 返 JWT  | `POST /api/auth/register` |
| **B2** | `AuthService.login()` 实现       | 📋 `TODO(B)` 占位 | bcrypt 校验 + 返 access + refresh             | `POST /api/auth/login`    |
| **B3** | `AuthService.refresh()` 实现     | 📋 `TODO(B)` 占位 | refresh token 校验 + 签发新 access              | `POST /api/auth/refresh`  |
| **B4** | `UserController.me()`          | ✅ 已实现           | —                                          | `GET /api/users/me`       |
| **B5** | `UserController` 加 `PATCH /me` | 📋 **你做**       | 修改 `displayName` / `password`（bcrypt 重新加密） | `PATCH /api/users/me`     |

完整端点契约见 [API.md](API.md) §1, §2。

### Phase 8 之前 — 配额查询（**仅查询，不扣费**）

| #      | 任务                                     | 状态        | 详细                                                              | 端点                        |
| ------ | -------------------------------------- | --------- | --------------------------------------------------------------- | ------------------------- |
| **B6** | `GET /api/users/me/quota` 端点           | 📋 **你做** | 直接读 `users` 表的 `credits / monthly_quota / quota_used / plan` 字段 | `GET /api/users/me/quota` |
| **B7** | `QuotaService.getCurrentUsage(userId)` | 📋 **你做** | **只查不扣**                                                        | (服务层方法，被 B6 调用)           |

**关键约束**（来自用户 2026-08-02 拍板）：

- ⚠️ **当前不关联"使用一次扣费多少"** —— Phase 8 之前不写扣费逻辑
- ⚠️ 不写"配额预扣 / 原子预扣 / 流水记账"
- ⚠️ 不写 `QuotaService.deduct(userId, amount)` 这种方法
- **只做读**：`users` 表的 quota 字段 + 返给前端展示

### Phase 8 — 完整 Billing（**未来**，**不在 v1 任务**）

- 计费模块完整实现
- 扣费逻辑
- 流水记账
- **不** 在 v1 任务清单里

### Phase 9 — Admin 模块 ✅ 已完成（2026-08-05，B 实现）

> Phase 9 提前实现：用户需求要求"管理员后台 + 客户分组管理"，于是 B 把 §8.2/§8.3 全部做完。
> 关键决策：把原计划的 `/api/customer/groups/**` 路径**整合到 `/api/admin/groups/**`**（一个 controller），避免双份路径模糊。

| #       | 任务                                                | 端点前缀                                              | 状态                |
| ------- | ------------------------------------------------- | ------------------------------------------------ | ----------------- |
| **B11** | Admin 搜索用户（displayName LIKE + 分页）             | `GET /api/admin/users`                            | ✅                |
| **B12** | Admin 取单用户                                          | `GET /api/admin/users/{id}`                      | ✅                |
| **B13** | Admin 改角色（USER↔ADMIN，禁改自己）                     | `PATCH /api/admin/users/{id}/role`               | ✅                |
| **B14** | Admin 启停账号（禁禁自己）                                 | `PATCH /api/admin/users/{id}/disabled`           | ✅                |
| **B15** | Admin 列出/创建/修改/删除分组                                  | `/api/admin/groups[/{id}]`（GET/POST/PATCH/DELETE） | ✅                |
| **B16** | Admin 列出/加/移分组员                                     | `/api/admin/groups/{id}/members[/{userId}]`      | ✅                |
| **B17** | 审计日志写入（fail-open）                                | admin_audit_logs 表                                 | ✅                |

**额外改动（涉及 B 之外的 bug fix，请 A 关注 review）**：
- [JwtTokenProvider.java](springboot/src/main/java/com/jurong/aicenter/security/JwtTokenProvider.java) 加 `role` claim（之前 token 不带 role，前端无法做页面分流；这一改让前端可解码 role 直接判断展示）
- [JwtAuthenticationFilter.java](springboot/src/main/java/com/jurong/aicenter/security/JwtAuthenticationFilter.java) null role fallback 注释强化（防止后续误改）
- [AuthServiceImpl.java](springboot/src/main/java/com/jurong/aicenter/service/impl/AuthServiceImpl.java) login/refresh 路径加 disabled 校验
- [V5 migration](springboot/src/main/resources/db/migration/V5__admin_module.sql) `users.disabled` 列 + `admin_audit_logs` 表

#### 角色变更的 token 失效策略

管理员把 USER 升 ADMIN（或反过来）后，**被改用户的现有 access token 在 2h 内仍带旧 role**。这是当前设计：
- `PATCH /api/admin/users/{id}/role` 成功后，调用方（前端）**必须**提示被改用户重新登录（或调 `/api/auth/refresh`，refresh 路径会用 DB 最新 role 重签 access）
- 实现简单、不依赖 Redis 黑名单；缺点：2h 内"角色变更及时性"差
- Phase 8 升级 Billing 时可以一并引入"role 黑名单"，但**当前不做**

#### 禁用账号的生效策略

`disabled=true` 在 **login 路径生效**（refresh 也校验）；但**已签发的 access token 在 2h 内仍可使用**。
- 前端如果想立刻"踢人"，可以让用户重新登录或等 2h
- 是否需要"即时踢出全端"（强制 token 失效）是 Phase 8 的事 —— 当前不做

#### Default 分组的不可变性

V2 migration 自动建了 `Default` 分组（is_default=1）。Admin 模块对此加硬约束：
- 不可删除（6005）
- 不可关闭 is_default 标志（6006）
- 新建 is_default=true 的分组会自动"独占"这个身份（其他分组 is_default 重置为 0）

### V2 客户分组（Phase 9 之前）— 实体已建，**API 你做只读**

| #       | 任务                                                                           | 状态                   | 详细                | 端点                    |
| ------- | ---------------------------------------------------------------------------- | -------------------- | ----------------- | --------------------- |
| **B8**  | 实体 `customer/entity/{UserGroup, UserGroupMember}.java`                       | ✅ **已建**（phase 2 骨架） | @TableName + 字段映射 | —                     |
| **B9**  | Repository `customer/repository/{UserGroup, UserGroupMember}Repository.java` | ✅ **已建**             | BaseMapper 接口     | —                     |
| **B10** | `GET /api/users/me/groups` 端点                                                | 📋 **你做**（v1 范围）     | 查当前用户所属分组         | 见 [API.md](API.md) §8 |

**B10 范围**：v1 只做"读我的分组"（`GET /api/users/me/groups`）。**不** 做管理员后台（创建 / 修改 / 删除分组 = Phase 9 任务）。

## 2. 工作流

### 2.1 PR review 重点

- **Auth / User 路径 SQL**（注入风险）—— 用 MyBatis Plus `LambdaQueryWrapper` 或 `@Param` 占位符，**不要**字符串拼接
- **bcrypt cost factor**（默认 10，太慢 / 太低都改；密码强度）
- **JWT expiry 平衡**：
  - access 2h（短——泄露风险小）
  - refresh 7d（长——用户体验好）
  - **不要** 用 access token 存敏感信息
- **错误码用对分段**：
  - 1xxx = Auth（**你的**）
  - 2xxx = User（**你的**）
  - 9xxx = Common

### 2.2 与 A 一起维护 [API.md](API.md)

**加 / 改端点的硬规则**：

1. **先改** **[API.md](API.md)** —— 这是真理源
2. **加 curl 测试示例**（参考 §11 模板）
3. 写代码
4. **mvn test 通过** + 手测 curl
5. **PR 描述里贴 curl 输出**

### 2.3 测试要求

| 层级  | 工具                          | 覆盖                                          |
| --- | --------------------------- | ------------------------------------------- |
| 单元  | JUnit 5 + Mockito           | Service 层业务逻辑（bcrypt 校验、email 唯一性、token 签发） |
| 集成  | `@SpringBootTest` + MockMvc | Controller（API 端点契约、HTTP 状态码、响应格式）          |
| 端到端 | curl 手测                     | 走 [API.md](API.md) §11 模板                   |

**每个新端点必须有 curl 测试用例**贴在 PR 描述里。

## 3. 开发环境

- 本地 Windows + IntelliJ IDEA / VSCode
- `mvn spring-boot:run` 启动（端口 8080）
- `application-dev.yml` 已配好云端 IP（192.140.163.161）
- MySQL / Redis / MinIO 都在云端
- 详细配置见 [secrets.txt](secrets.txt)

## 4. 不要做

- ❌ **不要写完整的 Billing 扣费逻辑**（Phase 8 任务）
- ❌ **不要把"使用一次扣多少 quota"硬编码**（YAGNI）
- ❌ **不要做用户注册时的 email 验证**（v1 不做，节省开发）
- ❌ *不要修改 application*.yml / pom.xml / Dockerfile / docker-compose\*（这些是配置，**配置改动和A商量**）
- ❌ **不要动 Generation / Workflow / Storage** 的代码（C 的活）
- ❌ **不要写 ComfyUI 节点包代码**（节点包在另一个仓库 `jurong-api-nodes/`）
- ❌ **不要修改 SecurityConfig.java 路径规则**（B 已使用 `.hasRole("ADMIN")` 拦截 `/api/admin/**`，
     改它会让 admin 端点对外暴露）
- ❌ **不要给现有 V1/V2/V4 迁移改名 / 删除**（数据库里已经应用，动了会触发 Flyway checksum mismatch）

## 5. 上手 checklist

- [ ] 读 [README.md](README.md) §3 架构 + §5 Spring Boot 职责
- [ ] 读 [API.md](API.md) §1, §2, §3, §8（你的 5-7 个端点）
- [ ] 读 `customer/entity/{UserGroup, UserGroupMember}.java`（V2 实体已建）
- [ ] 本地 `mvn spring-boot:run` 跑起来
- [ ] 调通 `POST /api/auth/register` 流程
- [ ] 实现 B1-B3（3 个 AuthService 方法）
- [ ] 实现 B5（`PATCH /api/users/me`）
- [ ] 实现 B6, B7（`GET /api/users/me/quota` + QuotaService）
- [ ] 实现 B10（`GET /api/users/me/groups`）
- [ ] 每个新端点 → 更新 [API.md](API.md) → 加 curl 示例 → 测试

## 6. 任务完成定义

- 代码 + 单元测试 + 集成测试 + 端到端 curl 通过
- [API.md](API.md) 更新
- PR 描述：背景 / 改动列表 / curl 截图
- 至少 1 人 review（A 必须）
- mvn test 通过

***

**最后更新**：2026-08-05（新增 Phase 9 admin 模块）&#x20;
