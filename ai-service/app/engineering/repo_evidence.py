"""Evidence from the repository itself: which source files an exception points at, which tests cover them, what changed in them recently.

Bounded and safe by construction:
* git is run without a shell and with fixed argument lists; the only variable parts are paths that were resolved INSIDE the repository root and
  timestamps/integers built here, and every path list is preceded by `--`;
* a path outside the repository root, or one that does not exist, is dropped;
* only commit hash, date and subject are read (no author names, no diff text), and excerpts are short and redacted;
* nothing here executes repository code or reads a file that is not a source/test file under the root.

A recent commit or a modified file is a CANDIDATE for investigation, never proof of cause: callers must word it that way.
"""
from __future__ import annotations

import os
import re
import subprocess
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from app.engineering.redact import redact_log_text

_JAVA_FRAME = re.compile(r"^\s*at\s+((?:com\.dsports)\.[\w.$]+)\.([\w$<>]+)\(([\w$]+\.java):(\d+)\)")
_PY_FRAME = re.compile(r'^\s*File "([^"]+)", line (\d+)(?:, in (\S+))?')
# Source code is not log text: a literal such as password = 'x' must not leave the repository even in a short excerpt.
_SECRET_ASSIGN = re.compile(
    r"""(?i)((?:pass(?:word|wd)?|secret|token|api[_-]?key|credential|private[_-]?key|jwt)[\w.\-]*\s*[:=]\s*)(?:'[^']*'|"[^"]*"|[^\s,;)]+)""")
MAX_EXCERPT_LINES = 14
MAX_FILES = 6
GIT_TIMEOUT_S = 15


def default_root() -> Path:
    """The repository this service runs from (ENG_REPO_ROOT overrides it). Evidence is only read from inside it."""
    return Path(os.environ.get("ENG_REPO_ROOT") or Path(__file__).resolve().parents[3])


@dataclass(frozen=True)
class Frame:
    path: str                  # repository-relative, forward slashes
    line: int | None
    symbol: str | None


def frames_from_exception(text: str | None, limit: int = 12) -> list[tuple[str, str | None, int | None, str | None]]:
    """Project frames only, in call order: (kind, java class path or python path, line, symbol). Library frames are ignored."""
    out: list[tuple[str, str | None, int | None, str | None]] = []
    for raw in (text or "").splitlines():
        m = _JAVA_FRAME.match(raw)
        if m:
            fqcn = re.sub(r"\$.*$", "", m.group(1))
            out.append(("java", fqcn.replace(".", "/") + ".java", int(m.group(4)), f"{m.group(1).rsplit('.', 1)[-1]}.{m.group(2)}"))
        else:
            p = _PY_FRAME.match(raw)
            if p:
                norm = p.group(1).replace("\\", "/")
                idx = norm.find("/ai-service/")
                if idx >= 0 and "/.venv/" not in norm:
                    out.append(("python", norm[idx + 1:], int(p.group(2)), p.group(3)))
        if len(out) >= limit:
            break
    return out


class RepoEvidence:
    def __init__(self, root: Path):
        self.root = root.resolve()

    # ------------------------------------------------------------------ files
    def _inside(self, p: Path) -> Path | None:
        try:
            rp = p.resolve()
        except OSError:
            return None
        return rp if rp.is_file() and rp.is_relative_to(self.root) else None

    def resolve_frames(self, frames: list[tuple[str, str | None, int | None, str | None]]) -> list[Frame]:
        seen: dict[str, Frame] = {}
        for kind, rel, line, symbol in frames:
            if not rel:
                continue
            found: Path | None = None
            if kind == "java":
                for cand in sorted(self.root.glob(f"backend/*/src/main/java/{rel}"))[:1]:
                    found = self._inside(cand)
            else:
                found = self._inside(self.root / rel)
            if found is not None:
                key = found.relative_to(self.root).as_posix()
                seen.setdefault(key, Frame(key, line, symbol))
            if len(seen) >= MAX_FILES:
                break
        return list(seen.values())

    def tests_for(self, path: str, limit: int = 4) -> list[str]:
        stem = Path(path).stem
        patterns = ([f"backend/*/src/test/java/**/{stem}*Test*.java"] if path.endswith(".java")
                    else [f"ai-service/tests/**/test_*{stem}*.py", f"ai-service/tests/**/test_{Path(path).parent.name}*.py"])
        out: list[str] = []
        for pattern in patterns:
            for p in sorted(self.root.glob(pattern)):
                ip = self._inside(p)
                if ip is not None and ip.relative_to(self.root).as_posix() not in out:
                    out.append(ip.relative_to(self.root).as_posix())
        return out[:limit]

    def excerpt(self, path: str, line: int | None, radius: int = 6) -> list[str]:
        p = self._inside(self.root / path)
        if p is None or line is None:
            return []
        try:
            lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            return []
        lo, hi = max(0, line - 1 - radius), min(len(lines), line + radius)
        picked = lines[lo:hi][:MAX_EXCERPT_LINES]
        return [f"{lo + i + 1}: {redact_log_text(_SECRET_ASSIGN.sub(r'\1<redacted>', ln))[:160]}" for i, ln in enumerate(picked)]

    # ------------------------------------------------------------------ git
    def _git(self, *args: str) -> str | None:
        try:
            r = subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, text=True, timeout=GIT_TIMEOUT_S, check=False,
                               env={**os.environ, "GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"})
        except (OSError, subprocess.SubprocessError):
            return None
        return r.stdout if r.returncode == 0 else None

    def head(self) -> dict[str, Any] | None:
        out = self._git("log", "-1", "--pretty=format:%h%x1f%aI%x1f%s")
        if not out:
            return None
        h, d, s = (out.split("\x1f") + ["", "", ""])[:3]
        return {"commit": h, "date": d, "subject": redact_log_text(s)[:120]}

    def commits(self, paths: list[str], since: datetime, until: datetime, limit: int = 10) -> list[dict[str, Any]] | None:
        """Commits in the window touching the given files (all files when none are given). None when git is unavailable."""
        safe = [p for p in paths if self._inside(self.root / p) is not None]
        out = self._git("log", f"--since={since.isoformat()}", f"--until={until.isoformat()}", "--pretty=format:%h%x1f%aI%x1f%s", "-n", str(limit), "--", *safe)
        if out is None:
            return None
        rows = []
        for ln in out.splitlines():
            h, d, s = (ln.split("\x1f") + ["", "", ""])[:3]
            rows.append({"commit": h, "date": d, "subject": redact_log_text(s)[:120]})
        return rows

    def uncommitted(self, paths: list[str]) -> list[str] | None:
        safe = [p for p in paths if self._inside(self.root / p) is not None]
        if not safe:
            return []
        out = self._git("status", "--porcelain", "--", *safe)
        return None if out is None else [ln[3:].strip() for ln in out.splitlines() if ln.strip()]

    def module_of(self, path: str) -> str | None:
        m = re.match(r"backend/([^/]+)/", path)
        return m.group(1) if m else ("ai-service" if path.startswith("ai-service/") else None)
