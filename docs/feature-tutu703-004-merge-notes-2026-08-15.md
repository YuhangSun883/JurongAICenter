# feature/tutu703-004 合并说明

本文档用于说明本分支本次提交的后台接入内容，方便后续合并到 `main` 时快速确认改动范围、部署配置和验证步骤。

## 1. 本次目标

在最新前台/后端代码基础上，接入一套独立的“全域智像后台”，避免后台账号、后台登录态、后台接口继续和前台客户系统混用。

核心入口：

- 后台页面：`/admin/login`
- 后台接口：`/api/console/**`
- 前台客户接口：继续走原有前台接口
- 后台登录态：使用独立本地存储 key
- 前台登录态：继续使用前台自己的本地存储 key

## 2. 已完成内容

### 2.1 后台登录和账号隔离

- 后台登录改为“全域智像后台”独立页面，避免老板或开发误以为是前台系统。
- 后台账号使用独立后台 token，不再复用前台客户 token。
- 后台请求统一带 `consoleAccessToken`，前台请求继续带 `accessToken`。
- 后台用户信息使用 `consoleUser`，前台用户信息使用 `user`。
- 修复“登录前台时偶尔显示后台账号”的问题，原因是旧代码前后台共用浏览器本地登录态 key。
- 后台默认管理员密码只作用于后台管理员，不会批量修改前台客户密码。

### 2.2 后台页面体验

- 后台名称统一为“全域智像后台”。
- 后台登录页中文化，弱化英文展示，方便老板直接看懂。
- 密码输入框保留小眼睛查看密码，样式和账号输入框统一。
- 后台首页、用户、任务、订单、流水、素材、后台账号等模块做了基础管理展示。
- 后台侧边栏固定，避免数据多时导航栏被页面内容顶走。
- 用户、任务、订单、流水、素材里的关联项支持点击后跳转到对应模块并带上检索条件。
- 管理列表增加检索按钮，避免输入条件后必须切换页面才触发刷新。
- 部分重要检索条件独立展示，提升后台筛选效率。

### 2.3 用户与权限

- 前台客户和后台管理员已区分：
  - 前台客户：业务用户，用于前台登录、生成、素材等功能。
  - 后台管理员：后台操作人员，用于进入 `/admin/login` 管理系统。
- 后台用户管理支持新增后台用户。
- 用户管理中加入修改密码能力。
- 当前管理员账号 `rootadmin@jurong.local` 按后台管理员处理。
- 管理员权限判断改为读取后台登录态，避免错误读取前台客户身份。

### 2.4 个人信息和客户详情

- 前台头像悬浮卡片保留，点击个人信息弹出个人信息弹窗。
- 个人信息弹窗展示邮箱、角色、注册时间等信息。
- 注册时间对应后端用户创建时间 `createdAt`。
- 按需求移除个人信息里的手机号展示。
- 客户详情弹窗改为内部滚动，避免内容过长时弹窗关不掉。
- 客户详情弹窗支持关闭按钮、底部按钮、遮罩层、Esc 关闭。

### 2.5 后端返回数据

- 登录返回体 `AuthResponse` 增加 `createdAt`。
- 前台客户登录时，`createdAt` 来自 `users.createdAt`。
- 后台管理员登录时，`createdAt` 来自 `console_admins.createdAt`。
- 前端用户类型 `UserInfo` 增加 `createdAt` 字段，用于展示注册/创建时间。

### 2.6 构建和本地启动修复

- 修复 Next.js 开发目录和生产构建目录混用导致的 dev chunk 404 问题。
- 开发环境使用 `.next-dev`。
- 生产构建使用 `.next-build`。
- `.gitignore` 补充忽略本地构建产物，避免把临时构建目录提交到仓库。
- 前端默认接口地址调整为真实后端 `http://localhost:8080`。
- 本地 mock 默认关闭，避免登录/注册走假数据。

### 2.7 已顺手修复的旧类型问题

- `src/api/video.real.ts` 里的 `TaskStatus` 旧类型问题已处理。
- `src/components/common/MediaPickerDialog.tsx` 里的 `assetUrls` 旧类型问题已处理。

## 3. 主要改动文件

前端认证和请求隔离：

- `frontend/src/lib/auth-store.ts`
- `frontend/src/lib/http.ts`
- `frontend/src/api/auth.real.ts`
- `frontend/src/api/auth.mock.ts`
- `frontend/src/api/console.ts`
- `frontend/src/api/config.ts`
- `frontend/src/types/user.ts`

后台页面和弹窗：

- `frontend/src/app/admin/page.tsx`
- `frontend/src/components/admin/ConsoleShell.tsx`
- `frontend/src/components/admin/ConsoleModal.tsx`

前台个人信息：

- `frontend/src/components/home/Sidebar.tsx`
- `frontend/src/components/common/LoginGate.tsx`

构建配置：

- `frontend/next.config.mjs`
- `frontend/tsconfig.json`
- `.gitignore`
- `frontend/.gitignore`

后端登录返回：

- `springboot/src/main/java/com/jurong/aicenter/dto/auth/AuthResponse.java`
- `springboot/src/main/java/com/jurong/aicenter/service/impl/AuthServiceImpl.java`
- `springboot/src/main/java/com/jurong/aicenter/controller/ConsoleAuthController.java`

## 4. 合并时重点检查

1. 不要把后台登录态再改回前台登录态 key。
2. `/api/console/**` 应只带后台 token。
3. 前台登录、注册应继续走前台接口和前台 token。
4. 后台管理员账号不要写入或覆盖 `users` 表。
5. 前台客户密码没有被统一改成后台管理员密码。
6. `next.config.mjs` 中开发和生产构建目录保持分离，避免本地启动后页面 chunk 报 404。
7. 部署前端时需要包含后台页面，否则服务器访问不到 `/admin/login`。

## 5. 部署配置说明

前端至少确认：

```bash
NEXT_PUBLIC_USE_MOCK=false
NEXT_PUBLIC_API_BASE_URL=https://你的后端域名或网关地址
```

如果前端使用 Next.js 服务端代理到后端，还需要：

```bash
INTERNAL_API_BASE_URL=http://你的后端内网地址:8080
```

后端至少确认：

```bash
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:mysql://你的数据库地址:3306/你的库名?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=你的数据库账号
SPRING_DATASOURCE_PASSWORD=你的数据库密码
JWT_SECRET=至少32位以上生产密钥
CONSOLE_ADMIN_EMAIL=rootadmin@jurong.local
CONSOLE_ADMIN_PASSWORD=生产环境后台管理员初始密码
CONSOLE_ADMIN_DISPLAY_NAME=超级管理员
```

注意：不要把真实数据库密码、JWT 密钥、NewAPI 密钥提交到 GitHub。

## 6. 上线后验证清单

1. 打开 `/admin/login`，标题应为“全域智像后台”。
2. 使用后台管理员账号登录，确认进入后台首页。
3. 打开前台登录页，确认不会显示后台账号信息。
4. 前台客户登录、注册可以正常提交。
5. 后台用户管理能看到前台客户列表。
6. 后台账号管理可以新增后台管理员。
7. 用户详情弹窗可以正常打开、滚动和关闭。
8. 个人信息弹窗可以正常打开，并显示注册时间。
9. 用户、任务、订单、流水、素材中的关联项点击后能跳转检索。
10. 页面刷新后，前后台登录态仍然互不影响。

## 7. 当前风险和后续建议

- 当前后台已经具备基础可用能力，但真实支付、真实计费、支付回调流水仍需要后续按正式中转/支付网关方案接入。
- 后台权限目前适合先上线验收，后续建议继续细化到按钮级权限和操作审计。
- 前台和后台建议后续在服务端也继续保持账号表、token 用途、接口权限三层隔离。
- 合并前建议在测试环境重新跑一次前台登录、后台登录、用户详情、后台新增用户四条主流程。

## 8. 本地验证记录

- 本地前端服务：`http://localhost:3000`
- 本地后端服务：`http://localhost:8080`
- 后台管理员登录接口已验证返回后台管理员角色。
- 前台登录接口已验证返回前台用户角色和创建时间。
