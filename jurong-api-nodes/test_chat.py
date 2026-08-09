"""
test_chat.py
============
新中转站 — 文字生成测试（chat completions，独立可运行）

调用什么
--------
GET  /v1/models
POST /v1/chat/completions   {model, messages:[...]}

使用
----
python test_chat.py                      # 列模型 + 默认选第一个 + 发 "你好"
python test_chat.py --model MiniMax-M3   # 指定模型
python test_chat.py --prompt "1+1=?"     # 自定义提问
"""
import argparse
import json
import os
import sys
import time

import requests

BASE_URL = "http://192.140.163.161:3000/v1"


def get_token() -> str:
    """默认控制台直接输入；stdin 不可用（EOFError）时退回 $NEWAPI_TOKEN。"""
    tk = ""
    try:
        tk = input("请输入 API Key (sk-...): ").strip()
    except EOFError:
        tk = os.environ.get("NEWAPI_TOKEN", "").strip()
    if not tk:
        print("ERROR: 没拿到 token", file=sys.stderr)
        sys.exit(2)
    return tk


def list_models(headers):
    r = requests.get(f"{BASE_URL}/models", headers=headers, timeout=30)
    r.raise_for_status()
    return [m["id"] for m in r.json().get("data", [])]


def chat(headers, model, prompt, max_retries=3):
    """发一条 chat completion；遇 5xx 自动重试。"""
    payload = {"model": model, "messages": [{"role": "user", "content": prompt}]}
    last_err = None
    for attempt in range(1, max_retries + 1):
        t0 = time.time()
        r = requests.post(
            f"{BASE_URL}/chat/completions",
            headers=headers,
            json=payload,
            timeout=60,
        )
        elapsed = time.time() - t0
        print(f"\n[POST {BASE_URL}/chat/completions  attempt {attempt}/{max_retries}]", flush=True)
        print(f"Body: {json.dumps(payload, ensure_ascii=False)}", flush=True)
        print(f"  -> HTTP {r.status_code}  {elapsed:.1f}s", flush=True)
        if r.status_code == 200:
            return r.json(), r.status_code, elapsed
        last_err = f"HTTP {r.status_code}: {r.text[:200]}"
        if r.status_code < 500:
            break
        time.sleep(2)
    return None, last_err


def main():
    p = argparse.ArgumentParser(description="新中转站 文字生成测试")
    p.add_argument("--model", default=None, help="模型名（默认从列表选第一个）")
    p.add_argument("--prompt", default="你好", help="提问内容")
    p.add_argument("--base-url", default=BASE_URL)
    args = p.parse_args()

    api_key = get_token()
    headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

    print(f"\n=== GET {args.base_url}/models ===", flush=True)
    models = list_models(headers)
    print(f"可用模型: {len(models)} 个", flush=True)
    for i, m in enumerate(models[:10]):
        print(f"  {i+1}. {m}", flush=True)
    if len(models) > 10:
        print(f"  ... +{len(models)-10} 更多", flush=True)

    model = args.model
    if not model:
        try:
            model = input("\n请输入模型名称（或直接回车使用第一个）: ").strip()
        except EOFError:
            model = ""
        model = model or models[0]
    if model not in models:
        print(f"WARN: '{model}' 不在可见列表里，仍然尝试发送", flush=True)

    print(f"\n使用模型: {model}", flush=True)
    print(f"发送消息: {args.prompt!r}", flush=True)

    out, status, elapsed = chat(headers, model, args.prompt)
    if isinstance(out, dict):
        reply = out["choices"][0]["message"]["content"]
        usage = out.get("usage", {})
        print(f"\n=== RESPONSE ===", flush=True)
        print(json.dumps(out, ensure_ascii=False, indent=2), flush=True)
        print(f"\n助手回复: {reply}", flush=True)
        print(f"usage: {usage}", flush=True)
        print("\n✓ 测试成功！模型接口正常。", flush=True)
        return 0
    else:
        print(f"\n✗ 失败: {status}", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())