"""
JurongTextToVideo — 文本生成视频

调用 NewAPI /v1/videos (multipart)，只有 prompt

注意：
- 视频是异步任务（submit → poll → download）
- 同步阻塞直到完成（ComfyUI 节点都是同步的）
- 第一帧作为 IMAGE 返回供后续节点链用
- 完整视频文件保存到 ComfyUI/output/jurong_videos/
"""
import os

from . import api_client


class JurongTextToVideo:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述要生成的视频内容"
                }),
            },
            "optional": {
                "model": (["doubao-seedance-2.0"], {
                    "default": "doubao-seedance-2.0"
                }),
                "duration": (["4", "8"], {
                    "default": "4"
                }),
                "resolution": (["480P", "720P"], {
                    "default": "480P"
                }),
                "audio": ("AUDIO", {
                    "tooltip": "可选：参考音频（拖入音频文件）"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE", "STRING")
    RETURN_NAMES = ("first_frame", "video_path")
    FUNCTION = "generate"
    CATEGORY = "Jurong/视频"
    OUTPUT_NODE = True

    def generate(self, prompt: str,
                 model: str = "doubao-seedance-2.0",
                 duration: str = "4", resolution: str = "480P",
                 audio=None) -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        # 处理可选音频
        audio_file = None
        if audio is not None:
            # ComfyUI AUDIO 类型通常带 waveform + sample_rate
            # 我们这里仅取 waveform，保存为 wav
            waveform = audio.get("waveform") if isinstance(audio, dict) else None
            sample_rate = audio.get("sample_rate", 44100) if isinstance(audio, dict) else 44100
            if waveform is not None:
                wav_bytes = _tensor_to_wav_bytes(waveform, sample_rate)
                audio_file = ("reference.wav", wav_bytes, "audio/wav")

        # 1. 提交任务
        submit_result = api_client.submit_video(
            prompt=prompt,
            model=model,
            image_files=None,
            audio_file=audio_file,
            duration=int(duration),
            resolution=resolution,
        )
        task_id = submit_result.get("id") or submit_result.get("task_id")
        if not task_id:
            raise RuntimeError(f"Submit video failed: {submit_result}")

        # 2. 轮询等待完成
        poll_result = api_client.wait_for_video(task_id, timeout_sec=600)

        # 3. 提取视频 URL 并下载保存
        video_url = api_client.extract_video_url(poll_result)
        output_dir = os.environ.get("JURONG_VIDEO_OUTPUT_DIR", "/app/output/jurong_videos")
        video_path = api_client.save_video_file(video_url, output_dir, filename_prefix="jurong_t2v")

        # 4. 提取第一帧作为 IMAGE 返回（供后续节点链用）
        first_frame = _extract_first_frame(video_path)

        return (first_frame, video_path)


def _tensor_to_wav_bytes(waveform, sample_rate: int) -> bytes:
    """torch.Tensor waveform → WAV bytes."""
    import io
    import numpy as np
    import soundfile as sf

    # waveform: [channels, samples] tensor
    arr = waveform.cpu().numpy() if hasattr(waveform, 'cpu') else np.array(waveform)
    if arr.ndim == 1:
        arr = arr[np.newaxis, :]  # [1, samples]
    buf = io.BytesIO()
    sf.write(buf, arr.T, sample_rate, format='WAV', subtype='PCM_16')
    return buf.getvalue()


def _extract_first_frame(video_path: str):
    """从视频文件提取第一帧，转 ComfyUI IMAGE tensor。"""
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
        # frame: BGR → RGB
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    except ImportError:
        # 没装 opencv 时退化：用 ffmpeg 或 PIL 的第一帧
        raise RuntimeError(
            "需要 opencv-python 来提取视频首帧。"
            "请在 Dockerfile / requirements.txt 加 opencv-python。"
        )

    arr = rgb.astype(np.float32) / 255.0
    tensor = torch.from_numpy(arr).unsqueeze(0)  # [1, H, W, C]
    return tensor