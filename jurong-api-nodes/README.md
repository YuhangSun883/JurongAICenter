# Jurong API Nodes for ComfyUI

> 5 个自定义节点，封装 **Jurong 中转站（NewAPI）** 的 AI 生成接口。
> 调用链：`ComfyUI 节点 → NewAPI (jurong) → aicoming.top`

## 节点清单

| 节点 | 功能 | 输入 | 输出 |
|---|---|---|---|
| `JurongTextToImage` | 文本 → 图像 | prompt + size + model + negative | IMAGE |
| `JurongImageToImage` | 图像 + prompt → 图像 | image + prompt + size + model | IMAGE |
| `JurongTextToVideo` | 文本 → 视频 | prompt + duration + resolution + 可选 audio | IMAGE (首帧) + 视频路径 |
| `JurongImageToVideo` | 图像 + prompt → 视频 | image + prompt + duration + resolution + 可选 audio | IMAGE (首帧) + 视频路径 |
| `JurongMultiImageToVideo` | 多图 + prompt → 视频 | image_1~4 + prompt + duration + resolution + 可选 audio | IMAGE (首帧) + 视频路径 |

视频节点**同步阻塞**直到生成完成（ComfyUI 节点都是同步的）。第一帧作为 IMAGE 输出供后续节点链使用，完整视频保存到 ComfyUI `output/jurong_videos/`。

## 安装

```bash
# 在 ComfyUI 容器内
mkdir -p /app/custom_nodes/jurong-api-nodes
cp -r /path/to/jurong-api-nodes/* /app/custom_nodes/jurong-api-nodes/

# 重启 ComfyUI 让它扫描新节点
```

或用 docker volume：

```yaml
volumes:
  - ./jurong-api-nodes:/app/custom_nodes/jurong-api-nodes:ro
```

## 环境变量

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `NEWAPI_BASE_URL` | 是 | `http://192.140.163.161:3000` | NewAPI（中转站）地址 |
| `NEWAPI_TOKEN_FILE` | 是 | `/run/secrets/newapi_token` | token 的 base64 文件路径 |
| `JURONG_VIDEO_OUTPUT_DIR` | 否 | `/app/output/jurong_videos` | 视频保存目录 |

## Token 准备

```bash
# 在本地把明文 token 编码成 base64
echo -n "sk-xxxxxxxx" | base64 > .newapi_token.b64

# 在容器启动时挂载
docker run -v $(pwd)/.newapi_token.b64:/run/secrets/newapi_token:ro ...
```

## 依赖

ComfyUI 容器内需要：

```
opencv-python-headless  # 视频首帧提取（text_to_video / image_to_video / multi_image_to_video）
soundfile              # 音频 tensor → WAV 字节（可选音频参考时用）
numpy
Pillow
requests
torch                   # ComfyUI 自带
```

如果用 `yanwk/comfyui-boot:cpu-latest`，这些依赖通常已装好（open-cv 需手动加）。

## 已知坑（已封装在节点里）

1. **aicoming 顶层 `prompt` 必填** — 必须放 multipart 顶层 + `metadata.content[].text` 双写（这里只写顶层，proxy 处理）
2. **同名 multipart `input_reference`** 支持多张 — 用 tuple 列表，不用 dict
3. **`input_audio` 单个** — 多 part 只取第一个
4. **`duration` 是字符串 `"4"` 不是 int** — 节点已自动转字符串
5. **aicoming 任务完成后会快速删除** — 节点同步轮询 + 立刻下载，避免 404
6. **aicoming-video-proxy 要求至少一个文件** — 即使文生视频也要传文件，节点自动加 16x16 占位 PNG

## 已知问题（2026-08-01 实测）

### 1. 图像端点返回异步（NewAPI calciumion/new-api:latest 不支持图像轮询）
`POST /v1/images/generations` 和 `/v1/images/edits` 返回 `{"status":"submitted","task_id":"..."}` 而不是直接的 URL。该 NewAPI 版本没有暴露图像任务轮询端点。

**临时方案**：节点会抛清晰的错误消息，让管理员升级 NewAPI 或换用其他中转。
**影响**：文生图、图生图节点当前**不可用**。

### 2. aicoming 当前响应慢（实测）
- 文生图：1 次提交后等 30+ 分钟仍 `submitted`，未拿到 URL
- 文生视频：1 次提交后等 5+ 分钟卡在 `in_progress 50%`，可能因 dummy 图或 aicoming 队列堆积
- 可能是上游 aicoming 队列问题，或 token 配额问题（需查 `/api/user/self` 看余额，需要 admin token）

**建议**：让管理员查 NewAPI 管理后台任务状态，确认 aicoming 上游是否正常。

## 上手测试

1. 启动 ComfyUI（容器或本地）
2. 浏览器打开 `http://localhost:8188`（或服务器 IP）
3. 双击空白处 → 搜 "Jurong" → 看到 5 个节点
4. 拖 `JurongTextToImage` 到画布
5. 填 `prompt: "高端手表"`，size 选 "1024x1024"
6. 点 **Queue Prompt**
7. 30 秒后右侧"Generated Images"看到图 → 链路通

## 故障排查

| 问题 | 排查 |
|---|---|
| 节点没出现在画布 | `docker logs comfyui \| grep -i jurong` 看是否加载 |
| 加载失败 | 看日志 Python stack trace，最常见是依赖缺 |
| 提交失败 401 | token 不对或没读出来 → 检查 `NEWAPI_TOKEN_FILE` |
| 提交失败 402 | 余额不足（向管理员充 NewAPI） |
| 视频长时间 PENDING | 直接 curl NewAPI `/v1/videos/{id}` 看上游状态 |
| 视频产物 0 字节 / 404 | aicoming 已删除完成的任务 → 节点应立刻下载，不应延迟 |

## 升级到 Phase 8（计费）

`pricing.py` 已经定义了积分常量和 `calc_image_cost` / `calc_video_cost` 函数。
Phase 8 启用时，Spring 端读取 `pricing.py` 同步到 DB，扣费逻辑在 Spring 实现。
节点本身不感知计费。