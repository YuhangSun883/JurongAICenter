"""
JurongImageToVideo — 图像生成视频

调用 NewAPI /v1/videos (multipart)，带 input_reference
"""
import os

from . import api_client
from .text_to_video import _extract_first_frame


class JurongImageToVideo:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image": ("IMAGE", {
                    "tooltip": "输入图像作为视频的首帧"
                }),
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述视频内容（镜头运动、光影、动作等）"
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
                    "tooltip": "可选：参考音频"
                }),
                "enable_prompt_optimize": ("BOOLEAN", {
                    "default": True,
                    "tooltip": "True=自动在 prompt 末尾追加保首帧引导（保持构图/主体/色调/风格）；False=原样传"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE", "STRING")
    RETURN_NAMES = ("first_frame", "video_path")
    FUNCTION = "generate"
    CATEGORY = "Jurong/视频"
    OUTPUT_NODE = True

    def generate(self, image, prompt: str,
                 model: str = "doubao-seedance-2.0",
                 duration: str = "4", resolution: str = "480P",
                 audio=None,
                 enable_prompt_optimize: bool = True) -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        # 0. 智能优化 prompt：保持首帧主体/构图/色调/风格
        if enable_prompt_optimize:
            prompt = self._enhance_prompt(prompt)

        # 1. 把 IMAGE tensor 转 PNG 字节，作为 input_reference 上传
        image_bytes = api_client.tensor_to_png_bytes(image)
        image_files = [("input_reference.png", image_bytes, "image/png")]

        # 2. 可选音频
        audio_file = None
        if audio is not None:
            waveform = audio.get("waveform") if isinstance(audio, dict) else None
            sample_rate = audio.get("sample_rate", 44100) if isinstance(audio, dict) else 44100
            if waveform is not None:
                from .text_to_video import _tensor_to_wav_bytes
                wav_bytes = _tensor_to_wav_bytes(waveform, sample_rate)
                audio_file = ("reference.wav", wav_bytes, "audio/wav")

        # 3. 提交任务（multipart，input_reference + input_audio + body.prompt）
        submit_result = api_client.submit_video(
            prompt=prompt,
            model=model,
            image_files=image_files,
            audio_file=audio_file,
            duration=int(duration),
            resolution=resolution,
        )
        task_id = submit_result.get("id") or submit_result.get("task_id")
        if not task_id:
            raise RuntimeError(f"Submit video failed: {submit_result}")

        # 4. 轮询等待完成
        poll_result = api_client.wait_for_video(task_id, timeout_sec=600)

        # 5. 下载保存
        video_url = api_client.extract_video_url(poll_result)
        output_dir = os.environ.get("JURONG_VIDEO_OUTPUT_DIR", "/app/output/jurong_videos")
        video_path = api_client.save_video_file(video_url, output_dir, filename_prefix="jurong_i2v")

        # 6. 第一帧作为 IMAGE 返回
        first_frame = _extract_first_frame(video_path)
        return {
            "ui": {
                "newapi_task_id": [task_id],
                "video_path": [video_path],
            },
            "result": (first_frame, video_path),
        }

    @staticmethod
    def _enhance_prompt(prompt: str) -> str:
        """智能优化 prompt，强制锁定参考图主体。

        关键约束：模型必须复刻参考图里的人物/物体外观，
        禁止改变性别/年龄/服装/发型/体型/肤色，
        只动画作和镜头运动。
        """
        lower = prompt.lower()
        existing_keywords = [
            "same as reference", "保持原图", "保持", "preserve",
            "consistent", "exact same", "identical",
            "same person", "same face", "maintain",
            "锁定", "不要改变", "do not change",
        ]
        if any(k in lower for k in existing_keywords):
            return prompt

        enhancer = (
            "CRITICAL: The subject(s) shown in the reference image MUST appear "
            "EXACTLY as in the reference — same face, same gender, same age, "
            "same hairstyle and hair color, same clothing, same body type, "
            "same skin tone, same accessories. Do NOT replace, swap, gender-swap, "
            "or alter the subject's identity in any way. "
            "Only animate the actions, expressions, and camera movement described above. "
            "Preserve the exact composition, color palette, lighting, and visual style "
            "of the reference image throughout the entire video. "
            "Lock the first frame as the visual anchor."
        )
        return f"{prompt.rstrip('. ')}. {enhancer}"