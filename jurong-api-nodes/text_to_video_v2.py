"""
JurongTextToVideoV2 — 文本生成视频（增强版）

相比 v1 的改进：
- 自带 robust extract_video_url（兼容 6 种响应格式，不再依赖 api_client.extract_video_url）
- 完整诊断日志：dump 完整 poll 响应、submit 响应、所有错误详情
- 新增参数：seed、camera_control、negative_prompt、fps、aspect_ratio
- 失败自动重试（指数退避）：submit 阶段 2 次，poll 阶段按超时重试
- 假完成检测：status=completed 但无 URL 时再轮询 3 次再放弃
- IS_CHANGED：seed > 0 时按 seed 重跑，否则按 prompt+seed hash 决定是否重跑

调用链：节点 → NewAPI (jurong :3000) → aicoming.top
"""
import os
import json
import time
import hashlib
import logging

from . import api_client

logger = logging.getLogger("jurong_text_to_video_v2")


# ============================================================================
# 节点主类
# ============================================================================

class JurongTextToVideoV2:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述要生成的视频内容（镜头运动、光影、动作等）"
                }),
            },
            "optional": {
                "model": (["doubao-seedance-2.0"], {
                    "default": "doubao-seedance-2.0",
                    "tooltip": "视频生成模型"
                }),
                "duration": (["4", "8", "12"], {
                    "default": "4",
                    "tooltip": "视频时长（秒）"
                }),
                "resolution": (["480p", "720p", "1080p", "4k"], {
                    "default": "480p",
                    "tooltip": "视频分辨率（aicoming 只接受小写 p 格式，节点已自动规范化）"
                }),
                "seed": ("INT", {
                    "default": 0,
                    "min": 0,
                    "max": 2147483647,
                    "control_after_generate": True,
                    "tooltip": "随机种子（0 = 随机，>0 时相同 seed + prompt 复现结果）"
                }),
                "camera_control": ([
                    "none",
                    "pan_left", "pan_right",
                    "zoom_in", "zoom_out",
                    "tilt_up", "tilt_down",
                    "static",
                ], {
                    "default": "none",
                    "tooltip": "镜头运动控制"
                }),
                "aspect_ratio": (["16:9", "9:16", "1:1", "4:3"], {
                    "default": "16:9",
                    "tooltip": "视频宽高比"
                }),
                "fps": (["24", "30"], {
                    "default": "24",
                    "tooltip": "帧率"
                }),
                "negative_prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "反向提示词（描述不想要的元素）"
                }),
                "audio": ("AUDIO", {
                    "tooltip": "可选：参考音频"
                }),
                "max_retry": ("INT", {
                    "default": 2,
                    "min": 0,
                    "max": 5,
                    "tooltip": "假完成（completed 但无 URL）时重试次数"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE", "STRING")
    RETURN_NAMES = ("first_frame", "video_path")
    FUNCTION = "generate"
    CATEGORY = "Jurong/视频"
    OUTPUT_NODE = True

    @classmethod
    def IS_CHANGED(cls, **kwargs):
        """
        ComfyUI 用来判断节点是否需要重跑。
        - seed > 0: 按 seed 决定（同样的 seed 复用缓存）
        - seed == 0: 按 prompt 哈希决定（避免每次随机结果重新生成）
        """
        seed = kwargs.get("seed", 0)
        if seed and seed > 0:
            return float(seed)
        # seed=0 时，用 prompt + 模型参数 hash 决定
        cache_key = (
            kwargs.get("prompt", "")
            + kwargs.get("model", "")
            + kwargs.get("duration", "")
            + kwargs.get("resolution", "")
        )
        return float(int(hashlib.md5(cache_key.encode()).hexdigest()[:8], 16))

    def generate(self, prompt, model="doubao-seedance-2.0", duration="4",
                 resolution="480P", seed=0, camera_control="none",
                 aspect_ratio="16:9", fps="24", negative_prompt="",
                 audio=None, max_retry=2):
        # ---------------------------------------------------------------------
        # 0. 参数校验
        # ---------------------------------------------------------------------
        if not prompt or not prompt.strip():
            raise ValueError("prompt 不能为空")

        logger.info(
            "[V2] Submitting: model=%s, duration=%ss, resolution=%s, seed=%s, "
            "camera=%s, aspect=%s, fps=%s, prompt_len=%d",
            model, duration, resolution, seed,
            camera_control, aspect_ratio, fps, len(prompt),
        )

        # 组装附加参数（注入到 prompt 末尾，避免破坏 API 兼容）
        composed_prompt = prompt.strip()
        if negative_prompt.strip():
            composed_prompt += f"\n\nAvoid: {negative_prompt.strip()}"
        if camera_control != "none" and camera_control != "static":
            composed_prompt += f"\n\nCamera motion: {camera_control}"
        if aspect_ratio:
            composed_prompt += f"\n\nAspect ratio: {aspect_ratio}"

        # 处理音频
        audio_file = None
        if audio is not None:
            waveform = audio.get("waveform") if isinstance(audio, dict) else None
            sample_rate = audio.get("sample_rate", 44100) if isinstance(audio, dict) else 44100
            if waveform is not None:
                wav_bytes = _tensor_to_wav_bytes(waveform, sample_rate)
                audio_file = ("reference.wav", wav_bytes, "audio/wav")

        # ---------------------------------------------------------------------
        # 1. 提交任务（带重试）
        # ---------------------------------------------------------------------
        submit_result = _submit_with_retry(
            prompt=composed_prompt,
            model=model,
            duration=int(duration),
            resolution=resolution,
            audio_file=audio_file,
            retries=2,
        )
        task_id = submit_result.get("id") or submit_result.get("task_id")
        if not task_id:
            raise RuntimeError(
                f"Submit video failed, no task_id in response:\n"
                f"{json.dumps(submit_result, ensure_ascii=False, indent=2)}"
            )
        logger.info("[V2] Task submitted: %s", task_id)

        # ---------------------------------------------------------------------
        # 2. 轮询 + 提取 URL（容错版）
        # ---------------------------------------------------------------------
        video_url, poll_result = _wait_and_extract(
            task_id=task_id,
            timeout_sec=600,
            poll_interval=5,
            max_retry_after_completed=max_retry,
        )

        # ---------------------------------------------------------------------
        # 3. 下载保存
        # ---------------------------------------------------------------------
        output_dir = os.environ.get("JURONG_VIDEO_OUTPUT_DIR", "/app/output/jurong_videos")
        video_path = _save_video_file(video_url, output_dir, prefix="jurong_t2v_v2")
        logger.info("[V2] Saved video: %s", video_path)

        # ---------------------------------------------------------------------
        # 4. 提取第一帧
        # ---------------------------------------------------------------------
        first_frame = _extract_first_frame(video_path)

        return {
            "ui": {
                "task_id": [task_id],
                "video_path": [video_path],
                "video_url": [video_url],
                "seed": [str(seed)],
            },
            "result": (first_frame, video_path),
        }


# ============================================================================
# 工具函数
# ============================================================================

def _submit_with_retry(prompt, model, duration, resolution, audio_file, retries=2):
    """Submit 失败重试（指数退避）。"""
    last_err = None
    for attempt in range(retries + 1):
        try:
            return api_client.submit_video(
                prompt=prompt,
                model=model,
                image_files=None,
                audio_file=audio_file,
                duration=duration,
                resolution=resolution,
            )
        except Exception as e:
            last_err = e
            logger.warning("[V2] Submit attempt %d/%d failed: %s",
                           attempt + 1, retries + 1, e)
            if attempt < retries:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"Submit failed after {retries + 1} attempts: {last_err}")


def _wait_and_extract(task_id, timeout_sec=600, poll_interval=5, max_retry_after_completed=2):
    """
    轮询直到完成 + 提取 URL。
    如果 status=completed 但响应里没 URL（aicoming 假完成），再轮询 max_retry_after_completed 次。

    2026-08-07 修复:处理 NewAPI 服务端清理任务记录返回 404 的情况。
    - 之前拿到 status=completed 但被 404,尝试用缓存的响应再提一次 URL
    - 从未拿到 completed 就被 404,继续轮询(给 server 一段时间重新创建记录)
    """
    start = time.time()
    last_status = None
    completed_no_url_retries = 0
    last_result = None
    last_completed_result = None  # 缓存最后一次 status=completed 的响应

    while time.time() - start < timeout_sec:
        try:
            result = api_client.poll_video(task_id)
        except Exception as e:
            logger.warning("[V2] Poll failed: %s", e)
            time.sleep(poll_interval)
            continue

        # 服务端清理任务记录(404 哨兵)
        if isinstance(result, dict) and result.get("__jurong_404__"):
            if last_completed_result is not None:
                # 之前拿到过 completed,server 后清理了 — 用缓存响应再尝试提 URL
                logger.warning(
                    "[V2] Server cleaned up task %s (404) but we have last "
                    "completed result cached. Trying to extract URL...",
                    task_id,
                )
                video_url = _extract_video_url_robust(last_completed_result)
                if video_url:
                    return video_url, last_completed_result
                # 缓存也没 URL,继续轮询看 server 是否重建记录
            last_result = result
            time.sleep(poll_interval)
            continue

        last_result = result
        status = result.get("status", "unknown")

        if status != last_status:
            logger.info("[V2] Task %s status: %s", task_id, status)
            last_status = status

        # ---- 状态分支 ----
        if status in ("completed", "succeeded", "success"):
            last_completed_result = result  # 缓存
            # 先尝试提取 URL
            video_url = _extract_video_url_robust(result)
            if video_url:
                return video_url, result

            # 假完成：completed 但无 URL
            completed_no_url_retries += 1
            logger.warning(
                "[V2] status=completed but no URL found (retry %d/%d). "
                "Full response: %s",
                completed_no_url_retries, max_retry_after_completed,
                json.dumps(result, ensure_ascii=False),
            )
            if completed_no_url_retries >= max_retry_after_completed:
                raise RuntimeError(
                    f"aicoming 返回 status={status} 累计 {max_retry_after_completed + 1} 次 "
                    f"但响应里都没有 URL 字段。\n"
                    f"最后一次响应：\n"
                    f"{json.dumps(result, ensure_ascii=False, indent=2)}\n\n"
                    f"可能原因：\n"
                    f"1. aicoming 假完成（任务队列里没真正处理）\n"
                    f"2. URL 字段名不在我们的提取列表里（需扩展 _extract_video_url_robust）\n"
                    f"3. 需要单独调用 GET /v1/videos/{{id}}/content 拿 URL\n"
                    f"4. NewAPI 配置的 doubao-seedance-2.0 模型映射错了\n"
                )
            time.sleep(poll_interval * 2)  # 假完成时拉长间隔
            continue

        if status in ("failed", "error", "cancelled"):
            raise RuntimeError(
                f"Video task {task_id} failed:\n"
                f"{json.dumps(result, ensure_ascii=False, indent=2)}"
            )

        # in_progress / pending / 其他：继续轮询
        time.sleep(poll_interval)

    raise TimeoutError(
        f"Video task {task_id} did not complete in {timeout_sec}s. "
        f"Last response: {json.dumps(last_result, ensure_ascii=False)}"
    )


def _extract_video_url_robust(poll_result: dict) -> str:
    """
    从 poll 响应里抠出视频 URL —— 兼容 6+ 种格式。

    优先级：
    1. 显式 metadata.url（OpenAI Sora flat）
    2. result.metadata.url
    3. result.url
    4. 顶层 url
    5. output_url / video_url / download_url / file_url
    6. content[0].url（OpenAI content array）
    7. data[0].url（NewAPI 包装）
    """
    # 形态 1：{metadata: {url: "..."}}
    if isinstance(poll_result.get("metadata"), dict):
        url = poll_result["metadata"].get("url")
        if url:
            return url

    # 形态 2/3：{result: {metadata: {url: "..."}}} 或 {result: {url: "..."}}
    result_obj = poll_result.get("result")
    if isinstance(result_obj, dict):
        if isinstance(result_obj.get("metadata"), dict):
            url = result_obj["metadata"].get("url")
            if url:
                return url
        if "url" in result_obj:
            return result_obj["url"]

    # 形态 4：顶层 url
    if "url" in poll_result:
        return poll_result["url"]

    # 形态 5：各种别名
    for key in ("output_url", "video_url", "download_url", "file_url"):
        if key in poll_result:
            return poll_result[key]

    # 形态 6：content 数组（OpenAI Sora 多输出）
    content = poll_result.get("content")
    if isinstance(content, list) and content:
        first = content[0]
        if isinstance(first, dict) and first.get("url"):
            return first["url"]

    # 形态 7：data 数组（NewAPI 包装）
    data = poll_result.get("data")
    if isinstance(data, list) and data:
        first = data[0]
        if isinstance(first, dict):
            for key in ("url", "video_url", "output_url"):
                if key in first:
                    return first[key]

    # 都没找到 → 返回 None（让上层决定重试/报错）
    return None


def _save_video_file(video_url: str, output_dir: str, prefix: str = "jurong_t2v_v2") -> str:
    """下载视频，保存到 output 目录，返回最终路径。

    自带 download 实现，不依赖 api_client.download_bytes。
    原因：服务端旧版 api_client.py 缺这个函数，部署时单独更新 v2 后会 AttributeError。
    """
    from pathlib import Path
    import uuid
    import requests as _requests  # 局部 import，避免污染模块顶部

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    ext = "mp4" if (".mp4" in video_url.lower() or video_url.lower().endswith(".mp4")) else "bin"
    filename = f"{prefix}_{uuid.uuid4().hex[:8]}.{ext}"
    full_path = out_dir / filename

    # 自带下载 + 流式写大文件（避免一次加载到内存）
    logger.info("[V2] Downloading video from: %s", video_url)
    with _requests.get(video_url, timeout=300, stream=True) as resp:
        resp.raise_for_status()
        with open(full_path, "wb") as f:
            for chunk in resp.iter_content(chunk_size=1024 * 256):
                if chunk:
                    f.write(chunk)

    size = full_path.stat().st_size
    logger.info("[V2] Saved: %s (%d bytes)", full_path, size)
    return str(full_path)


def _extract_first_frame(video_path: str):
    """从视频提取第一帧 → ComfyUI IMAGE tensor。"""
    import numpy as np
    from PIL import Image
    import torch

    try:
        import cv2
        cap = cv2.VideoCapture(video_path)
        ret, frame = cap.read()
        cap.release()
        if not ret:
            raise RuntimeError(f"Failed to read first frame from {video_path}")
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    except ImportError:
        raise RuntimeError(
            "需要 opencv-python 来提取视频首帧。"
            "请在 Dockerfile / requirements.txt 加 opencv-python。"
        )

    arr = rgb.astype(np.float32) / 255.0
    tensor = torch.from_numpy(arr).unsqueeze(0)  # [1, H, W, C]
    return tensor


def _tensor_to_wav_bytes(waveform, sample_rate: int) -> bytes:
    """torch.Tensor waveform → WAV bytes."""
    import io
    import numpy as np
    import soundfile as sf

    arr = waveform.cpu().numpy() if hasattr(waveform, "cpu") else np.array(waveform)
    if arr.ndim == 1:
        arr = arr[np.newaxis, :]
    buf = io.BytesIO()
    sf.write(buf, arr.T, sample_rate, format="WAV", subtype="PCM_16")
    return buf.getvalue()