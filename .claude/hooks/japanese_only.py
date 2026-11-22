#!/usr/bin/env python3
"""Stop hook: the user reads Japanese only (CLAUDE.md). If the reply just written is mostly English,
block the stop and have it rewritten in Japanese before the user has to point it out."""
import json
import re
import sys

JA = re.compile(r"[぀-ヿ㐀-鿿ｦ-ﾟ]")
LATIN = re.compile(r"[A-Za-z]")


def last_reply(path: str) -> str:
    texts: list[str] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except ValueError:
                continue
            msg = e.get("message") or {}
            if e.get("type") == "user":
                c = msg.get("content")
                # a real user message (not a tool result) starts a new turn
                if isinstance(c, str) or (isinstance(c, list) and any(b.get("type") == "text" for b in c if isinstance(b, dict))):
                    texts = []
            elif e.get("type") == "assistant":
                for b in msg.get("content") or []:
                    if isinstance(b, dict) and b.get("type") == "text":
                        texts.append(b.get("text", ""))
    return "\n".join(texts)


def main() -> None:
    data = json.load(sys.stdin)
    if data.get("stop_hook_active"):
        return  # already asked once in this turn
    text = last_reply(data.get("transcript_path", ""))
    # ignore code blocks, inline code and URLs: they are English by nature
    prose = re.sub(r"```.*?```", "", text, flags=re.S)
    prose = re.sub(r"`[^`]*`|https?://\S+", "", prose)
    ja, latin = len(JA.findall(prose)), len(LATIN.findall(prose))
    if latin >= 80 and ja < latin * 0.25:
        print(json.dumps({"decision": "block", "reason":
            "直前の返事が英語になっています。ユーザーは日本語しか読みません（CLAUDE.md）。"
            "同じ内容を、最初から最後まで日本語で書き直して出し直してください。英語だったことのお詫びは一言で。"}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception:  # never break the session because of this check
        pass
