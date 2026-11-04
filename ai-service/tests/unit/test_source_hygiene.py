"""Guards against a recurring tooling accident: backslash escapes in regexes (\b, \n) turning into control characters when
source is patched through a shell. Such a character silently changes what a pattern matches."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORBIDDEN = {"\x08": "backspace (a mangled \b)", "\x07": "bell", "\x0b": "vertical tab", "\x0c": "form feed", "\x00": "NUL"}


def test_python_sources_contain_no_control_characters():
    offenders = []
    for path in [*(ROOT / "app").rglob("*.py"), *(ROOT / "tests").rglob("*.py"), *(ROOT / "scripts").rglob("*.py")]:
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.split("\n"), start=1):
            for char, name in FORBIDDEN.items():
                if char in line:
                    offenders.append(f"{path.relative_to(ROOT)}:{number} contains {name}")
    assert not offenders, "\n".join(offenders)


def test_regex_word_boundaries_survived():
    tier0 = (ROOT / "app" / "router" / "tier0.py").read_text(encoding="utf-8")
    assert r"\b(show|view|see|check)\b" in tier0 and r"(human|real person" in tier0
