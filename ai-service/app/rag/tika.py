"""Apache Tika server client with OCR only where a page has no usable text layer.

How OCR is decided (measured against Tika 4.1.0, not assumed):
  Tika 4 deliberately disables per-request parser configuration ("Config endpoints are disabled", HTTP 403) and ignores
  the old X-Tika-PDFOcrStrategy header; enabling it would mean running the server with allowPerRequestConfig, which is a
  security risk for a service that parses uploaded documents. So a literal "extract without OCR, then retry with OCR" is
  not available. Tika's own default (AUTO) already runs Tesseract only on pages that lack a text layer, and it reports
  what it saw:
      pdf:chars-per-page   characters in the DIGITAL text layer of each page (before any OCR)
      pdf:ocr-page-count   pages Tesseract was actually run on
  We use those two facts: a document whose digital text layer averages fewer than AI_OCR_MIN_CHARS_PER_PAGE characters per
  page is a scan; we record whether OCR really ran, and refuse documents that are a scan but produced no text.
Non-PDF formats (md, txt, docx, html) have no OCR step. Images are OCR'd by Tika.
"""
from __future__ import annotations

import html
import logging
import re
from dataclasses import dataclass, field

import httpx

log = logging.getLogger(__name__)
_PAGE = re.compile(r'<div class="page">(.*?)</div>', re.DOTALL)
_BODY = re.compile(r"<body[^>]*>(.*)</body>", re.DOTALL)
_TAG = re.compile(r"<[^>]+>")
_HEADING = re.compile(r"<h([1-6])[^>]*>(.*?)</h\1>", re.DOTALL | re.IGNORECASE)
_BREAKS = [
    (re.compile(r"<li[^>]*>", re.I), "\n- "),
    (re.compile(r"</li>", re.I), ""),
    (re.compile(r"<br\s*/?>", re.I), "\n"),
    (re.compile(r"</?(p|blockquote|ul|ol|div|table)[^>]*>", re.I), "\n\n"),
    (re.compile(r"</tr>", re.I), "\n"),
    (re.compile(r"</t[dh]>", re.I), " | "),
]
_TEXT_TYPES = ("text/markdown", "text/plain", "text/html")


class ExtractionError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass
class Extraction:
    pages: list[str]
    ocr_used: bool
    is_scan: bool = False
    digital_chars_per_page: float | None = None
    ocr_pages: int = 0
    metadata: dict[str, str] = field(default_factory=dict)

    @property
    def text(self) -> str:
        return "\n\n".join(self.pages)


def markup_to_text(raw: str) -> str:
    """HTML from Tika -> light Markdown: headings keep their '#' (the chunker splits on them), lists keep their '-'."""
    text = _HEADING.sub(lambda m: f"\n\n{'#' * int(m.group(1))} {_TAG.sub('', m.group(2)).strip()}\n\n", raw)
    for pattern, replacement in _BREAKS:
        text = pattern.sub(replacement, text)
    text = html.unescape(_TAG.sub("", text))
    return re.sub(r"\n{3,}", "\n\n", re.sub(r"[ \t]+\n", "\n", text)).strip()


def html_to_pages(markup: str) -> list[str]:
    body = _BODY.search(markup)
    inner = body.group(1) if body else markup
    found = _PAGE.findall(inner) or [inner]  # not a paged format: the whole body is one page
    return [markup_to_text(raw) for raw in found]


class TikaClient:
    def __init__(self, base_url: str, timeout_s: float = 180.0, min_chars_per_page: int = 50, http: httpx.AsyncClient | None = None):
        self._base = base_url.rstrip("/")
        self._http = http or httpx.AsyncClient(timeout=httpx.Timeout(timeout_s, connect=5.0))
        self._min = min_chars_per_page

    async def aclose(self) -> None:
        await self._http.aclose()

    async def version(self) -> str:
        r = await self._http.get(f"{self._base}/version")
        r.raise_for_status()
        return r.text.strip()

    async def extract(self, data: bytes, content_type: str | None = None) -> Extraction:
        headers = {"Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = f"{content_type}; charset=utf-8" if content_type in _TEXT_TYPES else content_type
        try:
            r = await self._http.put(f"{self._base}/rmeta/html", content=data, headers=headers)
        except httpx.HTTPError as exc:
            raise ExtractionError("TIKA_UNAVAILABLE", f"Tika is not reachable: {type(exc).__name__}") from None
        if r.status_code == 422:
            raise ExtractionError("UNSUPPORTED_DOCUMENT", "Tika cannot parse this document")
        if r.status_code >= 300:
            raise ExtractionError("TIKA_ERROR", f"Tika returned HTTP {r.status_code}")
        try:
            meta = r.json()[0]
        except (ValueError, IndexError):
            raise ExtractionError("TIKA_BAD_RESPONSE", "Tika returned an unreadable response") from None

        pages = html_to_pages(meta.get("tk:content") or meta.get("X-TIKA:content") or "")
        detected = str(meta.get("Content-Type", "")).split(";")[0]
        ocr_pages = int(meta.get("pdf:ocr-page-count", 0) or 0)
        extraction = Extraction(pages=pages, ocr_used=ocr_pages > 0, ocr_pages=ocr_pages,
                                metadata={k: str(v) for k, v in meta.items() if k in ("dc:title", "Content-Type", "xmpTPg:NPages")})
        if detected.startswith("image/"):
            extraction.ocr_used = True
            return extraction
        if detected == "application/pdf":
            per_page = [int(x) for x in (meta.get("pdf:chars-per-page") or []) if str(x).isdigit()]
            extraction.digital_chars_per_page = sum(per_page) / len(per_page) if per_page else 0.0
            extraction.is_scan = extraction.digital_chars_per_page < self._min
            if extraction.is_scan and not any(p.strip() for p in pages):
                raise ExtractionError("NO_TEXT", "The document is a scan and OCR produced no text")
            log.info("pdf extracted", extra={"pages": len(pages), "digital_chars_per_page": round(extraction.digital_chars_per_page, 1),
                                             "ocr_pages": ocr_pages, "scan": extraction.is_scan})
        return extraction
