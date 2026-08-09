"""
JurongImageToImage — 图像生成图像

调用 NewAPI /v1/images/edits (multipart)
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
                    "tooltip": "描述要生成的图像内容"
                }),
            },
            "optional": {
                "size": (["1024x1024", "1024x1536", "1536x1024", "2048x2048"], {
                    "default": "1024x1024"
                }),
                "model": (["gpt-image-2-1k", "gpt-image-2-2k", "gpt-image-2-4k"], {
                    "default": "gpt-image-2-1k"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE",)
    RETURN_NAMES = ("image",)
    FUNCTION = "generate"
    CATEGORY = "Jurong/图像"

    def generate(self, image, prompt: str, size: str = "1024x1024",
                 model: str = "gpt-image-2-1k") -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        # ComfyUI IMAGE 是 [B, H, W, C] tensor，转 PNG 字节上传
        image_bytes = api_client.tensor_to_png_bytes(image)
        url = api_client.edit_image(
            image_bytes=image_bytes,
            prompt=prompt,
            model=model,
            size=size,
        )
        tensor = api_client.image_url_to_tensor(url)
        return (tensor,)