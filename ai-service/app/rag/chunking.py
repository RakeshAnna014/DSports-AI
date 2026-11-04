"""Heading-aware recursive chunking (~450 tokens, ~60 overlap by default).

Algorithm
  1. Split the text into UNITS at markdown headings and blank lines; every unit remembers its heading path
     ("Care > Cleaning").
  2. A unit larger than the target is split recursively: sentences, then words.
  3. Units are packed greedily into chunks up to the target size. A chunk may hold several small sections (the heading
     text stays inline so the section is still recognisable) but never grows past the target.
  4. When a chunk closes, the last ~overlap tokens are repeated at the start of the next one so an answer that straddles
     a boundary is retrievable from either side.

Tokens are estimated as ceil(chars / 4), the usual rule of thumb for English with Gemini tokenizers. It is an estimate,
not the model's tokenizer; the target is a size, not a hard model limit.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass

_HEADING = re.compile(r"^(#{1,6})\s+(.*\S)\s*$")
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(\[])")


def count_tokens(text: str) -> int:
    return math.ceil(len(text) / 4) if text else 0


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    section: str | None
    tokens: int


@dataclass(frozen=True)
class _Unit:
    text: str
    path: str | None
    heading: bool = False


def _units(text: str) -> list[_Unit]:
    units: list[_Unit] = []
    path: list[tuple[int, str]] = []
    paragraph: list[str] = []

    def current() -> str | None:
        return " > ".join(t for _, t in path) or None

    def flush() -> None:
        if paragraph:
            units.append(_Unit("\n".join(paragraph).strip(), current()))
            paragraph.clear()

    for line in text.replace("\r\n", "\n").split("\n"):
        m = _HEADING.match(line)
        if m:
            flush()
            level = len(m.group(1))
            while path and path[-1][0] >= level:
                path.pop()
            path.append((level, m.group(2).strip()))
            units.append(_Unit(line.strip(), current(), heading=True))
        elif not line.strip():
            flush()
        else:
            paragraph.append(line.rstrip())
    flush()
    return [u for u in units if u.text]


def _split_oversized(unit: _Unit, target: int) -> list[_Unit]:
    if count_tokens(unit.text) <= target:
        return [unit]
    pieces: list[str] = []
    buf = ""
    for sentence in _SENTENCE_SPLIT.split(unit.text):
        if count_tokens(sentence) > target:  # a single enormous sentence: fall back to words
            if buf:
                pieces.append(buf); buf = ""
            words, cur = sentence.split(), ""
            for w in words:
                if count_tokens(cur + " " + w) > target and cur:
                    pieces.append(cur); cur = w
                else:
                    cur = f"{cur} {w}".strip()
            if cur:
                pieces.append(cur)
            continue
        if buf and count_tokens(buf + " " + sentence) > target:
            pieces.append(buf); buf = sentence
        else:
            buf = f"{buf} {sentence}".strip()
    if buf:
        pieces.append(buf)
    return [_Unit(p, unit.path) for p in pieces]


def _tail(text: str, tokens: int) -> str:
    """The last ~`tokens` tokens of `text`, cut on a word boundary."""
    if tokens <= 0:
        return ""
    chars = tokens * 4
    if len(text) <= chars:
        return text
    cut = text[-chars:]
    space = cut.find(" ")
    return cut[space + 1:] if space != -1 else cut


def chunk_text(text: str, target_tokens: int = 450, overlap_tokens: int = 60) -> list[Chunk]:
    if overlap_tokens >= target_tokens:
        raise ValueError("overlap must be smaller than the chunk size")
    units: list[_Unit] = []
    for u in _units(text):
        units.extend(_split_oversized(u, target_tokens - overlap_tokens))

    chunks: list[Chunk] = []
    parts: list[str] = []
    first_path: str | None = None
    size = 0
    carried = ""

    def close() -> None:
        nonlocal parts, size, carried, first_path
        body = "\n\n".join(parts).strip()
        if body:
            chunks.append(Chunk(len(chunks), body, first_path, count_tokens(body)))
            carried = _tail(body, overlap_tokens)
        parts, size, first_path = [], 0, None

    for unit in units:
        tokens = count_tokens(unit.text)
        if parts and size + tokens > target_tokens:
            close()
        if not parts and carried:
            parts.append(carried)
            size = count_tokens(carried)
        if first_path is None:
            first_path = unit.path
        parts.append(unit.text)
        size += tokens
    close()
    # A trailing chunk that is only the carried-over overlap adds nothing new.
    if len(chunks) > 1 and chunks[-1].text.strip() in chunks[-2].text:
        chunks.pop()
    return chunks
