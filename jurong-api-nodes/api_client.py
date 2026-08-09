"""
Jurong (NewAPI) HTTP 客户端
封装我们中转站的图像/视频接口调用

调用链：节点 → NewAPI (jurong) → aicoming.top
- NewAPI base URL: $NEWAPI_BASE_URL (默认 http://192.140.163.161:3000)
- Token: 从 $NEWAPI_TOKEN_FILE 读 base64 文件（部署时注入）
"""
import os
import base64
import time
import logging
from pathlib import Path

import requests

logger = logging.getLogger("jurong_api")

NEWAPI_BASE_URL = os.environ.get("NEWAPI_BASE_URL", "http://192.140.163.161:3000").rstrip("/")
NEWAPI_TOKEN_FILE = os.environ.get("NEWAPI_TOKEN_FILE", "/run/secrets/newapi_token")


def _load_token() -> str:
    """从 base64 文件读 token。"""
    p = Path(NEWAPI_TOKEN_FILE)
    if not p.exists():
        raise RuntimeError(f"NEWAPI_TOKEN_FILE not found: {NEWAPI_TOKEN_FILE}")
    encoded = p.read_text(encoding="utf-8").strip()
    return base64.b64decode(encoded).decode("utf-8")


try:
    NEWAPI_TOKEN = _load_token()
except Exception as e:
    logger.warning("Failed to load NewAPI token at import time: %s", e)
    NEWAPI_TOKEN = None


def _auth_headers() -> dict:
    if not NEWAPI_TOKEN:
        raise RuntimeError("NewAPI token not loaded")
    return {"Authorization": f"Bearer {NEWAPI_TOKEN}"}


def check_balance() -> dict:
    """查询 NewAPI 用户配额。返回 JSON（具体字段看 NewAPI 实现）。"""
    # NewAPI 的 /api/user/self 是常见路径，这里做 best-effort 调用
    resp = requests.get(
        f"{NEWAPI_BASE_URL}/api/user/self",
        headers=_auth_headers(),
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()

def chat_completion(messages, model="gpt-5.4-mini", temperature=0.5,
                    max_tokens=300, timeout=60) -> dict:
    """POST /v1/chat/completions - OpenAI 兼容,支持 vision (image_url content).

    2026-08-07 新增:用于图像描述注入,锁定图生视频的主体。
    """
    body = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    resp = requests.post(
        f"{NEWAPI_BASE_URL}/v1/chat/completions",
        headers={**_auth_headers(), "Content-Type": "application/json"},
        json=body,
        timeout=timeout,
    )
    resp.raise_for_status()
    return resp.json()


def describe_image(image_bytes, model="gpt-5.4-mini") -> str:
    """用视觉模型描述图片,1-2 句话,聚焦主体(物种/外观/关键特征)。

    2026-08-07 新增:让图生视频节点拿到具体的主体描述,避免模型跑偏。
    """
    import base64
    b64 = base64.b64encode(image_bytes).decode("ascii")
    messages = [{
        "role": "user",
        "content": [
            {"type": "text", "text":
                "Describe this image in 1-2 short sentences. "
                "Focus on the MAIN SUBJECT (what it is, its appearance, key features). "
                "Be specific and concise. Reply in English only. "
                "Start directly with the subject, no preamble."
            },
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}}
        ]
    }]
    resp = chat_completion(messages, model=model, max_tokens=150, temperature=0.3)
    try:
        text = resp["choices"][0]["message"]["content"].strip()
        if not text:
            raise RuntimeError(f"Vision model returned empty content: {resp}")
        return text
    except (KeyError, IndexError) as e:
        raise RuntimeError(f"Vision model returned unexpected response: {resp}") from e



# ============================================================================
# 图像接口
# ============================================================================

def generate_image(prompt: str, model: str, size: str,
                   negative_prompt: str = "", n: int = 1,
                   poll_timeout_sec: int = 600, poll_interval: int = 8,
                   **kwargs) -> str:
    """POST /v1/images/generations
    返回第一个结果的 URL。

    接受 **kwargs 以兼容 ComfyUI 节点可能传的其他参数（如 quality, style 等），
    这些参数会被静默忽略，避免 ComfyUI 节点升级后调用失败。

    已知行为（2026-08-01 实测）：NewAPI（calciumion/new-api:latest）对部分图像任务
    只回传 {"data":[{"task_id":"..."}]}，没有直接的 url，此时走 Case 2 异步轮询分支
    调用 GET /api/task/{task_id} 拿到最终 url。
    """
    if kwargs:
        logger.debug("generate_image ignoring extra kwargs: %s", list(kwargs.keys()))

    body = {
        "model": model,
        "prompt": prompt,
        "size": size,
        "n": n,
    }
    if negative_prompt:
        body["negative_prompt"] = negative_prompt

    resp = requests.post(
        f"{NEWAPI_BASE_URL}/v1/images/generations",
        headers={**_auth_headers(), "Content-Type": "application/json"},
        json=body,
        timeout=600,
    )
    resp.raise_for_status()
    data = resp.json()

    # === 2026-08-08 修复: 增加日志 + 内联 b64 解码(绕过 _save_b64_to_tempfile) ===
    logger.info("[generate_image] Response keys: %s", list(data.keys()))
    if isinstance(data.get("data"), list) and data["data"]:
        first_keys = list(data["data"][0].keys()) if isinstance(data["data"][0], dict) else type(data["data"][0]).__name__
        logger.info("[generate_image] data['data'] has %d item(s), first keys: %s",
                    len(data["data"]), first_keys)

    # Case 1: 同步返回 —— url 或 b64_json 都支持
    if isinstance(data.get("data"), list) and data["data"]:
        first = data["data"][0]
        if isinstance(first, dict):
            url = first.get("url")
            if isinstance(url, str) and url:
                logger.info("[generate_image] Sync (url): %s", url)
                return url
            b64 = first.get("b64_json")
            if isinstance(b64, str) and b64 and len(b64) > 100:
                logger.info("[generate_image] Sync (b64_json, %d chars), decoding inline...", len(b64))
                import base64, os
                img_bytes = base64.b64decode(b64)
                tmp_dir = os.environ.get("JURONG_VIDEO_OUTPUT_DIR", "/app/output/jurong_videos")
                os.makedirs(tmp_dir, exist_ok=True)
                tmp_path = os.path.join(tmp_dir, f"jurong_t2i_{os.urandom(4).hex()}.png")
                with open(tmp_path, "wb") as f:
                    f.write(img_bytes)
                logger.info("[generate_image] Wrote %d bytes to %s", len(img_bytes), tmp_path)
                return tmp_path
            logger.warning("[generate_image] First item has neither url nor valid b64_json. Keys: %s",
                           list(first.keys()))

    # Case 2: 异步返回 → 轮询 /api/task/{task_id}
    if isinstance(data.get("data"), list) and data["data"] and isinstance(data["data"][0], dict) and "task_id" in data["data"][0]:
        task_id = data["data"][0]["task_id"]
        logger.info("Image task %s submitted, polling %s every %ds (timeout %ds)...",
                    task_id, f"{NEWAPI_BASE_URL}/api/task/{task_id}",
                    poll_interval, poll_timeout_sec)
        result = wait_for_image_task(
            task_id,
            timeout_sec=poll_timeout_sec,
            poll_interval=poll_interval,
        )
        url = _extract_url_from_image_task(result)
        logger.info("Generated image async: %s", url)
        return url

    raise RuntimeError(f"Unexpected image response: keys={list(data.keys())}, data_preview={str(data)[:300]}")


def edit_image(image_bytes: bytes, prompt: str, model: str, size: str = "1024x1024",
               poll_timeout_sec: int = 600, poll_interval: int = 8,
               **kwargs) -> str:
    """POST /v1/images/edits (multipart)
    image_bytes: 输入图片的 PNG/JPG 字节

    接受 **kwargs 以兼容 ComfyUI 节点可能传的其他参数（如 quality, style 等），
    这些参数会被静默忽略，避免 ComfyUI 节点升级后调用失败。
    """
    if kwargs:
        logger.debug("edit_image ignoring extra kwargs: %s", list(kwargs.keys()))

    files = {"image": ("input.png", image_bytes, "image/png")}
    data = {
        "model": model,
        "prompt": prompt,
        "size": size,
    }
    resp = requests.post(
        f"{NEWAPI_BASE_URL}/v1/images/edits",
        headers=_auth_headers(),
        files=files,
        data=data,
        timeout=600,
    )
    resp.raise_for_status()
    data = resp.json()

    if "data" in data and isinstance(data["data"], list) and data["data"]:
        first = data["data"][0]
        if isinstance(first, dict):
            if first.get("url"):
                logger.info("Edited image sync (url): %s", first["url"])
                return first["url"]
            if first.get("b64_json"):
                logger.info("Edited image sync (b64_json), decoding...")
                return _save_b64_to_tempfile(first["b64_json"], prefix="jurong_edit")
        # Case 2: 异步 task_id → 走图像任务轮询（与 text_to_image 对齐）
        if isinstance(first, dict) and first.get("task_id"):
            task_id = first["task_id"]
            logger.info("Image edit task %s submitted, polling %s every %ds (timeout %ds)...",
                        task_id, f"{NEWAPI_BASE_URL}/api/task/{task_id}",
                        poll_interval, poll_timeout_sec)
            result = wait_for_image_task(
                task_id,
                timeout_sec=poll_timeout_sec,
                poll_interval=poll_interval,
            )
            url = _extract_url_from_image_task(result)
            logger.info("Edited image async: %s", url)
            return url

    raise RuntimeError(f"Unexpected edit image response: {data}")


# ============================================================================
# 图像异步任务轮询（NewAPI 部分版本只回 task_id，需 GET /api/task/{task_id}）
# ============================================================================

def _extract_url_from_image_task(result: dict) -> str:
    """从 /api/task/{task_id} 响应里抠出图像 URL —— 兼容多种 NewAPI 形态。

    形态 1: data.url                                (NewAPI 常见，task.data.url)
    形态 2: data.data[0].url                        (data 里再包一层数组)
    形态 3: 顶层 url / output_url / image_url 等    (兼容 OpenAI 风格)
    形态 4: 顶层 data 是数组                        (兼容 NewAPI 包装)
    """
    if not isinstance(result, dict):
        raise RuntimeError(f"Unexpected image task response: {result!r}")

    data_obj = result.get("data")

    # 形态 1 / 2: data 是 dict
    if isinstance(data_obj, dict):
        for key in ("url", "output_url", "image_url", "download_url", "file_url"):
            if data_obj.get(key):
                return data_obj[key]
        # 形态 2: data.data[0].url
        nested = data_obj.get("data")
        if isinstance(nested, list) and nested:
            first = nested[0]
            if isinstance(first, dict) and first.get("url"):
                return first["url"]

    # 形态 4: 顶层 data 是数组
    if isinstance(data_obj, list) and data_obj:
        first = data_obj[0]
        if isinstance(first, dict):
            for key in ("url", "output_url", "image_url"):
                if first.get(key):
                    return first[key]

    # 形态 3: 顶层 url
    for key in ("url", "output_url", "image_url", "download_url", "file_url"):
        if result.get(key):
            return result[key]

    raise RuntimeError(f"Could not extract image URL from task result: {result}")


def poll_image_task(task_id: str) -> dict:
    """GET /api/task/{task_id} —— 查一次图像异步任务状态。

    2026-08-07 修复:NewAPI 在任务完成后会清理记录返回 404,这是预期行为。
    返回 sentinel dict {"__jurong_404__": True, "task_id": ...} 让上层处理,
    而不是抛 HTTPError 炸掉整个 workflow。
    """
    resp = requests.get(
        f"{NEWAPI_BASE_URL}/api/task/{task_id}",
        headers=_auth_headers(),
        timeout=30,
    )
    if resp.status_code == 404:
        logger.info("Image task %s returned 404 (server cleaned up)", task_id)
        return {"__jurong_404__": True, "task_id": task_id}
    resp.raise_for_status()
    return resp.json()


def wait_for_image_task(task_id: str, timeout_sec: int = 600,
                        poll_interval: int = 8) -> dict:
    """轮询直到图像异步任务完成或超时。

    完成的返回：{"status": "success", "data": {"url": "..."}, ...}
    状态值兼容: success/completed/succeeded 与 failed/error/failure/cancelled。

    2026-08-07 修复 NewAPI 任务清理导致的 404:
    - 轮询过程中服务端返回 404（任务已被清理）不再视为致命错误
    - 若之前拿到过 status=success,返回最后一次成功的响应(含 URL),避免丢结果
    - 若从未拿到 success 就被 404,抛清晰的 RuntimeError 带 task_id
    """
    start = time.time()
    last_status = None
    last_success_result = None  # 记住最后一次 status=success 的响应
    while time.time() - start < timeout_sec:
        result = poll_image_task(task_id)

        # 服务端清理任务记录(404 哨兵)
        if isinstance(result, dict) and result.get("__jurong_404__"):
            if last_success_result is not None:
                logger.warning(
                    "Image task %s was cleaned up by server (404) but we have "
                    "the success result cached, returning it.",
                    task_id,
                )
                return last_success_result
            raise RuntimeError(
                f"Image task {task_id} not found on server (404). "
                f"This usually means the server cleaned up the task record "
                f"before we could retrieve the result. The task may have "
                f"completed or expired. Check NewAPI server logs for {task_id}."
            )

        # NewAPI 通常把 status 放在 data.status，也有直接放顶层的
        data_obj = result.get("data") if isinstance(result.get("data"), dict) else {}
        status = (
            data_obj.get("status") or result.get("status") or "unknown"
        ).lower()
        if status != last_status:
            logger.info("Image task %s status: %s", task_id, status)
            last_status = status
        if status in ("success", "completed", "succeeded"):
            last_success_result = result  # 缓存
            return result
        if status in ("failed", "error", "failure", "cancelled"):
            raise RuntimeError(f"Image task {task_id} failed: {result}")
        time.sleep(poll_interval)
    raise TimeoutError(f"Image task {task_id} did not complete in {timeout_sec}s")


# ============================================================================
# 视频接口
# ============================================================================

def submit_video(prompt: str, model: str = "doubao-seedance-2.0",
                 image_files: list = None, audio_file: tuple = None,
                 duration: int = 4, resolution: str = "480P") -> dict:
    """POST /v1/videos (multipart)
    image_files: [(filename, bytes, content_type), ...]  作为 input_reference
    audio_file:  (filename, bytes, content_type)         作为 input_audio（可选）

    关键坑（已踩）：
      - body.prompt 必须放最顶层（aicoming 强制要求）
      - input_reference 支持多张（同名 multipart part）
      - input_audio 单个（多了只取第一个）
      - 2026-08-01 实测：aicoming-video-proxy 即使对文生视频也要求至少一个 multipart file，
        所以当 image_files 为空时自动加一张 16x16 透明 PNG 作为占位
    """
    data = {
        "model": model,
        "prompt": prompt,  # ← 顶层必填
        "duration": str(duration),  # ← aicoming 要求字符串 "4" 不是 int
        "resolution": resolution,
    }

    files = []
    if image_files:
        for filename, content, content_type in image_files:
        # 2026-08-08 修复: doubao-seedance-2.0 期望 'image' 字段,不是 'input_reference'
        # 同时发 3 个字段名,兼容 Doubao Seedance / Sora / aicoming-proxy
        files.append(("image", (filename, content, content_type)))
        files.append(("input_reference", (filename, content, content_type)))
        files.append(("image_url", (filename, content, content_type)))
        logger.info("[submit_video] Sent image under 3 field names (%d bytes each), total %d parts", len(content), len(files))
    else:
        # 占位: aicoming-video-proxy 要求至少一个文件
        files.append(("image", ("_placeholder.png", _DUMMY_PNG_BYTES, "image/png")))
        files.append(("input_reference", ("_placeholder.png", _DUMMY_PNG_BYTES, "image/png")))
        files.append(("image_url", ("_placeholder.png", _DUMMY_PNG_BYTES, "image/png")))
    if audio_file:
        filename, content, content_type = audio_file
        files.append(("input_audio", (filename, content, content_type)))

    resp = requests.post(
        f"{NEWAPI_BASE_URL}/v1/videos",
        headers=_auth_headers(),
        data=data,
        files=files,
        timeout=600,
    )
    resp.raise_for_status()
    result = resp.json()
    task_id = result.get("id") or result.get("task_id")
    logger.info("Submitted video task: %s (model=%s, duration=%s%s)",
                task_id, model, duration,
                f", {len(image_files)} images" if image_files else "")
    return result


def poll_video(task_id: str) -> dict:
    """GET /v1/videos/{task_id}
    返回 aicoming 实时状态：in_progress / completed / failed

    2026-08-07 修复:同 poll_image_task,404 返回 sentinel 而不是抛异常。
    """
    resp = requests.get(
        f"{NEWAPI_BASE_URL}/v1/videos/{task_id}",
        headers=_auth_headers(),
        timeout=30,
    )
    if resp.status_code == 404:
        logger.info("Video task %s returned 404 (server cleaned up)", task_id)
        return {"__jurong_404__": True, "task_id": task_id}
    resp.raise_for_status()
    return resp.json()


def wait_for_video(task_id: str, timeout_sec: int = 600,
                   poll_interval: int = 8) -> dict:
    """轮询直到视频任务完成或超时。
    完成的返回：{"status": "completed", "metadata": {"url": "..."}}

    2026-08-07 修复:同 wait_for_image_task,处理 404 sentinel 和缓存最终响应。
    """
    start = time.time()
    last_status = None
    last_success_result = None  # 记住最后一次 status=completed 的响应
    while time.time() - start < timeout_sec:
        result = poll_video(task_id)

        # 服务端清理任务记录(404 哨兵)
        if isinstance(result, dict) and result.get("__jurong_404__"):
            if last_success_result is not None:
                logger.warning(
                    "Video task %s was cleaned up by server (404) but we have "
                    "the success result cached, returning it.",
                    task_id,
                )
                return last_success_result
            raise RuntimeError(
                f"Video task {task_id} not found on server (404). "
                f"This usually means the server cleaned up the task record "
                f"before we could retrieve the result. Check NewAPI server "
                f"logs for {task_id}."
            )

        status = result.get("status", "unknown")
        if status != last_status:
            logger.info("Video task %s status: %s", task_id, status)
            last_status = status
        if status in ("completed", "succeeded", "success"):
            last_success_result = result  # 缓存
            return result
        if status in ("failed", "error", "cancelled"):
            raise RuntimeError(f"Video task {task_id} failed: {result}")
        time.sleep(poll_interval)
    raise TimeoutError(f"Video task {task_id} did not complete in {timeout_sec}s")


# 16x16 透明 PNG 占位图（用于 text-to-video 必须传文件的场景）
_DUMMY_PNG_BYTES = (
    b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x10\x00\x00\x00\x10'
    b'\x08\x06\x00\x00\x00\x1f\xf3\xffa\x00\x00\x00\x19tEXtSoftware\x00'
    b'Adobe ImageReadyq\xc9e<\x00\x00\x00\x0eIDATx\xdac\xfc\xcf\xc0P\x0f'
    b'\x00\x00\x00\x05\x00\x01\xa7\x9c\xa5\xa7\x00\x00\x00\x00IEND\xaeB`\x82'
)


def extract_video_url(poll_result: dict) -> str:
    """从 poll 结果里抠出视频 URL —— 兼容 7 种格式 (aicoming / NewAPI / Sora 等)。

    形态 1: {metadata: {url: "..."}}    (OpenAI Sora flat)
    形态 2: {result: {metadata: {url: "..."}}}
    形态 3: {result: {url: "..."}}
    形态 4: 顶层 url
    形态 5: output_url / video_url / download_url / file_url (aicoming 别名)
    形态 6: content[0].url (OpenAI content array)
    形态 7: data[0].url (NewAPI 包装)

    Returns:
        视频 URL 字符串

    Raises:
        RuntimeError: 找不到 URL 时 (响应里没任何已知字段)
    """
    # 形态 1
    if isinstance(poll_result.get("metadata"), dict):
        url = poll_result["metadata"].get("url")
        if url: return url
    # 形态 2 / 3
    result_obj = poll_result.get("result")
    if isinstance(result_obj, dict):
        if isinstance(result_obj.get("metadata"), dict):
            url = result_obj["metadata"].get("url")
            if url: return url
        if "url" in result_obj:
            return result_obj["url"]
    # 形态 4
    if "url" in poll_result:
        return poll_result["url"]
    # 形态 5: 别名
    for key in ("output_url", "video_url", "download_url", "file_url"):
        if key in poll_result:
            return poll_result[key]
    # 形态 6: content 数组
    content = poll_result.get("content")
    if isinstance(content, list) and content:
        first = content[0]
        if isinstance(first, dict) and first.get("url"):
            return first["url"]
    # 形态 7: data 数组
    data = poll_result.get("data")
    if isinstance(data, list) and data:
        first = data[0]
        if isinstance(first, dict):
            for key in ("url", "video_url", "output_url"):
                if key in first:
                    return first[key]

    raise RuntimeError(f"Could not extract video URL from: {poll_result}")


# ============================================================================
# 工具：下载 + tensor 转换
# ============================================================================

def download_bytes(url: str, timeout: int = 300) -> bytes:
    """下载字节。支持 http(s):// 与 file://（处理 Windows file:///C:/... 盘符）。"""
    if url.startswith("file://"):
        from pathlib import Path
        import urllib.parse
        parsed = urllib.parse.urlparse(url)
        # 不依赖 url2pathname（Linux 上 urllib.parse 根本没有这个名字，
        # nturl2path 是 Windows-only 的逻辑——会把 /app/temp/... 转成反斜杠，
        # 在 Linux 上反而是 bug）。直接手写：unquote + 处理 Windows 盘符。
        local_path = urllib.parse.unquote(parsed.path)
        if os.name == "nt" and local_path.startswith("/") and len(local_path) > 2 and local_path[2] == ":":
            # file:///C:/foo → C:/foo
            local_path = local_path[1:]
        return Path(local_path).read_bytes()
    resp = requests.get(url, timeout=timeout)
    resp.raise_for_status()
    return resp.content


def _save_b64_to_tempfile(b64_str: str, prefix: str = "jurong_img") -> str:
    """把 NewAPI 回传的 b64_json 解码后写本地文件，返回 file:// URL。

    优先放 ComfyUI temp 目录（通过 folder_paths 拿），找不到就系统 temp。
    留作临时中转，下游 image_url_to_tensor 读完就被 GC，无需长留。
    """
    import base64
    import tempfile
    import uuid
    from pathlib import Path

    raw = base64.b64decode(b64_str)
    try:
        import folder_paths  # ComfyUI 自带
        out_dir = Path(folder_paths.get_temp_directory())
    except Exception:
        out_dir = Path(tempfile.gettempdir())
    out_dir.mkdir(parents=True, exist_ok=True)

    fp = out_dir / f"{prefix}_{uuid.uuid4().hex[:8]}.png"
    fp.write_bytes(raw)
    file_url = fp.as_uri()  # 自动处理 Windows file:///C:/... 与 POSIX file:///tmp/...
    logger.info("Saved b64 image to %s (%d bytes)", fp, len(raw))
    return file_url


def image_url_to_tensor(url: str):
    """下载 URL 图片，转成 ComfyUI IMAGE tensor。"""
    raw = download_bytes(url)
    return _bytes_to_tensor(raw)


def _bytes_to_tensor(raw: bytes):
    """图片 bytes → ComfyUI IMAGE tensor（共用底层）。"""
    import io
    from PIL import Image
    import numpy as np
    import torch
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    arr = np.array(pil).astype(np.float32) / 255.0  # [H, W, C]
    tensor = torch.from_numpy(arr).unsqueeze(0)  # [1, H, W, C]
    return tensor


# 兼容旧名：仓库历史上有节点/工作流直接用 image_bytes_to_tensor 这个名字。
# 老版本的 text_to_image.py 传的是 URL 字符串，少数魔改版传的是图片 bytes，
# 这里两种都接，内部走同一条 _bytes_to_tensor 路径。
def image_bytes_to_tensor(data):
    """兼容旧名：接受 URL 字符串 或 图片 bytes，统一转成 IMAGE tensor。"""
    if isinstance(data, (bytes, bytearray)):
        return _bytes_to_tensor(bytes(data))
    # 其他全部当 URL 处理（http/https/file://）
    return image_url_to_tensor(data)


def tensor_to_png_bytes(tensor) -> bytes:
    """[1, H, W, C] float tensor → PNG bytes"""
    import numpy as np
    from PIL import Image
    import io

    arr = (tensor[0].cpu().numpy() * 255).clip(0, 255).astype(np.uint8)
    pil = Image.fromarray(arr)
    buf = io.BytesIO()
    pil.save(buf, format="PNG")
    return buf.getvalue()


def save_video_file(video_url: str, output_dir: str, filename_prefix: str = "jurong") -> str:
    """下载视频，保存到 ComfyUI output 目录，返回最终文件路径。"""
    from pathlib import Path
    import uuid

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    ext = "mp4" if "mp4" in video_url.lower() or video_url.endswith(".mp4") else "bin"
    filename = f"{filename_prefix}_{uuid.uuid4().hex[:8]}.{ext}"
    full_path = out_dir / filename

    raw = download_bytes(video_url)
    full_path.write_bytes(raw)
    logger.info("Saved video: %s (%d bytes)", full_path, len(raw))
    return str(full_path)