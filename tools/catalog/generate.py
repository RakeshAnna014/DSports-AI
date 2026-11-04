"""Generates, from spec.py:

  backend/bootstrap/src/main/resources/db/migration/V43__multisport_demo_catalog.sql   sports, categories, brands, products, prices, inventory, attributes, images
  frontend/public/products/<sport>/<slug>-<n>.svg                                      3 images per product
  frontend/public/sports/<sport>.svg                                                   sport banners
  frontend/scripts/catalog-seed.json                                                   what `npm run validate:catalog` checks
  ai-service/app/agent/kits.json                                                       the per-sport kit definitions used by the recommender

Run:  python tools/catalog/generate.py        (deterministic: the same input always yields the same files)
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import illustrations as il  # noqa: E402
import spec  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
V15 = ROOT / "backend/catalog/src/main/resources/db/migration/V15__seed_products.sql"
V42 = ROOT / "backend/pricing/src/main/resources/db/migration/V42__seed_inr_prices.sql"
OUT_SQL = ROOT / "backend/bootstrap/src/main/resources/db/migration/V43__multisport_demo_catalog.sql"
PUBLIC = ROOT / "frontend/public"
SEED_JSON = ROOT / "frontend/scripts/catalog-seed.json"
KITS_JSON = ROOT / "ai-service/app/agent/kits.json"

SPORT_SLUG = {"Cricket": "cricket", "Football": "football", "Basketball": "basketball", "Tennis": "tennis", "Badminton": "badminton", "Volleyball": "volleyball",
              "Running": "running", "Fitness": "fitness", "Swimming": "swimming", "Cycling": "cycling", "Table Tennis": "table-tennis"}
SPORT_THEME = {"cricket": ("#0F7A38", "#B8F23C"), "football": ("#1D4ED8", "#38BDF8"), "basketball": ("#C2410C", "#FDBA74"), "tennis": ("#B45309", "#FDE047"),
               "badminton": ("#7C3AED", "#F0ABFC"), "volleyball": ("#0E7490", "#67E8F9"), "running": ("#BE123C", "#FDA4AF"), "fitness": ("#334155", "#94A3B8"),
               "swimming": ("#0369A1", "#7DD3FC"), "cycling": ("#15803D", "#BEF264"), "table-tennis": ("#1E40AF", "#93C5FD")}
SPORT_HERO = {"Cricket": ["bat", "ball-cricket", "helmet"], "Football": ["boot", "ball-football", "gloves-gk"], "Basketball": ["ball-basketball", "shoe", "jersey"],
              "Tennis": ["racquet-tennis", "ball-tennis", "cap"], "Badminton": ["racquet-badminton", "shuttlecock", "shoe"], "Running": ["shoe", "watch", "bottle"],
              "Fitness": ["dumbbell", "kettlebell", "mat"], "Swimming": ["goggles", "swimcap", "fins"], "Cycling": ["bike-road", "helmet-cycling", "bottle"],
              "Table Tennis": ["ttbat", "ball-tt", "tttable"], "Volleyball": ["ball-volleyball", "shoe", "support-knee"]}


def slugify(name: str) -> str:
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", name.lower())).strip("-")


def q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def pid(n: int) -> str:
    return f"00000000-0000-0000-0000-{n:012d}"


def colors_for(brand: str) -> dict:
    main, accent, dark = spec.BRAND_COLORS.get(brand, ("#1D4ED8", "#FDE047", "#0B1426"))
    return {"main": main, "accent": accent, "dark": dark, "light": "#FFFFFF"}


def read_existing() -> list[dict]:
    """The 50 seeded products, parsed from their own migration (so there is one source for their names and slugs)."""
    text = V15.read_text(encoding="utf-8")
    rx = re.compile(r"\('(0{8}-0{4}-0{4}-0{4}-0{8}(\d{4}))', '([^']*)', '((?:[^']|'')*)', '([^']*)', '((?:[^']|'')*)', \(SELECT id FROM brands WHERE name = '([^']*)'\), "
                    r"\(SELECT id FROM categories WHERE slug = '([^']*)'\), \(SELECT id FROM sports WHERE name = '([^']*)'\)")
    out = []
    for m in rx.finditer(text):
        out.append({"id": m.group(1), "n": int(m.group(2)), "sku": m.group(3), "name": m.group(4).replace("''", "'"), "slug": m.group(5), "brand": m.group(7),
                    "category_slug": m.group(8), "sport": m.group(9)})
    assert len(out) == 50, len(out)
    return out


def read_existing_prices() -> dict[int, tuple[int, int]]:
    out = {}
    for m in re.finditer(r"\('0{8}-0{4}-0{4}-0{4}-0{8}(\d{4})', (\d+), (\d+)\)", V42.read_text(encoding="utf-8")):
        out[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
    return out


def category_name(slug: str) -> str:
    return {"bat": "Bat", "ball": "Ball", "shoes": "Shoes", "gloves": "Gloves", "helmet": "Helmet", "jersey": "Jersey", "accessories": "Accessories", "kit-bag": "Kit Bag"}[slug]


def main() -> None:
    existing = read_existing()
    ex_prices = read_existing_prices()
    products: list[dict] = []
    for e in existing:
        item, role, sub, use = spec.EXISTING[e["n"]]
        mrp, price = ex_prices[e["n"]]
        products.append({**e, "category": category_name(e["category_slug"]), "item": item, "role": role, "sub": sub, "use": use, "mrp": mrp, "price": price, "new": False})
    for i, p in enumerate(spec.NEW):
        n = 1051 + i
        products.append({"id": pid(n), "n": n, "sku": p.sku, "name": p.name, "slug": slugify(p.name), "brand": p.brand, "category": p.category, "sport": p.sport, "item": p.item,
                         "role": p.role, "sub": p.sub, "use": p.use, "mrp": p.mrp, "price": p.price, "new": True, "spec": p})
    assert len({p["slug"] for p in products}) == len(products), "duplicate slugs"
    assert len({p["sku"] for p in products}) == len(products), "duplicate SKUs"

    # ---------------------------------------------------------------- images
    img_rows = []        # (image id, product n, url, order, primary)
    seed = []
    next_img = 8101
    for p in products:
        folder = SPORT_SLUG[p["sport"]]
        (PUBLIC / "products" / folder).mkdir(parents=True, exist_ok=True)
        urls = []
        for view in (1, 2, 3):
            rel = f"products/{folder}/{p['slug']}-{view}.svg"
            (PUBLIC / rel).write_text(il.render(p["item"], colors_for(p["brand"]), view=view, label="Detail" if view == 3 else ""), encoding="utf-8")
            urls.append("/" + rel)
        p["images"] = urls
        for view, url in enumerate(urls):
            if not p["new"] and view == 0:
                img_rows.append((f"{8000 + p['n'] - 1000}", p["n"], url, 0, True, "update"))        # replaces the placehold.co row seeded in V15
            else:
                img_rows.append((next_img, p["n"], url, view, view == 0, "insert"))
                next_img += 1
        seed.append({"id": p["id"], "sku": p["sku"], "name": p["name"], "brand": p["brand"], "sport": p["sport"], "category": p["category"], "kitRole": p["role"],
                     "mrp": p["mrp"], "price": p["price"], "discountPercent": round((p["mrp"] - p["price"]) / p["mrp"] * 100), "images": urls})

    # ---------------------------------------------------------------- sport banners
    (PUBLIC / "sports").mkdir(parents=True, exist_ok=True)
    for sport, items in SPORT_HERO.items():
        slug = SPORT_SLUG[sport]
        a, b = SPORT_THEME[slug]
        c = colors_for("Decathlon")
        parts = []
        for k, (item, x, y, s, r) in enumerate(zip(items, (-60, 250, 140), (40, 70, 150), (.78, .6, .5), (-12, 10, 0))):
            inner = il.DRAWERS[item]({**c, "main": "#FFFFFF" if k == 1 else c["main"], "accent": a if k != 0 else c["accent"], "dark": "#0B1426"})
            parts.append(f'<g transform="translate({x} {y}) rotate({r} 200 200) scale({s})" opacity="{1 if k == 0 else .92}">{inner}</g>')
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 400" width="600" height="400" role="img" aria-label="{sport}">'
               f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient>'
               f'<linearGradient id="wood" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#F2D9A8"/><stop offset="1" stop-color="#C99A58"/></linearGradient></defs>'
               f'<rect width="600" height="400" fill="url(#g)"/><circle cx="470" cy="80" r="170" fill="#FFFFFF" opacity=".10"/><circle cx="60" cy="380" r="150" fill="#000000" opacity=".12"/>'
               f'{"".join(parts)}</svg>')
        (PUBLIC / "sports" / f"{slug}.svg").write_text(svg, encoding="utf-8")

    # ---------------------------------------------------------------- SQL
    L: list[str] = []
    add = L.append
    add("-- SAMPLE DATA ONLY: a multi-sport demo catalogue (11 sports). Generated by tools/catalog/generate.py from tools/catalog/spec.py; do not edit by hand.")
    add("-- Brands are used the way a shop would list them; names, descriptions, specs and prices are illustrative, not manufacturer information.")
    add("-- Product images are original illustrations served from the frontend's static assets (/products/<sport>/<slug>-<n>.svg).\n")
    add("INSERT INTO sports (id, name, slug, description, status, created_at, updated_at, version) VALUES")
    add(",\n".join(f"    (gen_random_uuid(), {q(n)}, {q(s)}, {q(d)}, 'ACTIVE', NOW(), NOW(), 1)" for n, s, d in spec.NEW_SPORTS) + ";\n")
    add("INSERT INTO categories (id, name, slug, description, status, created_at, updated_at, version) VALUES")
    add(",\n".join(f"    (gen_random_uuid(), {q(n)}, {q(s)}, {q(d)}, 'ACTIVE', NOW(), NOW(), 1)" for n, s, d in spec.NEW_CATEGORIES) + ";\n")
    add("INSERT INTO brands (id, name, slug, description, status, created_at, updated_at, version) VALUES")
    add(",\n".join(f"    (gen_random_uuid(), {q(n)}, {q(s)}, {q(d)}, 'ACTIVE', NOW(), NOW(), 1)" for n, s, d in spec.NEW_BRANDS) + ";\n")

    news = [p for p in products if p["new"]]
    add("INSERT INTO products (id, sku, name, slug, description, brand_id, category_id, sport_id, weight, weight_unit, status, version) VALUES")
    rows = []
    for p in news:
        s = p["spec"]
        w, unit = (round(s.kg * 1000), "g") if s.kg < 0.1 else (round(s.kg, 2), "kg")
        rows.append(f"({q(p['id'])}, {q(p['sku'])}, {q(p['name'])}, {q(p['slug'])}, {q(s.desc)}, (SELECT id FROM brands WHERE name = {q(p['brand'])}), "
                    f"(SELECT id FROM categories WHERE name = {q(p['category'])}), (SELECT id FROM sports WHERE name = {q(p['sport'])}), {w}, {q(unit)}, 'ACTIVE', 1)")
    add(",\n".join(rows) + ";\n")

    add("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, effective_to, status, version) VALUES")
    add(",\n".join(f"({q(pid(10000 + p['n']))}, {q(p['id'])}, {p['mrp']}, {p['price']}, 'INR', NOW(), NULL, 'ACTIVE', 1)" for p in news) + ";\n")

    qty_override = {"SW-BUOY-ARE-108": 0}
    add("INSERT INTO inventory (id, product_id, warehouse_id, available_quantity, reserved_quantity, reorder_level, status, version) VALUES")
    inv = []
    for p in news:
        qty = qty_override.get(p["sku"], p["spec"].qty)
        status = "OUT_OF_STOCK" if qty == 0 else "LOW_STOCK" if qty <= 5 else "IN_STOCK"
        inv.append(f"({q(pid(9100 + p['n'] - 1000))}, {q(p['id'])}, {q(pid(9001))}, {qty}, 0, 5, {q(status)}, 1)")
    add(",\n".join(inv) + ";\n")

    attrs = []
    for p in products:
        a: dict[str, str] = {}
        if p["new"]:
            s = p["spec"]
            a.update({"skillLevel": s.skill, "ageGroup": s.age, "kitRole": s.role, "subCategory": s.sub, "useCase": s.use, "features": " | ".join(s.features)})
            if s.material:
                a["material"] = s.material
            if s.sizes:
                a["sizeOptions"] = s.sizes
            a.update(s.extra)
        else:
            a.update({"kitRole": p["role"], "subCategory": p["sub"], "useCase": p["use"]})
        for k, v in a.items():
            assert len(v) <= 200, (p["name"], k)
            attrs.append(f"({q(p['id'])}, {q(k)}, {q(v)})")
    add("INSERT INTO product_attributes (product_id, attr_key, attr_value) VALUES")
    add(",\n".join(attrs) + "\nON CONFLICT (product_id, attr_key) DO NOTHING;\n")

    add("-- The seeded rows pointed at placehold.co (the product name painted on a coloured block); point them at the local illustrations instead.")
    for rid, n, url, order, primary, kind in img_rows:
        if kind == "update":
            add(f"UPDATE product_images SET url = {q(url)} WHERE id = {q(pid(int(rid)))};")
    inserts = [r for r in img_rows if r[5] == "insert"]
    add("INSERT INTO product_images (id, product_id, url, display_order, is_primary) VALUES")
    add(",\n".join(f"({q(pid(int(rid)))}, {q(pid(n))}, {q(url)}, {order}, {'TRUE' if primary else 'FALSE'})" for rid, n, url, order, primary, _ in inserts) + ";")
    OUT_SQL.write_text("\n".join(L) + "\n", encoding="utf-8")

    # ---------------------------------------------------------------- validation seed + kits
    SEED_JSON.parent.mkdir(parents=True, exist_ok=True)
    SEED_JSON.write_text(json.dumps({"products": seed, "sports": sorted({p["sport"] for p in products})}, indent=1, ensure_ascii=False), encoding="utf-8")
    KITS_JSON.write_text(json.dumps({"kits": spec.KITS, "crossSport": [{"fromSport": a, "fromRole": b, "toSport": c, "toRole": d, "why": e} for a, b, c, d, e in spec.CROSS_SPORT]},
                                    indent=1, ensure_ascii=False), encoding="utf-8")
    per = {}
    for p in products:
        per[p["sport"]] = per.get(p["sport"], 0) + 1
    print(f"{len(products)} products ({len(news)} new), {len(per)} sports: {per}")
    print(f"{len(products) * 3} product images, {len(SPORT_HERO)} sport banners; wrote {OUT_SQL.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
