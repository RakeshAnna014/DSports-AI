"""Product image references: which images a source page offers for a product, how sure we are that each one shows THAT product, and whether it can be loaded.

Nothing is downloaded or redistributed: an image is stored as a URL with its provenance (source page, source type, attribution, rights note, match level, validation status). Rules:
  * EXACT_MODEL        the image is the one the product's own page declares for it (structured-data image or Open Graph image of a page whose identity matches the product)
  * LIKELY_MATCH       an image on the product's own page whose alt text names the product, but which the page does not declare as the product image
  * SIMILAR_REFERENCE  an image of a related or different model (another page, a series page): a visual reference only
  * UNVERIFIED         a buyer-entered image the buyer has not classified
  Images found by a general image search are never accepted as proof that an image belongs to the product. If nothing suitable exists the UI says so; no unrelated picture is shown.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlsplit

from app.sourcing import extract
from app.sourcing.fetch import IMAGE_TYPES, FetchError, SafeFetcher

RIGHTS_NOTE = "Linked from the source page; not downloaded or redistributed. Usage rights belong to the brand or source: check its terms before reusing the image."
MATCH_LEVELS = ("EXACT_MODEL", "LIKELY_MATCH", "SIMILAR_REFERENCE", "UNVERIFIED")
MAX_PER_PRODUCT = 6
_BAD_NAME = re.compile(r"(logo|icon|sprite|favicon|placeholder|banner|avatar|badge|flag|arrow|social|payment|blank)", re.I)


@dataclass
class ImageRef:
    url: str
    match_level: str
    source_page_url: str
    source_type: str
    attribution: str
    rights_note: str = RIGHTS_NOTE
    is_primary: bool = False
    position: int = 0


def attribution_for(page_url: str) -> str:
    host = (urlsplit(page_url).hostname or "").removeprefix("www.")
    return f"Image from {host} (source page linked); © the brand or site owner"


def _alt_names_product(alt: str, name: str, sku: str | None) -> bool:
    a = re.sub(r"[^a-z0-9]+", " ", alt.lower()).strip()
    toks = [t for t in re.sub(r"[^a-z0-9]+", " ", name.lower()).split() if len(t) > 2]
    if sku and re.sub(r"[^a-z0-9]", "", sku.lower()) in re.sub(r"[^a-z0-9]", "", a):
        return True
    return bool(toks) and sum(1 for t in toks if t in a) >= max(1, len(toks) - 1)


def pick_images(page: extract.Page, product_name: str, sku: str | None, source_type: str, identity_confirmed: bool) -> list[ImageRef]:
    """The images a PRODUCT page declares or shows for this product. `identity_confirmed` says the page really is this product's page (e.g. its title/H1/SKU matched)."""
    out: list[ImageRef] = []
    seen: set[str] = set()

    def add(url: str, level: str) -> None:
        if url in seen or len(out) >= MAX_PER_PRODUCT or not url.lower().startswith(("http://", "https://")) or _BAD_NAME.search(urlsplit(url).path.rsplit("/", 1)[-1]):
            return
        seen.add(url)
        out.append(ImageRef(url, level, page.url, source_type, attribution_for(page.url), is_primary=not out, position=len(out)))

    for node in extract.product_nodes(page):
        for u in extract.jsonld_images(node, page.url):
            add(u, "EXACT_MODEL" if identity_confirmed else "LIKELY_MATCH")
    for u in extract.meta_images(page):
        add(u, "EXACT_MODEL" if identity_confirmed else "LIKELY_MATCH")
    if identity_confirmed:
        for img in page.images:
            if img.alt and _alt_names_product(img.alt, product_name, sku):
                add(img.src, "LIKELY_MATCH")
    return out


# ------------------------------------------------------------------------------------------------ validation
def sniff(head: bytes) -> str | None:
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head[:4] == b"GIF8":
        return "image/gif"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[4:12] in (b"ftypavif", b"ftypavis"):
        return "image/avif"
    return None


async def validate_image(fetcher: SafeFetcher, url: str, max_declared: int = 8_000_000) -> tuple[str, str, str | None, int | None]:
    """(status, detail, content_type, content_length). Only the first bytes are read. VALID means: reachable by the server, an image content type, matching file signature, not oversized.
    It does NOT prove the visitor's browser can load it (hotlink protection): the UI still handles a failed load."""
    try:
        got = await fetcher.get(url, accept=IMAGE_TYPES, max_bytes=65_536, peek=True)
    except FetchError as exc:
        status = {"URL_NOT_ALLOWED": "BLOCKED", "ROBOTS_DISALLOWED": "BLOCKED", "BAD_CONTENT_TYPE": "UNSUPPORTED"}.get(exc.code)
        if exc.code == "HTTP_STATUS":
            status = "BLOCKED" if exc.status in (401, 403, 451) else "BROKEN"
        return (status or "BROKEN"), exc.message, None, None
    kind = sniff(got.body[:16])
    if kind is None:
        return "UNSUPPORTED", "the file does not look like a supported image", got.content_type, got.declared_length
    if got.declared_length and got.declared_length > max_declared:
        return "UNSUPPORTED", f"the image is larger than {max_declared} bytes", got.content_type, got.declared_length
    return "VALID", "reachable image (server-side check)", kind, got.declared_length
