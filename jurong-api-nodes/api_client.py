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


# ============================================================================
# 图像接口
# ============================================================================

def generate_image(prompt: str, model: str, size: str,
                   negative_prompt: str = "", n: int = 1,
                   poll_timeout_sec: int = 600, poll_interval: int = 8) -> str:
    """POST /v1/images/generations
    返回第一个结果的 URL。

    已知问题（2026-08-01 实测）：这个 NewAPI（calciumion/new-api:latest）版本
    对图像任务只回传 {"status":"submitted","task_id":"..."}，没有直接的 url。
    我们尝试轮询相同路径（实际上 404），所以这里降级为报错提示用户。
    """
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
        timeout=180,
    )
    resp.raise_for_status()
    data = resp.json()

    # Case 1: 同步返回（带 url）
    if "data" in data and isinstance(data["data"], list) and data["data"]:
        first = data["data"][0]
        if isinstance(first, dict) and first.get("url"):
            logger.info("Generated image sync: %s", first["url"])
            return first["url"]

    # Case 2: 异步返回（只有 task_id）
    if "data" in data and data["data"] and "task_id" in data["data"][0]:
        task_id = data["data"][0]["task_id"]
        raise RuntimeError(
            f"图像生成返回异步任务 {task_id}，但当前 NewAPI 版本不支持图像任务轮询。\n"
            f"请让管理员：（1）升级 NewAPI 到支持图像任务轮询的版本，或\n"
            f"（2）给 token 加管理员权限查询 /api/task/{task_id}。"
        )

    raise RuntimeError(f"Unexpected image response: {data}")


def edit_image(image_bytes: bytes, prompt: str, model: str, size: str = "1024x1024",
               poll_timeout_sec: int = 600) -> str:
    """POST /v1/images/edits (multipart)
    image_bytes: 输入图片的 PNG/JPG 字节
    """
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
        timeout=180,
    )
    resp.raise_for_status()
    data = resp.json()

    if "data" in data and isinstance(data["data"], list) and data["data"]:
        first = data["data"][0]
        if isinstance(first, dict) and first.get("url"):
            logger.info("Edited image sync: %s", first["url"])
            return first["url"]
        if isinstance(first, dict) and first.get("task_id"):
            task_id = first["task_id"]
            raise RuntimeError(
                f"图生图返回异步任务 {task_id}，但当前 NewAPI 不支持图像任务轮询。"
            )

    raise RuntimeError(f"Unexpected edit image response: {data}")


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
            # 注意：同名 multipart part，requests 用 tuple 列表表达
            files.append(("input_reference", (filename, content, content_type)))
    else:
        # 占位：aicoming-video-proxy 要求至少一个文件
        files.append(("input_reference", ("_placeholder.png", _DUMMY_PNG_BYTES, "image/png")))
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
    """
    resp = requests.get(
        f"{NEWAPI_BASE_URL}/v1/videos/{task_id}",
        headers=_auth_headers(),
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def wait_for_video(task_id: str, timeout_sec: int = 600,
                   poll_interval: int = 8) -> dict:
    """轮询直到视频任务完成或超时。
    完成的返回：{"status": "completed", "metadata": {"url": "..."}}
    """
    start = time.time()
    last_status = None
    while time.time() - start < timeout_sec:
        result = poll_video(task_id)
        status = result.get("status", "unknown")
        if status != last_status:
            logger.info("Video task %s status: %s", task_id, status)
            last_status = status
        if status in ("completed", "succeeded", "success"):
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
    resp = requests.get(url, timeout=timeout)
    resp.raise_for_status()
    return resp.content


def image_url_to_tensor(url: str):
    """下载 URL 图片，转成 ComfyUI IMAGE tensor。"""
    import numpy as np
    from PIL import Image
    import io
    import torch

    raw = download_bytes(url)
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    arr = np.array(pil).astype(np.float32) / 255.0  # [H, W, C]
    tensor = torch.from_numpy(arr).unsqueeze(0)  # [1, H, W, C]
    return tensor


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