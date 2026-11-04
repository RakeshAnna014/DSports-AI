"""Victor Sport (victorsport.com): the manufacturer's OWN website, used as a permitted, official research source for badminton products and for finding the brand's local contacts.

What the site gives us (verified against the live site while building this provider):
  * /sitemap_products.xml            the product URL list (robots.txt allows it; only /login, /signup, /account, /search ... are disallowed)
  * /product/<slug>                  a server-rendered page: <h1> the product name, "SKU: <code>", a SPEC & TECH table, Open Graph title/description/image (an image on the brand's CDN)
  * /page/distributors               the brand's local contacts: a card per country with company name, phone, address and e-mail
The pages carry no price, so nothing here produces a price, a quotation or a margin: those still need a supplier quotation. Specifications come only from the page's own table.
Politeness: robots.txt is honoured, requests are spaced out, and a run reads at most `max_products` product pages.
"""
from __future__ import annotations

import hashlib
import html
import re
from typing import Any

from app.sourcing import extract, images, normalize, specs
from app.sourcing.fetch import HTML_TYPES, XML_TYPES, FetchError, SafeFetcher
from app.sourcing.providers import Candidate, ProviderResult, SupplierCandidate

BASE = "https://www.victorsport.com"
BRAND = "Victor"
CATEGORY_HINTS: dict[str, tuple[str, ...]] = {
    "rackets": ("thruster", "auraspeed", "jetspeed", "hypernano", "drivex", "brave", "ryuga", "challenger", "meteor", "tk-", "tk"),
    "shuttlecocks": ("shuttle", "gold", "master", "champion", "nylon", "ts-", "ts"),
    "shoes": ("shoe", "a9", "p9", "s82", "a3", "a7"),
    "bags": ("br", "bag"),
}
FAMILY_TO_CATEGORY = {"racket": "Rackets", "shuttlecock": "Shuttlecocks", "shoes": "Shoes", "bag": "Bags", "apparel": "Apparel", "fitness": "Equipment", "protective": "Protective gear"}
_CODE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9\-\.]{2,20})\s+(?:Badminton|Racket|Shuttle|Shoe|Bag|Apparel)", re.I)
_RELEASE = re.compile(r">\s*((?:19|20)\d\d)\s+NEW\s*<")
_LOC = re.compile(r"<loc>\s*([^<\s]+)\s*</loc>")
_CARD = re.compile(r'<div class="shop-card">(.*?)(?=<div class="shop-card">|</div></div></div></div>|$)', re.S)


def _text(fragment: str) -> str:
    return extract.clean_text(html.unescape(re.sub(r"<[^>]+>", " ", fragment)), 240)


def parse_distributors(page_html: str) -> list[dict[str, str]]:
    cards = []
    for c in page_html.split('class="shop-card"')[1:]:
        title = re.search(r'class="shop-title">(.*?)</div>', c, re.S)
        company = re.search(r'class="company-name">(.*?)</p>', c, re.S)
        if not title or not company:
            continue
        tel = re.search(r"<div>Tel:</div><div class=\"gray-text\">(.*?)</div>", c, re.S)
        addr = re.search(r"<div>Add:</div><div class=\"gray-text\">(.*?)</div>", c, re.S)
        mail = re.search(r"<div>E-mail:</div><div class=\"gray-text\">(.*?)</div>", c, re.S)
        cards.append({"country": _text(title.group(1)), "company": _text(company.group(1)), "phone": _text(tel.group(1)) if tel else "", "address": _text(addr.group(1)) if addr else "",
                      "email": _text(mail.group(1)) if mail else ""})
    return cards


class VictorSportProvider:
    name = "victor-sport"
    source_type = "OFFICIAL_BRAND_PAGE"

    def __init__(self, fetcher: SafeFetcher, base: str = BASE, max_products: int = 12, supplier_countries: tuple[str, ...] = ("India",)):
        self.fetcher, self.base, self.max_products = fetcher, base.rstrip("/"), max(1, min(int(max_products), 100))
        self.supplier_countries = tuple(c.lower() for c in supplier_countries)

    # ------------------------------------------------------------------------------------------ selection
    @staticmethod
    def select(urls: list[str], params: dict[str, Any], limit: int) -> list[str]:
        slugs = [(u, u.rsplit("/", 1)[-1].lower()) for u in urls if "/product/" in u]
        words = [str(k).lower() for k in (params.get("keywords") or []) if str(k).strip()]
        if words:
            slugs = [(u, s) for u, s in slugs if any(re.sub(r"[^a-z0-9]", "", w) in re.sub(r"[^a-z0-9]", "", s) for w in words)]
        else:
            cat = str(params.get("category") or "").lower().strip()
            hints = next((h for k, h in CATEGORY_HINTS.items() if cat and (k.rstrip("s") in cat or cat in k)), None) if cat else None
            if hints:
                slugs = [(u, s) for u, s in slugs if any(s.startswith(h) or f"-{h}" in s for h in hints)]
        seen_prefix: dict[str, int] = {}
        chosen: list[str] = []
        for u, s in slugs:                                     # spread over series (first slug word), deterministic
            prefix = re.split(r"[-_]", s)[0]
            if seen_prefix.get(prefix, 0) >= 3:
                continue
            seen_prefix[prefix] = seen_prefix.get(prefix, 0) + 1
            chosen.append(u)
            if len(chosen) >= limit:
                break
        return chosen

    # ------------------------------------------------------------------------------------------ one product page
    def candidate_from(self, url: str, page: extract.Page, raw_html: str) -> tuple[Candidate | None, str | None]:
        name = page.h1 or ""
        if not name:
            return None, "the page has no product heading (not a product page)"
        sku = extract.find_sku(page)
        desc = page.meta.get("og:description") or page.meta.get("description") or ""
        family = specs.family_of(desc, page.h1, page.title, url.rsplit("/", 1)[-1])
        # the page is this product's own page when its heading is the product and its title/Open Graph title names the same product (a SKU, when present, is an extra identifier)
        og_title = (page.meta.get("og:title") or page.title or "").lower()
        confirmed = bool(name and name.lower() in og_title)
        pairs = specs.canonicalise(page.spec_pairs)
        badge = _RELEASE.search(raw_html)
        if badge:
            pairs["release_note"] = {"label": "Release note", "value": f"{badge.group(1)} NEW (as stated on the manufacturer's page)"}
        refs = images.pick_images(page, name, sku, self.source_type, identity_confirmed=confirmed)
        code = _CODE.match(desc.strip())
        model_code = code.group(1) if code and code.group(1).lower() != name.lower() and re.search(r"\d", code.group(1)) else None    # e.g. "BRS-1800C Badminton Racket VICTOR"
        cand = Candidate(name=name, brand=BRAND, model=sku or model_code, sku=sku, manufacturer=BRAND, sport="Badminton", category=FAMILY_TO_CATEGORY.get(family or ""), description=desc or None,
                         specs=pairs, url=url, provider=self.name, content_hash=hashlib.sha256((name + (sku or "") + str(sorted((k, v["value"]) for k, v in pairs.items()))).encode()).hexdigest()[:32],
                         excerpt=extract.clean_text(desc or name, 300), source_title=page.meta.get("og:title") or page.title or None, model_name=name, mfr_sku=sku,
                         source_type=self.source_type, images=refs, identity_confirmed=confirmed)
        if not sku:
            return cand, None            # still a real product page; the missing identifier is reported on the record, not invented
        return cand, None

    # ------------------------------------------------------------------------------------------ the run
    async def fetch(self, params: dict[str, Any]) -> ProviderResult:
        brand = normalize.normalize_brand(params.get("brand")) if params.get("brand") else None
        if brand and brand != "victor":
            return ProviderResult(self.name, "ok", reason=f"This source covers the Victor brand only; the requested brand is {params.get('brand')}.", stats={"pages_requested": 0})
        sport = str(params.get("sport") or "").lower()
        if sport and sport not in ("badminton",):
            return ProviderResult(self.name, "ok", reason="This source covers badminton only.", stats={"pages_requested": 0})
        res = ProviderResult(self.name, "ok", stats={"pages_requested": 0, "pages_ok": 0, "invalid": 0, "images_found": 0, "suppliers_found": 0})
        try:
            sm = await self.fetcher.get(f"{self.base}/sitemap_products.xml", accept=XML_TYPES, max_bytes=1_500_000)
        except FetchError as exc:
            return ProviderResult(self.name, "error", reason=f"the product sitemap could not be read: {exc.message} ({exc.code})", stats=res.stats)
        urls = _LOC.findall(sm.text())
        chosen = self.select(urls, params, self.max_products)
        res.stats["sitemap_urls"] = len(urls)
        res.stats["selected"] = len(chosen)
        if not chosen:
            res.reason = "No product in the sitemap matched the requested keywords or category."
        failures = 0
        for u in chosen:
            res.stats["pages_requested"] += 1
            try:
                got = await self.fetcher.get(u, accept=HTML_TYPES)
            except FetchError as exc:
                failures += 1
                res.skipped.append({"url": u, "why": f"{exc.code}: {exc.message}"})
                if exc.code == "ROBOTS_DISALLOWED":
                    break
                continue
            page = extract.parse_page(got.text(), got.final_url)
            cand, why = self.candidate_from(got.final_url, page, got.text())
            if cand is None:
                res.stats["invalid"] += 1
                res.skipped.append({"url": u, "why": why or "no usable product data"})
                continue
            res.stats["pages_ok"] += 1
            res.stats["images_found"] += len(cand.images)
            res.candidates.append(cand)
        # supplier discovery from the brand's own contacts page (only the requested countries)
        try:
            dist = await self.fetcher.get(f"{self.base}/page/distributors", accept=HTML_TYPES)
            url = dist.final_url
            for c in parse_distributors(dist.text()):
                if self.supplier_countries and c["country"].lower() not in self.supplier_countries:
                    continue
                res.suppliers.append(SupplierCandidate(
                    name=c["company"], type="AUTHORISED_DISTRIBUTOR", country=c["country"], address=c["address"] or None, phone=c["phone"] or None, email=c["email"] or None, website=None,
                    brands=[BRAND], source_url=url,
                    source_note="Listed on the manufacturer's official distributors page. Authorisation and identity are NOT verified by a buyer."))
            res.stats["suppliers_found"] = len(res.suppliers)
        except FetchError as exc:
            res.skipped.append({"url": f"{self.base}/page/distributors", "why": f"{exc.code}: {exc.message}"})
        if failures and not res.candidates:
            res.status, res.reason = "error", f"none of the {failures} product page(s) could be read ({res.skipped[0]['why']})"
        elif failures:
            res.reason = f"{failures} of {len(chosen)} page(s) could not be read"
        return res
