// `npm run validate:catalog`   checks the generated demo-catalogue spec against the files on disk.
// `npm run validate:catalog:live`  additionally checks the RUNNING backend: every product the API serves has a real image file, a sport,
//                                   a category, a positive price (and the list price is not below the selling price).
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const publicDir = join(here, '..', 'public');
const live = process.argv.includes('--live');
const api = process.env.CATALOG_API || 'http://localhost:8080';
const errors = [];
const err = (id, msg) => errors.push(`${id}\n    ${msg}`);

const seed = JSON.parse(readFileSync(join(here, 'catalog-seed.json'), 'utf8'));
const ids = new Set(); const skus = new Set(); const images = new Map();
const perSport = new Map();

for (const p of seed.products) {
  const tag = `${p.sku || '(no sku)'} ${p.name || '(no name)'}`;
  if (!p.name || !p.name.trim()) err(tag, 'Empty product name');
  if (!p.id) err(tag, 'Missing id');
  if (ids.has(p.id)) err(tag, `Duplicate product id ${p.id}`);
  ids.add(p.id);
  if (!p.sku) err(tag, 'Missing SKU'); else if (skus.has(p.sku)) err(tag, `Duplicate SKU ${p.sku}`);
  skus.add(p.sku);
  if (!p.sport) err(tag, 'Missing sport');
  if (!p.category) err(tag, 'Missing category');
  if (!p.brand) err(tag, 'Missing brand');
  if (!(p.price > 0)) err(tag, `Price must be > 0 (got ${p.price})`);
  if (!(p.mrp >= p.price)) err(tag, `Original price ${p.mrp} is below the selling price ${p.price}`);
  const expected = Math.round(((p.mrp - p.price) / p.mrp) * 100);
  if (p.discountPercent !== expected) err(tag, `discountPercent ${p.discountPercent} should be ${expected}`);
  if (!p.images || p.images.length === 0) err(tag, 'Missing image');
  for (const url of p.images ?? []) {
    if (!url.startsWith('/products/')) err(tag, `Image path is not under /products/: ${url}`);
    if (!existsSync(join(publicDir, url))) err(tag, `Missing image file: public${url}`);
    if (images.has(url)) err(tag, `Image shared with ${images.get(url)}: ${url}`);
    images.set(url, tag);
  }
  perSport.set(p.sport, (perSport.get(p.sport) ?? 0) + 1);
}
for (const [sport, n] of perSport) if (n < 8) err(sport, `Only ${n} products (minimum is 8)`);
if (!existsSync(join(publicDir, 'products', 'placeholder.svg'))) err('fallback', 'public/products/placeholder.svg is missing (the broken-image fallback)');
for (const sport of perSport.keys()) {
  const slug = sport.toLowerCase().replace(/[^a-z0-9]+/g, '-');
  if (!existsSync(join(publicDir, 'sports', `${slug}.svg`))) err(sport, `Missing sport image public/sports/${slug}.svg`);
}

if (live) {
  const rows = [];
  for (let page = 0; page < 50; page++) {
    const r = await fetch(`${api}/api/catalog/products?page=${page}&size=100`);
    if (!r.ok) { err('live', `GET products page ${page} -> ${r.status}`); break; }
    const batch = await r.json(); rows.push(...batch);
    if (batch.length < 100) break;
  }
  const prices = new Map((await (await fetch(`${api}/api/prices`)).json()).map((p) => [p.productId, p]));
  const sports = new Map((await (await fetch(`${api}/api/catalog/sports`)).json()).map((s) => [s.id, s.name]));
  const cats = new Map((await (await fetch(`${api}/api/catalog/categories`)).json()).map((c) => [c.id, c.name]));
  for (const p of rows) {
    const tag = `${p.sku} ${p.name}`;
    if (!p.imageUrl) err(tag, 'The API serves no image for this product');
    else if (!existsSync(join(publicDir, p.imageUrl))) err(tag, `The API image does not exist on disk: public${p.imageUrl}`);
    if (!sports.has(p.sportId)) err(tag, 'Unknown sport');
    if (!cats.has(p.categoryId)) err(tag, 'Unknown category');
    const pr = prices.get(p.id);
    if (!pr) err(tag, 'No active price');
    else if (!(Number(pr.sellingPrice) > 0) || Number(pr.mrp) < Number(pr.sellingPrice)) err(tag, `Bad price ${pr.sellingPrice} / list ${pr.mrp}`);
  }
  const missing = [...ids].filter((id) => !rows.some((r) => r.id === id));
  if (missing.length) err('live', `${missing.length} catalogue products from the spec are not served by the API (migration not applied?)`);
  console.log(`live: ${rows.length} products from ${api}, ${sports.size} sports`);
}

if (errors.length) {
  console.error('Catalog validation FAILED\n\nERROR:\n' + errors.map((e) => `  ${e}`).join('\n'));
  process.exit(1);
}
console.log(`Catalog validation passed. ${seed.products.length} products, ${perSport.size} sports (${[...perSport].map(([s, n]) => `${s} ${n}`).join(', ')}), ${images.size} image files.`);
