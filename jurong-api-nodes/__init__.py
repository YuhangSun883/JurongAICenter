"""
Jurong API Nodes for ComfyUI
5 个节点封装 NewAPI（jurong 自家中转站）的接口调用

品牌名：jurong（这是我们的中转站，不是上游 aicoming）
调用链：ComfyUI 节点 → NewAPI (jurong) → aicoming.top
"""

from .text_to_image import JurongTextToImage
from .image_to_image import JurongImageToImage
from .text_to_video import JurongTextToVideo
from .text_to_video_v2 import JurongTextToVideoV2
from .image_to_video import JurongImageToVideo
from .multi_image_to_video import JurongMultiImageToVideo

NODE_CLASS_MAPPINGS = {
    "JurongTextToImage": JurongTextToImage,
    "JurongImageToImage": JurongImageToImage,
    "JurongTextToVideo": JurongTextToVideo,
    "JurongTextToVideoV2": JurongTextToVideoV2,
    "JurongImageToVideo": JurongImageToVideo,
    "JurongMultiImageToVideo": JurongMultiImageToVideo,
}

# ComfyUI 会通过 NODE_DISPLAY_NAME_MAPPINGS 覆盖 UI 显示名（可选）
NODE_DISPLAY_NAME_MAPPINGS = {
    "JurongTextToImage": "Jurong 文本生成图像",
    "JurongImageToImage": "Jurong 图像生成图像",
    "JurongTextToVideo": "Jurong 文本生成视频",
    "JurongTextToVideoV2": "Jurong 文本生成视频 v2",
    "JurongImageToVideo": "Jurong 图像生成视频",
    "JurongMultiImageToVideo": "Jurong 多图生成视频",
}

__all__ = ["NODE_CLASS_MAPPINGS", "NODE_DISPLAY_NAME_MAPPINGS"]