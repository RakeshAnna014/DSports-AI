"""Safe extraction of product facts from retrieved HTML. Standard library only (html.parser): no script is run, no resource is loaded, and nothing is evaluated.

What is extracted, and only what the page actually contains:
  * <title>, the first <h1>, meta description, Open Graph / Twitter card tags, <link rel="canonical">
  * JSON-LD blocks (schema.org Product: name, brand, sku, mpn, gtin*, image, description, offers)
  * two-column table rows (label | value) and definition lists as specification pairs
  * image URLs: structured data, Open Graph, and <img> tags on the page (the caller decides which belong to the product)
Everything is returned as bounded plain text; instruction-like sentences are removed (see app.guard.untrusted) because a page is untrusted data, never a source of instructions.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlsplit

from app.guard.untrusted import sanitize_untrusted

MAX_TEXT = 400
_WS = re.compile(r"\s+")


def clean_text(value: str | None, limit: int = MAX_TEXT) -> str:
    if not value:
        return ""
    text, _ = sanitize_untrusted(_WS.sub(" ", value.replace("\xa0", " ")).strip(), limit)
    return text.strip()


@dataclass
class ImgTag:
    src: str
    alt: str = ""
    width: int | None = None
    height: int | None = None
    in_gallery_hint: bool = False


@dataclass
class Page:
    url: str
    title: str = ""
    h1: str = ""
    meta: dict[str, str] = field(default_factory=dict)
    canonical: str | None = None
    jsonld: list[Any] = field(default_factory=list)
    spec_pairs: list[tuple[str, str]] = field(default_factory=list)
    images: list[ImgTag] = field(default_factory=list)
    text_after_sku: str | None = None
    body_text: str = ""


class _Parser(HTMLParser):
    def __init__(self, base: str):
        super().__init__(convert_charrefs=True)
        self.base = base
        self.page = Page(url=base)
        self._tag_stack: list[str] = []
        self._buf: list[str] = []
        self._capture: str | None = None          # title | h1 | script_ld | cell | dt | dd
        self._cell_text: list[str] = []
        self._row: list[str] = []
        self._in_row = False
        self._pending_dt: str | None = None
        self._skip = 0                            # inside <script>/<style>/<noscript>
        self._ld_buf: list[str] = []
        self._body: list[str] = []

    # ------------------------------------------------------------------------------------------ tags
    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "meta":
            key = (a.get("property") or a.get("name") or "").lower()
            if key and "content" in a and key not in self.page.meta:
                self.page.meta[key] = a["content"]
        elif tag == "link" and a.get("rel", "").lower() == "canonical" and a.get("href"):
            self.page.canonical = urljoin(self.base, a["href"])
        elif tag == "title" and not self.page.title:
            self._capture, self._buf = "title", []
        elif tag == "h1" and not self.page.h1:
            self._capture, self._buf = "h1", []
        elif tag == "script":
            if a.get("type", "").lower() == "application/ld+json":
                self._capture, self._ld_buf = "script_ld", []
            else:
                self._skip += 1
        elif tag in ("style", "noscript"):
            self._skip += 1
        elif tag == "tr":
            self._in_row, self._row = True, []
        elif tag in ("td", "th") and self._in_row:
            self._capture, self._cell_text = "cell", []
        elif tag == "dt":
            self._capture, self._buf = "dt", []
        elif tag == "dd":
            self._capture, self._buf = "dd", []
        elif tag == "br" and self._capture in ("cell", "dd"):
            (self._cell_text if self._capture == "cell" else self._buf).append(" / ")
        elif tag == "img":
            src = a.get("src") or a.get("data-src") or a.get("data-lazy-src") or ""
            if not src and a.get("srcset"):
                src = a["srcset"].split(",")[0].strip().split(" ")[0]
            if src and not src.startswith("data:"):
                self.page.images.append(ImgTag(urljoin(self.base, src), clean_text(a.get("alt"), 160), _int(a.get("width")), _int(a.get("height"))))
        self._tag_stack.append(tag)

    def handle_endtag(self, tag: str) -> None:
        if tag == "br" and self._capture in ("cell", "dd"):       # sloppy markup such as "a</br>b" is a line break too
            (self._cell_text if self._capture == "cell" else self._buf).append(" / ")
            return
        if tag == "script":
            if self._capture == "script_ld":
                try:
                    self.page.jsonld.append(json.loads("".join(self._ld_buf).strip()))
                except ValueError:
                    pass
                self._capture = None
            elif self._skip:
                self._skip -= 1
        elif tag in ("style", "noscript") and self._skip:
            self._skip -= 1
        elif tag == "title" and self._capture == "title":
            self.page.title = clean_text("".join(self._buf), 200)
            self._capture = None
        elif tag == "h1" and self._capture == "h1":
            self.page.h1 = clean_text("".join(self._buf), 200)
            self._capture = None
        elif tag in ("td", "th") and self._capture == "cell":
            self._row.append(clean_text(" ".join("".join(self._cell_text).split()), MAX_TEXT))
            self._capture = None
        elif tag == "tr" and self._in_row:
            cells = [c for c in self._row]
            if len(cells) == 2 and cells[0] and cells[1] and len(cells[0]) <= 80:
                self.page.spec_pairs.append((cells[0], cells[1]))
            self._in_row = False
        elif tag == "dt" and self._capture == "dt":
            self._pending_dt = clean_text("".join(self._buf), 80)
            self._capture = None
        elif tag == "dd" and self._capture == "dd":
            value = clean_text("".join(self._buf), MAX_TEXT)
            if self._pending_dt and value:
                self.page.spec_pairs.append((self._pending_dt, value))
            self._pending_dt, self._capture = None, None
        if self._tag_stack and self._tag_stack[-1] == tag:
            self._tag_stack.pop()

    def handle_data(self, data: str) -> None:
        if self._capture == "script_ld":
            self._ld_buf.append(data)
        elif self._skip:
            return
        elif self._capture in ("title", "h1", "dt", "dd"):
            self._buf.append(data)
        elif self._capture == "cell":
            self._cell_text.append(data)
        if not self._skip and data.strip():
            self._body.append(data.strip())


def _int(v: str | None) -> int | None:
    try:
        return int(re.sub(r"[^\d]", "", v or "")) or None
    except ValueError:
        return None


def parse_page(html: str, url: str) -> Page:
    parser = _Parser(url)
    parser.feed(html[:2_000_000])
    parser.close()
    page = parser.page
    page.body_text = _WS.sub(" ", " ".join(parser._body))[:6000]
    return page


# ------------------------------------------------------------------------------------------------ structured data
def product_nodes(page: Page) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    stack: list[Any] = list(page.jsonld)
    while stack and len(out) < 20:
        node = stack.pop(0)
        if isinstance(node, list):
            stack += node
        elif isinstance(node, dict):
            if isinstance(node.get("@graph"), list):
                stack += node["@graph"]
            types = node.get("@type")
            if "Product" in (types if isinstance(types, list) else [types]):
                out.append(node)
    return out


def jsonld_images(node: dict[str, Any], base: str) -> list[str]:
    img = node.get("image")
    items = img if isinstance(img, list) else [img] if img else []
    urls = []
    for i in items:
        u = i.get("url") if isinstance(i, dict) else i
        if isinstance(u, str) and u.strip():
            urls.append(urljoin(base, u.strip()))
    return urls


def meta_images(page: Page) -> list[str]:
    urls = []
    for key in ("og:image", "og:image:url", "twitter:image", "twitter:image:src"):
        if page.meta.get(key):
            urls.append(urljoin(page.url, page.meta[key]))
    return list(dict.fromkeys(urls))


_SKU = re.compile(r"\bSKU\s*[:：]\s*([A-Za-z0-9][A-Za-z0-9\-_/\.]{1,40})", re.I)
_MODEL = re.compile(r"\b(?:Model|Item|Article|Part)\s*(?:No\.?|Number|#)?\s*[:：]\s*([A-Za-z0-9][A-Za-z0-9\-_/\.]{1,40})", re.I)


def find_sku(page: Page) -> str | None:
    for node in product_nodes(page):
        for k in ("sku", "mpn"):
            if isinstance(node.get(k), str) and node[k].strip():
                return clean_text(node[k], 60)
    for rx in (_SKU, _MODEL):
        m = rx.search(page.body_text)
        if m:
            return m.group(1).strip(".")
    return None


def same_site(url: str, other: str) -> bool:
    a, b = urlsplit(url).hostname or "", urlsplit(other).hostname or ""
    return bool(a) and a == b
