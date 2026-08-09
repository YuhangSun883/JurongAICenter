"""
JurongTextToImage — 文本生成图像

调用 NewAPI /v1/images/generations
返回 ComfyUI IMAGE tensor
"""
from . import api_client


class JurongTextToImage:
    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "描述要生成的图像内容"
                }),
                "size": (["1024x1024", "1024x1536", "1536x1024", "2048x2048"], {
                    "default": "1024x1024"
                }),
            },
            "optional": {
                "model": (["gpt-image-2-1k", "gpt-image-2-2k", "gpt-image-2-4k"], {
                    "default": "gpt-image-2-1k"
                }),
                "negative_prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "反向提示词"
                }),
            }
        }

    RETURN_TYPES = ("IMAGE",)
    RETURN_NAMES = ("image",)
    FUNCTION = "generate"
    CATEGORY = "Jurong/图像"

    def generate(self, prompt: str, size: str = "1024x1024",
                 model: str = "gpt-image-2-1k", negative_prompt: str = "") -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        url = api_client.generate_image(
            prompt=prompt,
            model=model,
            size=size,
            negative_prompt=negative_prompt,
        )
        tensor = api_client.image_url_to_tensor(url)
        return (tensor,)