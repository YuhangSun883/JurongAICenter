"""
JurongMultiImageToImage — 多图生成图像

调用 NewAPI /v1/images/edits (multipart)，多张 input_reference
参考 multi_image_to_video.py 的模式：image_1 必填，image_2~4 可选。
"""
from . import api_client


class JurongMultiImageToImage:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image_1": ("IMAGE", {
                    "tooltip": "参考图 1（必填）"
                }),
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述要怎么合成这些图（必填）"
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
                "size": (["1024x1024", "1024x1536", "1536x1024", "2048x2048"], {
                    "default": "1024x1024"
                }),
                "model": (["gpt-image-2-1k", "gpt-image-2-2k", "gpt-image-2-4k",
                           "gpt-5.4-mini"], {
                    "default": "gpt-image-2-1k"
                }),
                "quality": (["low", "medium", "high"], {
                    "default": "low"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE",)
    RETURN_NAMES = ("image",)
    FUNCTION = "generate"
    CATEGORY = "Jurong/图像"

    def generate(self, image_1, prompt: str,
                 image_2=None, image_3=None, image_4=None,
                 size: str = "1024x1024",
                 model: str = "gpt-image-2-1k",
                 quality: str = "low") -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        # 1. 收集所有非空图片
        input_images = [img for img in [image_1, image_2, image_3, image_4]
                        if img is not None]
        if not input_images:
            raise ValueError("至少需要 1 张参考图")

        # 2. 转换为 PNG bytes 列表
        image_bytes_list = [api_client.tensor_to_png_bytes(t) for t in input_images]

        # 3. 提交
        raw = api_client.edit_image(
            image_bytes=image_bytes_list,  # list[bytes] 走多图分支
            prompt=prompt,
            model=model,
            size=size,
            quality=quality,
        )

        # 4. 结果转 tensor
        tensor = api_client.image_bytes_to_tensor(raw)
        return (tensor,)
