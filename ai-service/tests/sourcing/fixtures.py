"""Test doubles that mimic the STRUCTURE of the real Victor Sport site (observed live while building the provider): robots.txt, product sitemap, server-rendered product pages with a SPEC & TECH
table and Open Graph tags, the distributors page, and an image CDN. These are fixtures for automated tests; they are NOT evidence of a live integration (that is verified separately against
the real site by e2e/ai/sourcing_live_e2e.py)."""
from __future__ import annotations

import httpx

HOST = "www.victorsport.com"
CDN = "dkjulgymkya8y.cloudfront.net"
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
WEBP = b"RIFF\x24\x00\x00\x00WEBPVP8 " + b"\x00" * 40

ROBOTS = "User-agent: *\nDisallow: /login\nDisallow: /account\nDisallow: /search\nSitemap: https://www.victorsport.com/sitemap.xml\n"

PRODUCTS = {
    "thruster-k-11": {"h1": "THRUSTER K 100", "sku": "TK-100", "desc": "TK-100 Badminton Racket VICTOR", "img": "product-tk100.webp", "release": "2015",
                      "specs": [("Weight / Grip Size", "3U / G4、G5</br>4U / G5"), ("Frame Material", "Graphite + Resin"), ("Shaft Material", "Graphite + Resin + 7.0 SHAFT"),
                                ("String tension LBS", "≦24 lbs</br>≦23 lbs"), ("Stiffness", "S ○○○●○ F"), ("Length", "675 mm")]},
    "challenger-9500-cd": {"h1": "CHALLENGER 9500 CD", "sku": None, "desc": "CHALLENGER 9500 CD Badminton Racket product introduction VICTOR", "img": "product-9500cd.webp", "release": "2024",
                           "specs": [("Weight / Grip Size", "3U / G5</br>4U / G5"), ("Frame Material", "Graphite+ Resin")]},
    "gold-shuttle": {"h1": "GOLD SHUTTLE", "sku": "GS-1", "desc": "GS-1 Badminton Shuttlecock VICTOR", "img": "product-gs1.webp", "release": None, "specs": [("Speed", "77"), ("Quantity", "12 pcs / tube")]},
    "broken-page": {"h1": "", "sku": None, "desc": "", "img": None, "release": None, "specs": []},
}


def product_html(slug: str) -> str:
    p = PRODUCTS[slug]
    rows = "".join(f'<tr><td style="x"><strong>{k}</strong></td><td style="y">{v}</td></tr>' for k, v in p["specs"])
    sku = f'<p class="text-gray-500">SKU: <!-- -->{p["sku"]} </p>' if p["sku"] else ""
    badge = f'<div class="badge">{p["release"]} NEW</div>' if p["release"] else ""
    og = (f'<meta property="og:title" content="{p["h1"]} | VICTOR Badminton | Global"><meta property="og:description" content="{p["desc"]}">'
          f'<meta property="og:image" content="https://{CDN}/victor/en_us/{p["img"]}">') if p["img"] else ""
    h1 = f'<h1 class="font-bold">{p["h1"]}</h1>' if p["h1"] else ""
    return (f'<html><head><title>{p["h1"]} | VICTOR Badminton | Global</title>{og}<script>var x = "<h1>NOT A HEADING</h1>";</script></head><body>'
            f'<div class="space-y-4">{h1}{sku}{badge}</div><figure class="table"><table><tbody>{rows}</tbody></table></figure>'
            f'<img src="https://{CDN}/victor/en_us/related-other-racket.webp" alt="Another racket"></body></html>')


SITEMAP = ("<?xml version=\"1.0\"?><urlset>" + "".join(f"<url><loc>https://{HOST}/product/{s}</loc></url>" for s in PRODUCTS) +
           f"<url><loc>https://{HOST}/page/about</loc></url></urlset>")

DISTRIBUTORS = ('<html><body><div class="continent">'
                '<div class="shop-card"><div class="shop-title">China&nbsp;</div><p class="company-name">VICTOR SPORTS CO.,LTD.</p><div class="contact-info"><div class="tel-container"><div>Tel:</div><div class="gray-text">+86-1</div></div></div></div>'
                '<div class="shop-card"><div class="shop-title">India</div><p class="company-name">VICTOR India</p><div class="contact-info"><div class="tel-container"><div>Tel:</div><div class="gray-text">+91-9205688015</div></div>'
                '<div class="address-container"><div>Add:</div><div class="gray-text">PO Begumpur Khatola, Gurgaon-122001</div></div><div class="email-container"><div>E-mail:</div><div class="gray-text">info@victorsport.in</div></div></div></div>'
                '</div></body></html>')


def victor_handler(fail: set[str] | None = None, calls: list[str] | None = None):
    fail = fail or set()

    def handler(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        if calls is not None:
            calls.append(url)
        path = request.url.path
        if any(f in url for f in fail):
            return httpx.Response(503, text="down")
        if request.url.host == CDN:
            if path.endswith("broken.webp"):
                return httpx.Response(404)
            if path.endswith("blocked.webp"):
                return httpx.Response(403)
            if path.endswith("notimage.webp"):
                return httpx.Response(200, content=b"<html>nope</html>", headers={"content-type": "image/webp"})
            return httpx.Response(200, content=WEBP, headers={"content-type": "image/webp", "content-length": str(len(WEBP))})
        if path == "/robots.txt":
            return httpx.Response(200, text=ROBOTS, headers={"content-type": "text/plain"})
        if path == "/sitemap_products.xml":
            return httpx.Response(200, text=SITEMAP, headers={"content-type": "application/xml"})
        if path == "/page/distributors":
            return httpx.Response(200, text=DISTRIBUTORS, headers={"content-type": "text/html; charset=utf-8"})
        if path.startswith("/product/"):
            slug = path.rsplit("/", 1)[-1]
            if slug in PRODUCTS:
                return httpx.Response(200, text=product_html(slug), headers={"content-type": "text/html; charset=utf-8"})
        if path.startswith("/account"):
            return httpx.Response(200, text="private", headers={"content-type": "text/html"})
        return httpx.Response(404, text="not found")
    return handler


def public_resolver(host: str) -> list[str]:
    return ["93.184.216.34"]
