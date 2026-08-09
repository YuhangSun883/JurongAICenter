"""
JurongMultiImageToVideo — 多图生成视频

调用 NewAPI /v1/videos (multipart)，多个 input_reference
"""
import os

from . import api_client
from .text_to_video import _extract_first_frame


class JurongMultiImageToVideo:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image_1": ("IMAGE", {
                    "tooltip": "参考图 1"
                }),
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述视频内容"
                }),
            },
            "optional": {
                "image_2": ("IMAGE", {
                    "tooltip": "参考图 2（可选）"
                }),
                "image_3": ("IMAGE", {
                    "tooltip": "参考图 3（可选）"
                }),
                "image_4": ("IMAGE", {
                    "tooltip": "参考图 4（可选）"
                }),
                "model": (["doubao-seedance-2.0"], {
                    "default": "doubao-seedance-2.0"
                }),
                "duration": (["4"], {
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

    def generate(self, image_1, prompt: str,
                 image_2=None, image_3=None, image_4=None,
                 model: str = "doubao-seedance-2.0",
                 duration: str = "4", resolution: str = "480P",
                 audio=None,
                 enable_prompt_optimize: bool = True) -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        # 0. 智能优化 prompt：保持首帧主体/构图/色调/风格
        if enable_prompt_optimize:
            prompt = self._enhance_prompt(prompt)

        # 1. 收集所有非空图片
        input_images = [img for img in [image_1, image_2, image_3, image_4]
                        if img is not None]
        if not input_images:
            raise ValueError("至少需要 1 张参考图")

        # 2. 转换为 multipart files（每张图一个 input_reference）
        image_files = []
        for idx, tensor in enumerate(input_images, start=1):
            png_bytes = api_client.tensor_to_png_bytes(tensor)
            image_files.append((f"ref_{idx}.png", png_bytes, "image/png"))

        # 3. 可选音频
        audio_file = None
        if audio is not None:
            waveform = audio.get("waveform") if isinstance(audio, dict) else None
            sample_rate = audio.get("sample_rate", 44100) if isinstance(audio, dict) else 44100
            if waveform is not None:
                from .text_to_video import _tensor_to_wav_bytes
                wav_bytes = _tensor_to_wav_bytes(waveform, sample_rate)
                audio_file = ("reference.wav", wav_bytes, "audio/wav")

        # 4. 提交
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

        # 5. 轮询
        poll_result = api_client.wait_for_video(task_id, timeout_sec=600)

        # 6. 保存 + 首帧
        video_url = api_client.extract_video_url(poll_result)
        output_dir = os.environ.get("JURONG_VIDEO_OUTPUT_DIR", "/app/output/jurong_videos")
        video_path = api_client.save_video_file(video_url, output_dir, filename_prefix="jurong_mi2v")
        first_frame = _extract_first_frame(video_path)
        return (first_frame, video_path)

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
            "CRITICAL: The subject(s) shown in the reference images MUST appear "
            "EXACTLY as in the references — same face, same gender, same age, "
            "same hairstyle and hair color, same clothing, same body type, "
            "same skin tone, same accessories. Do NOT replace, swap, gender-swap, "
            "or alter the subject's identity in any way. "
            "Only animate the actions, expressions, and camera movement described above. "
            "Preserve the exact composition, color palette, lighting, and visual style "
            "of the reference images throughout the entire video. "
            "Lock the first frame as the visual anchor."
        )
        return f"{prompt.rstrip('. ')}. {enhancer}"