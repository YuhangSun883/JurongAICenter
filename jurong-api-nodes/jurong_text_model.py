"""
JurongTextModel — 文本生成节点

调用 NewAPI /v1/chat/completions（标准 OpenAI 形态）
输出 文本 + usage

典型用法
========
- 把用户简单描述扩写为详细图像 prompt，接入 JurongTextToImage.prompt
- 多轮 prompt 工程（用 system_prompt 设定角色/格式）
- 让模型先做意图分析/分类，再发到下游节点

默认用 gpt-5.5
===============
- aicoming 上游稳定，返回快（实测 1.9s / 1k token）
- 角色遵循稳定，中文不错，足够把短中文 prompt 扩写到 ~150 字英文 prompt
- 备胎：gpt-5.4-mini（更便宜）/ MiniMax-M2.1（自家，最快）/ kimi-k3（长上下文）
"""
import json

from . import api_client


class JurongTextModel:
    # 1 个节点，挑过的稳定模型清单（不全暴露 36 个，避免下拉框爆炸）
    # 上游某个模型挂掉时，最简单的修法是改这里 + 重启 ComfyUI
    # 2026-08-02 重排：移除 claude-haiku-4-5（aicoming 上游返 405），
    # 加上用户提交的 23 个模型（顺序原样保留）
    DEFAULT_MODELS = [
        "claude-fable-5",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-sonnet-5",
        "claude-opus-4-8",
        "claude-opus-4-7",
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "gemini-3.1-pro-high",
        "gemini-3.1-pro-preview",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-antigravity",
        "gpt-5.4-mini",
        "gpt-5.6-terra",
        "gpt-5.4",
        "gpt-5.6-sol",
        "gpt-5.5",                  # default
        "gpt-5.6-luna",
        "glm-5.2",
        "glm-5.1",
        "kimi-k2.6",
        "kimi-k3",
        "grok-4.5",
        "MiniMax-M2.7-highspeed",  # 自家高速版（要补）
        "MiniMax-M3",              # 自家旗舰
    ]

    @classmethod
    def INPUT_TYPES(cls):
        return {
            "required": {
                "prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "用户消息"
                }),
            },
            "optional": {
                "model": (cls.DEFAULT_MODELS, {
                    "default": "gpt-5.5"
                }),
                "system_prompt": ("STRING", {
                    "multiline": True,
                    "default": "",
                    "tooltip": "可选：系统提示词，设定 LLM 角色/格式/约束"
                }),
                "temperature": ("FLOAT", {
                    "default": 1.0, "min": 0.0, "max": 2.0, "step": 0.05,
                    "tooltip": "采样温度；高=更发散，低=更确定"
                }),
                "max_tokens": ("INT", {
                    "default": 1024, "min": 16, "max": 8192, "step": 32,
                    "tooltip": "最大输出 token 数"
                }),
            }
        }

    RETURN_TYPES = ("STRING", "STRING")
    RETURN_NAMES = ("text", "usage_json")
    FUNCTION = "generate"
    CATEGORY = "Jurong/文本"
    OUTPUT_NODE = True  # ComfyUI 要求工作流有至少一个 OUTPUT_NODE

    def generate(self, prompt: str,
                 model: str = "gpt-5.5",
                 system_prompt: str = "",
                 temperature: float = 1.0,
                 max_tokens: int = 1024) -> tuple:
        if not prompt.strip():
            raise ValueError("prompt 不能为空")

        messages = []
        if system_prompt.strip():
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        # temperature=0 时 NewAPI 偶尔报 type 错误，按 0 处理
        t = float(temperature)
        if t <= 0:
            t = 0.01

        resp = api_client.chat_completion(
            messages=messages,
            model=model,
            temperature=t,
            max_tokens=int(max_tokens),
            timeout=180,
        )
        text = api_client.extract_chat_text(resp)
        usage = api_client.extract_chat_usage(resp)
        return (text, json.dumps(usage, ensure_ascii=False, indent=2))
