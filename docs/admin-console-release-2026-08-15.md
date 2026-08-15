# 全域智像后台上线说明

本包用于先上线一版可用后台，前台功能保持现有业务入口，新增独立后台入口：

- 后台页面：`/admin/login`
- 后台接口：`/api/console/**`
- 旧后台接口：`/api/admin/**` 已禁止访问，避免和前台账号体系混用
- 后台账号表：`console_admins`
- 前台客户表：`users`

注意：即使服务器上已经有旧前台，也需要更新本包里的前端构建产物，否则浏览器访问不到新的 `/admin/login` 后台入口。

## 1. 上线前确认

1. 先备份线上数据库。
2. 后端必须连接线上 MySQL，启动时 Flyway 会自动执行迁移。
3. 本次迁移会新增 `console_admins` 表，不会批量修改 `users` 表里的前台客户密码。
4. 第一个后台管理员通过环境变量初始化，只写入 `console_admins`。

## 2. 后端环境变量

至少需要配置：

```bash
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
SPRING_DATASOURCE_URL=jdbc:mysql://你的数据库地址:3306/你的库名?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
SPRING_DATASOURCE_USERNAME=你的数据库账号
SPRING_DATASOURCE_PASSWORD=你的数据库密码
JWT_SECRET=至少32位以上的生产密钥

CONSOLE_ADMIN_EMAIL=rootadmin@jurong.local
CONSOLE_ADMIN_PASSWORD=上线后请立刻修改的临时强密码
CONSOLE_ADMIN_DISPLAY_NAME=超级管理员

NEWAPI_BASE_URL=你的NewAPI地址
NEWAPI_TOKEN=你的NewAPI密钥
AICOMING_PROXY_BASE_URL=你的素材代理地址
AICOMING_PROXY_TOKEN=你的素材代理密钥

MINIO_ENDPOINT=你的MinIO地址
MINIO_ACCESS_KEY=你的MinIO账号
MINIO_SECRET_KEY=你的MinIO密钥
MINIO_BUCKET=你的桶名
```

说明：`CONSOLE_ADMIN_*` 只在该邮箱不存在时创建一次；后续重启不会覆盖密码。

## 3. 启动后端

```bash
java -jar backend/aicenter-0.1.0-SNAPSHOT.jar
```

健康检查：

```bash
curl http://127.0.0.1:8080/actuator/health
```

## 4. 前端环境变量

如果前端和后端在同一台机器，默认即可：

```bash
INTERNAL_API_BASE_URL=http://127.0.0.1:8080
NEXT_PUBLIC_USE_MOCK=false
```

如果后端是另一台机器，把 `INTERNAL_API_BASE_URL` 改成后端内网地址。

## 5. 启动前端

```bash
cd frontend
npm ci --omit=dev
npm run start -- -p 3000
```

访问：

```text
http://你的域名/admin/login
```

## 6. 上线后检查

1. `/admin/login` 显示“全域智像后台”。
2. 使用 `CONSOLE_ADMIN_EMAIL` 和 `CONSOLE_ADMIN_PASSWORD` 登录。
3. 登录后立刻在“后台账号”里修改管理员密码。
4. 检查“用户与权限”能看到前台用户列表。
5. 检查“后台账号”能新增后台用户。
6. 检查“操作日志”能记录后台操作。

## 7. 回滚

1. 停止新前端和新后端进程。
2. 恢复上一版前端包和上一版后端 jar。
3. 数据库新增的 `console_admins` 表不影响前台业务；如需彻底回退，可在确认无后台依赖后删除该表。

## 8. 本次包内验证

- 前端 `npm run typecheck` 通过。
- 前端 `npm run build` 通过。
- 后端主代码编译通过，jar 打包成功。
- 后端旧 `/api/admin` 测试代码已不匹配新后台隔离方案，打 jar 时跳过了测试编译。
