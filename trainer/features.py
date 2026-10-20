"""Tokenisation and features — must stay identical to workbench.model.Features (Kotlin).

`parity.json` (written by `make_parity.py`) is checked by a Kotlin test, so a change on one side
without the other fails CI.
"""

from __future__ import annotations

import re

VERSION = 1
TOKEN = re.compile(r"[A-Za-zÄÖÜäöüß0-9]+(?:[.,/][0-9]+)*|\[[A-ZÄÖÜ]+]|\S")


def tokens(text: str) -> list[tuple[str, int, int]]:
    return [(m.group(0), m.start(), m.end()) for m in TOKEN.finditer(text)]


def shape(w: str) -> str:
    out: list[str] = []
    for c in w:
        s = "X" if c.isupper() else "x" if c.islower() else "d" if c.isdigit() else c
        if not out or out[-1] != s:
            out.append(s)
    return "".join(out)


def features(toks: list[tuple[str, int, int]], i: int) -> list[str]:
    w = toks[i][0].lower()
    prev = toks[i - 1][0].lower() if i > 0 else "<s>"
    nxt = toks[i + 1][0].lower() if i + 1 < len(toks) else "</s>"
    return ["b", f"w={w}", f"s3={w[-3:]}", f"p3={w[:3]}", f"shape={shape(toks[i][0])}", f"pw={prev}", f"nw={nxt}", f"pw|w={prev}|{w}"]


def fnv1a(s: str, buckets: int) -> int:
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h % buckets


def bio(text: str, entities: list[dict]) -> list[str]:
    """BIO tag per token: a token belongs to the span containing its first character; the first
    such token of a span is B-, the rest I-."""
    tags, started = [], set()
    for _, start, _ in tokens(text):
        tag = "O"
        for k, e in enumerate(entities):
            if e["start"] <= start < e["end"]:
                tag = ("I-" if k in started else "B-") + e["label"]
                started.add(k)
                break
        tags.append(tag)
    return tags


def spans(toks: list[tuple[str, int, int]], tags: list[str]) -> list[dict]:
    """BIO to spans; an I- tag without a matching B- starts a new span (same rule as Kotlin)."""
    out, cur = [], None
    for (_, s, e), tag in zip(toks, tags):
        typ = tag.split("-", 1)[1] if "-" in tag else ""
        if tag.startswith("I-") and cur and cur[2] == typ:
            cur = (cur[0], e, typ)
            continue
        if cur:
            out.append({"start": cur[0], "end": cur[1], "label": cur[2]})
        cur = None if tag == "O" else (s, e, typ)
    if cur:
        out.append({"start": cur[0], "end": cur[1], "label": cur[2]})
    return out
