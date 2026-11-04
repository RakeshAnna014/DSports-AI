"""Log text is untrusted data. One rule, used everywhere log text leaves the store (evidence rows, cluster text, trace chains, LLM prompts)."""
from __future__ import annotations

from app.guard.untrusted import sanitize_untrusted

WITHHELD = "[log message withheld: it contains instruction-like text]"


def safe_text(text: str | None, limit: int) -> tuple[str | None, bool]:
    """If ANY instruction-like pattern is found the whole message is withheld (the shared sanitiser would leave the rest of an injected message
    behind); only its metadata (id, time, service, level) is shown. Returns (text, flagged)."""
    if text is None:
        return None, False
    clean, removed = sanitize_untrusted(text, limit)
    return (WITHHELD, True) if removed else (clean, False)
