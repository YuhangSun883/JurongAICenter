"""
节点积分定价（v1 配置）

v1 不强制扣费（Phase 8 才接），这里先定义好常量和计算函数，
方便以后 Spring 端调用 / 也方便测试时算预期消耗。
"""

PRICING = {
    "JurongTextToImage": {
        "gpt-image-2-1k": 1,
        "gpt-image-2-2k": 4,
        "gpt-image-2-4k",
            "gpt-5.4-mini": 16,
    },
    "JurongImageToImage": {
        "gpt-image-2-1k": 2,
        "gpt-image-2-2k": 6,
        "gpt-image-2-4k": 24,
    },
    "JurongTextToVideo": {
        "doubao-seedance-2.0_4s_480P": 8,
        "doubao-seedance-2.0_4s_720P": 16,
        "doubao-seedance-2.0_8s_480P": 16,
        "doubao-seedance-2.0_8s_720P": 32,
    },
    "JurongTextToVideoV2": {
        # 跟 v1 同价；v2 多了 retry/robust 逻辑，但 quota 成本相同
        "doubao-seedance-2.0_4s_480P": 8,
        "doubao-seedance-2.0_4s_720P": 16,
        "doubao-seedance-2.0_8s_480P": 16,
        "doubao-seedance-2.0_8s_720P": 32,
    },
    "JurongImageToVideo": {
        "doubao-seedance-2.0_4s_480P": 5,
        "doubao-seedance-2.0_4s_720P": 10,
        "doubao-seedance-2.0_8s_480P": 10,
    },
    "JurongMultiImageToVideo": {
        "doubao-seedance-2.0_4s_480P": 8,
        "doubao-seedance-2.0_4s_720P": 16,
    },
    "audio_extra": 2,  # 叠加在视频基础价上
}


def calc_image_cost(model: str) -> int:
    """根据模型返回图像生成积分。"""
    if model not in PRICING["JurongTextToImage"]:
        return 1
    return PRICING["JurongTextToImage"][model]


def calc_video_cost(node_class: str, model: str, duration: int, resolution: str, with_audio: bool = False) -> int:
    """根据规格返回视频生成积分。

    Args:
        node_class: 节点类名 (JurongTextToVideo / JurongTextToVideoV2 / JurongImageToVideo / JurongMultiImageToVideo)
    """
    key = f"{model}_{duration}s_{resolution}"
    base = PRICING.get(node_class, PRICING["JurongTextToVideo"]).get(key, 8)
    if with_audio:
        base += PRICING["audio_extra"]
    return base