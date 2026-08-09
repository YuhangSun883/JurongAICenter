"""
JurongImageToImage — 图像生成图像

调用 NewAPI /v1/images/edits (multipart)
输出 ComfyUI IMAGE tensor
"""
from . import api_client


class JurongImageToImage:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "image": ("IMAGE",),
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述要怎么改这张图"
                }),
            },
            "optional": {
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

    def generate(self, image, prompt: str, size: str = "1024x1024",
                 model: str = "gpt-image-2-1k",
                 quality: str = "low") -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        image_bytes = api_client.tensor_to_png_bytes(image)
        raw = api_client.edit_image(
            image_bytes=image_bytes,
            prompt=prompt,
            model=model,
            size=size,
            quality=quality,
        )
        tensor = api_client.image_bytes_to_tensor(raw)
        return (tensor,)
