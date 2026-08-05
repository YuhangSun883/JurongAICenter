# DEVELOPER\_C.md — C 任务文档

> 你的任务范围：**Generation / Workflow / Storage** 三个核心业务模块。
> **必须先熟悉 ComfyUI API**（哪些 input / 哪些 output / 怎么调）—— 这是 C 的核心能力。
> 与 A 一起维护 [API.md](API.md)，**编写/测试请求示例**。

## 0. 你是谁

- **C** = 后端开发者 2
- 负责模块：**Generation / Workflow / Storage**（三个核心业务模块）
- **不** 负责：Auth / User（这是 B 的活） / Billing 完整（Phase 8）
- 你的工作面是 **Spring Boot 后端 + ComfyUI HTTP client + MinIO 集成 + 节点包 API 理解**
- **核心技能**：理解 ComfyUI workflow JSON 结构 + 节点 I/O 类型 + ComfyUI HTTP API

## 1. 任务清单（按 Phase 顺序）

### Phase 4 — Generation + Storage ★ 你的核心

| #      | 任务                                        | 状态                                | 详细                                                                       | 端点                                     |
| ------ | ----------------------------------------- | --------------------------------- | ------------------------------------------------------------------------ | -------------------------------------- |
| **C1** | `GenerationService.submit()` 实现           | 📋 `TODO(C)` 占位                   | workflow 验证（status） + 调 ComfyUI 提交 + 入 jobs 表（PENDING→RUNNING）           | `POST /api/generate`                   |
| **C2** | `GenerationService.getJob()` 实现           | ✅ 部分（TODO: 解析 `result_urls` JSON） | 查 jobs + 鉴权（只能看自己的）                                                      | `GET /api/jobs/{id}`                   |
| **C3** | 后台 polling 线程（@Async）                     | 📋 **你做**                         | 每 5s 调 ComfyUI `/history/{prompt_id}`，COMPLETED 时下载 + 上传 MinIO + 更新 jobs | (后台线程，无端点)                             |
| **C4** | `ComfyUIClient.submitWorkflow()`          | 📋 **你做**                         | `POST /prompt`，返 `prompt_id`，错误处理                                        | (Java 方法)                              |
| **C5** | `ComfyUIClient.getHistory()`              | 📋 **你做**                         | `GET /history/{id}`，返 `status` + `outputs`                               | (Java 方法)                              |
| **C6** | `StorageService.putObject()`              | ✅ 部分                              | 把 InputStream 流式上传到 MinIO                                                | (Java 方法)                              |
| **C7** | `StorageService.getPresignedUrl()`        | 📋 **你做**                         | 24h 有效签名 URL（前端展示用）                                                      | (Java 方法)                              |
| **C8** | 端点 `GET /api/jobs/{id}/result/{filename}` | 📋 **计划中**（**你做**）                | 返文件流 **或** 302 redirect 到 MinIO presigned URL                            | `GET /api/jobs/{id}/result/{filename}` |
| **C9** | 端点 `DELETE /api/jobs/{id}`                | 📋 **计划中**（**你做**）                | 删 job（**注意**：正在 RUNNING 的不能直接删，先 cancel）                                 | `DELETE /api/jobs/{id}`                |

完整端点契约见 [API.md](API.md) §4, §5。

### Phase 5 — Workflow 完整功能

| #       | 任务                             | 状态              | 详细                                                                                            |
| ------- | ------------------------------ | --------------- | --------------------------------------------------------------------------------------------- |
| **C10** | `WorkflowService.save()`       | ✅ 部分            | 已写                                                                                            |
| **C11** | `WorkflowService.listByUser()` | 📋 `TODO(C)` 占位 | 分页 + 返 WorkflowResponse 列表                                                                    |
| **C12** | `WorkflowService.update()`     | ✅ 部分            | 已写                                                                                            |
| **C13** | `WorkflowService.delete()`     | ✅ 部分            | 已写                                                                                            |
| **C14** | 3 个开箱即用 workflow 模板 JSON       | 📋 **你做**       | `workflows/01-product-photo.json` / `02-anime-style.json` / `03-image-to-video.json`（**未来**做） |

完整 WorkflowService 4 个方法都要 work，分页 + 鉴权（只能改自己的）。

### C **不** 负责

- ❌ ComfyUI 节点包代码（`jurong-api-nodes/` 是另一团队 / 另一仓库）
- ❌ 节点代码改动 + commit 到本仓库
- ❌ Auth / User 模块（B 的活）
- ❌ Billing 完整扣费（Phase 8）

## 2. 必读：ComfyUI API

**C 的核心能力是理解 ComfyUI**。参考资料：

- **官方仓库**：`https://github.com/comfyanonymous/ComfyUI`（README + Wiki）
- **云端部署**：浏览器打开 `http://192.140.163.161:8188` —— **直接玩 30 分钟画布**（比文档直观）
- **项目节点包**：`jurong-api-nodes/` —— 5 个现成节点参考（看 `api_client.py` 怎么调 NewAPI）

### ComfyUI HTTP API（C 直接调）

| 端点                     | 方法       | 用途          | 我们怎么用                       |
| ---------------------- | -------- | ----------- | --------------------------- |
| `/prompt`              | POST     | 提交 workflow | 返 `prompt_id`               |
| `/history/{prompt_id}` | GET      | 查任务状态       | 轮询直到 `COMPLETED` / `FAILED` |
| `/object_info`         | GET      | 节点列表        | 验证节点已加载（启动时 sanity check）   |
| `/queue`               | GET/POST | 队列管理        | （**未来**：C9 的 cancel 功能）     |

**C 必读字段**（看 `client/ComfyUIClient.java` 现有代码）：

- 提交请求体：workflow JSON + `client_id`
- 提交响应：`{prompt_id, number, node_errors}`
- history 响应：每个 node 的 `outputs`（images / gifs / videos / files）

### Workflow JSON 结构（关键）

```json
{
  "3": {
    "class_type": "KSampler",
    "inputs": {
      "seed": 42,
      "steps": 20,
      "cfg": 7,
      "sampler_name": "euler",
      "scheduler": "normal",
      "denoise": 1.0,
      "model": ["4", 0],
      "positive": ["6", 0],
      "negative": ["7", 0],
      "latent_image": ["8", 0]
    }
  },
  "6": {
    "class_type": "CLIPTextEncode",
    "inputs": { "text": "a beautiful sunset", "clip": ["4", 1] }
  }
}
```

**关键**：

- node id 是 **字符串**（不是数字）
- input 引用是 **`[node_id, output_index]`** 二元组（不是 Python 引用）
- 整个 workflow 是一个 `{node_id: {class_type, inputs}}` 字典

### 5 个自研节点（C 必须懂 — 节点包团队维护，C 不改）

| 节点 class\_type            | input                   | output                                        |
| ------------------------- | ----------------------- | --------------------------------------------- |
| `JurongTextToImage`       | `prompt` / `size`       | `image` (IMAGE tensor)                        |
| `JurongImageToImage`      | `image` / `prompt`      | `image` (IMAGE tensor)                        |
| `JurongTextToVideo`       | `prompt`                | `first_frame` (IMAGE) + `video_path` (STRING) |
| `JurongImageToVideo`      | `image` / `prompt`      | `first_frame` + `video_path`                  |
| `JurongMultiImageToVideo` | `image_1..4` / `prompt` | `first_frame` + `video_path`                  |

`jurong-api-nodes/` 仓库的 `__init__.py` 里看具体 schema。

## 3. 工作流

### 3.1 PR review 重点

- **ComfyUI 调用超时**：
  - 提交 `/prompt` 30s 内（响应快）
  - **polling** **`/history/{id}`** **每次 10s**（视频生成 5+ 分钟）
  - **不要** 同步阻塞（必须异步 polling）
- **MinIO 流式上传**：用 `stream(input, size, -1)`，**不要** 一次性 `read_all` 到内存
- **workflow JSON 验证**：`inputsSnapshot` 入 jobs 表前做 JSON 解析
- **错误码用对分段**：
  - 3xxx = Generation（**你的**）
  - 4xxx = Workflow（**你的**）
  - 9xxx = Common

### 3.2 与 A 一起维护 [API.md](API.md)

**加 / 改端点的硬规则**：

1. **先改** **[API.md](API.md)** —— 真理源
2. **加 curl 测试示例**（参考 §11 模板）
3. 写代码
4. **mvn test 通过** + 手测 curl
5. **PR 描述里贴 curl 输出 + 流程截图**

**特别**：C 加的端点（`/api/jobs/{id}/result/{filename}` / `DELETE /api/jobs/{id}`）必填 curl 示例。

### 3.3 测试要求

| 层级  | 工具                                | 覆盖                                                                   |
| --- | --------------------------------- | -------------------------------------------------------------------- |
| 单元  | JUnit 5 + Mockito                 | `GenerationService` 状态机（`submit/poll/getJob`）、`WorkflowService` CRUD |
| 集成  | `MockWebServer` mock ComfyUI HTTP | ComfyUIClient 真实请求逻辑                                                 |
| 端到端 | curl 手测                           | 真打一次 `/api/generate`，观察 jobs 表 status 流转                             |

**必须**真打一次 ComfyUI（用 [API.md](API.md) §11 模板）—— 浏览器开 `http://192.140.163.161:8188` 看画布运行。

## 4. 开发环境

- 本地 Windows + IntelliJ IDEA / VSCode
- `mvn spring-boot:run` 启动（端口 8080）
- **ComfyUI 在云端** —— 浏览器开 `http://192.140.163.161:8188` 直接玩
- **MinIO Console** `http://192.140.163.161:19001`（看产物上传）
- 详细配置见 [secrets.txt](secrets.txt)

## 5. 不要做

- ❌ **改项目配置**（`application*.yml` / `pom.xml` / `Dockerfile` / `docker-compose`）—— **改动必须 A 同意**
- ❌ 写 ComfyUI 节点包代码（`jurong-api-nodes/` 是另一团队）
- ❌ 改节点包代码 commit 到本仓库
- ❌ ComfyUI 调用里硬编码 30s 超时（视频 polling 10 分钟）
- ❌ 把图片 / 视频 read 完整到内存再上传 MinIO（**流式**）
- ❌ 同步阻塞等待 ComfyUI 任务完成（必须 async polling）
- ❌ 改 Auth / User 模块代码（B 的活）
- ❌ 改 application\*.yml / pom.xml / Dockerfile / docker-compose（**配置改动和A商量**）

## 6. 上手 checklist

- [ ] 读 [README.md](README.md) §3 架构 + §5 Spring Boot 职责
- [ ] **打开 ComfyUI 浏览器** `http://192.140.163.161:8188` 玩 30 分钟（拖节点、连 SaveImage、点 Run、查 outputs）
- [ ] 看 `client/ComfyUIClient.java`（已有占位代码）+ 节点包 `jurong-api-nodes/` 5 个节点
- [ ] 读 [API.md](API.md) §4, §5（你的 5-7 个端点）
- [ ] 本地 `mvn spring-boot:run` 跑起来
- [ ] 调通 `POST /api/generate` 端到端（提交 → 等 1 分钟 → 查 status → 拿产物）
- [ ] 实现 C1（`GenerationService.submit()`）
- [ ] 实现 C3（后台 polling 线程）
- [ ] 实现 C4, C5（`ComfyUIClient` 完整方法）
- [ ] 实现 C7, C8（`StorageService` + 端点）
- [ ] 实现 C9（`DELETE /api/jobs/{id}` 端点）
- [ ] 实现 C11（`WorkflowService.listByUser()`）
- [ ] 每个新端点 → 更新 [API.md](API.md) → 加 curl 示例 → 测试

## 7. 任务完成定义

- 代码 + 单元测试 + 集成测试 + 端到端 curl 通过
- [API.md](API.md) 更新
- PR 描述：背景 / 改动列表 / curl 输出 / ComfyUI 流程截图
- 至少 1 人 review（A 必须）
- mvn test 通过

***

**最后更新**：2026-08-02 
