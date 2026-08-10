# 协作开发约定

适用范围：`D:\JRai` 仓库全体成员。

## 1. 目标

- 所有人按同一套技术栈写代码，避免合并失败。
- 代码只进自己的分支，不直接碰 `main`。
- 每个任务尽量小，能当天提就当天提。

## 2. 当前技术栈

### 前端

- 目录：`frontend/`
- 框架：`Next.js 14 + React 18 + TypeScript`
- 样式：`Tailwind CSS`
- 状态/动画：`Zustand`、`Framer Motion`

### 后端

- 目录：`springboot/`
- 框架：`Spring Boot 3.3.4 + Java 21`
- ORM：`MyBatis Plus`
- 安全：`Spring Security + JWT`
- 迁移：`Flyway`
- 存储：`MinIO`
- 网络：`WebFlux` + 普通 `Spring Web`

### 其他

- `workflows/`：ComfyUI 工作流 JSON
- `jurong-api-nodes/`：节点包 Python 代码
- `API.md`：接口约定

## 3. 分支规则

- `main` 只用于稳定合并。
- 每个人只在自己的分支开发。
- 分支命名建议：
  - `feature/<姓名>-<模块>`
  - `fix/<姓名>-<问题>`
  - `docs/<姓名>-<文档>`

示例：

```bash
git switch -c feature/yuhangsun883-login
git push -u origin feature/yuhangsun883-login
```

## 4. 提交规则

- 先 `git pull` 再开始写。
- 一个提交只做一件事。
- 提交信息写清楚人和内容。

示例：

```bash
git commit -m "YuhangSun883: 完成登录页"
```

## 5. PR 规则

- 只通过 Pull Request 合并到 `main`。
- PR 标题简短直接。
- PR 描述建议写：
  - 做了什么
  - 改了哪些文件
  - 怎么测的
  - 有没有接口变更
  - 有没有数据库变更

## 6. 文件归属

- 前端功能只改 `frontend/`
- 后端功能只改 `springboot/`
- 数据库结构只改 `springboot/src/main/resources/db/migration/`
- 接口文档只改 `API.md`
- 工作流只改 `workflows/`
- 节点包只改 `jurong-api-nodes/`

## 7. 禁止事项

- 不直接 `push main`
- 不在前端里乱引 Vue、Angular、Svelte 等新框架
- 不在后端里乱改成别的 ORM 或重构成别的架构
- 不跨模块乱改别人的目录
- 不在没约定的情况下改接口字段名

## 8. 合并前检查

- 本地能启动
- 相关页面/接口能跑通
- 没有多余的格式化噪音
- 没有改到无关文件
- 需要的话补一条数据库迁移

## 9. 推荐日常流程

1. 拉最新代码
2. 建自己的分支
3. 写一个小功能
4. 本地验证
5. 提交并推送到自己的分支
6. 发 PR
7. 审核后合并
