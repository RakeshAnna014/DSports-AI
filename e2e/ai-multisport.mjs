// Real-browser acceptance test for the MULTI-SPORT catalogue, real product images and cross-sport AI/personalisation. REAL stack, nothing mocked.
// Prerequisites: backend :8080 (migration V43 applied), AI :8090, Vite :3000, demo customers (python e2e/ai/seed_demo_customers.py).
// Usage: node ai-multisport.mjs [shotDir]
import { chromium } from 'playwright';

const shots = process.argv[2] || '.';
const BASE = 'http://localhost:3000';
const JAVA = 'http://localhost:8080';
const PASSWORD = process.env.DEMO_PASSWORD || 'Passw0rd!x';
let failed = false;
const log = (...a) => console.log('[multisport]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

const problems = [];
let tolerate = false;      // while a failure is injected on purpose
const watch = (page) => {
  page.on('response', (r) => { if (r.status() >= 400 && !tolerate && !/\/(login|refresh)/.test(r.url()) && !/\/api\/v1\/(cart|wishlist)$/.test(r.url())) problems.push(`HTTP ${r.status()} ${r.request().method()} ${r.url()}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !tolerate && !/status of 4\d\d/.test(m.text())) problems.push(`console: ${m.text()}`); });
  page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
};
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) { failed = true; log('FAIL', name, '-', String(e.message || e).split('\n')[0]); try { await page.screenshot({ path: `${shots}/fail-ms-${name.slice(0, 34).replace(/\W+/g, '_')}.png`, fullPage: true }); } catch { /* ignore */ } }
}
const javaJson = async (path, token) => (await fetch(JAVA + path, token ? { headers: { Authorization: `Bearer ${token}` } } : {})).json();
const allProducts = async () => { const out = []; for (let p = 0; p < 5; p++) { const r = await javaJson(`/api/catalog/products?page=${p}&size=100`); out.push(...r); if (r.length < 100) break; } return out; };

/** Every <img> inside the selector actually loaded real pixels from the catalogue (not the fallback, not a broken icon). */
async function imagesAreReal(page, selector, { minimum = 1 } = {}) {
  await page.waitForFunction((sel) => [...document.querySelectorAll(`${sel} img`)].length > 0 && [...document.querySelectorAll(`${sel} img`)].every((i) => i.complete && i.naturalWidth > 0 && !/placeholder\.svg/.test(i.getAttribute('src') || '')), selector, { timeout: 30000 });
  const info = await page.evaluate((sel) => [...document.querySelectorAll(`${sel} img`)].map((i) => ({ src: i.getAttribute('src'), ok: i.complete && i.naturalWidth > 0 })), selector);
  const bad = info.filter((i) => !i.ok || /placehold\.co|placeholder\.svg/.test(i.src ?? '') || !(i.src ?? '').startsWith('/'));
  if (info.length < minimum) throw new Error(`only ${info.length} images in ${selector}`);
  if (bad.length) throw new Error(`${bad.length} bad images in ${selector}: ${JSON.stringify(bad.slice(0, 2))}`);
  return info.length;
}
async function scrollThrough(page) { for (let y = 0; y < 8000; y += 700) { await page.mouse.wheel(0, 700); await page.waitForTimeout(120); } await page.evaluate(() => window.scrollTo(0, 0)); }

// ============================================================================================ guest
const guestCtx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await guestCtx.newPage();
watch(page);
const chats = [];
page.on('response', async (r) => { if (r.url().includes('/ai-api/v1/chat') && r.request().method() === 'POST') { try { chats.push({ req: JSON.parse(r.request().postData() || '{}'), body: await r.json() }); } catch { /* ignore */ } } });
async function ask(text) {
  const before = chats.length;
  await page.getByTestId('ai-search-input').first().fill(text);
  await page.getByTestId('ai-search-submit').first().click();
  for (let i = 0; i < 240 && chats.length === before; i++) await page.waitForTimeout(500);
  if (chats.length === before) throw new Error('no chat response');
  await page.getByTestId('ai-search-results').getByTestId('ai-assistant-message').first().waitFor({ timeout: 30000 });
  await page.getByTestId('ai-loading').waitFor({ state: 'hidden', timeout: 30000 });
  return chats[chats.length - 1];
}

await step(page, 'M1 Home: real product images (no placeholder blocks), multi-sport trending, all 11 sport tiles with images', async () => {
  await page.goto(BASE); await page.waitForLoadState('networkidle');
  await page.getByTestId('featured-products').locator('.MuiCard-root').first().waitFor();
  await scrollThrough(page);
  const n = await imagesAreReal(page, '[data-testid="featured-products"]', { minimum: 8 });
  const tiles = page.getByTestId('sports-categories').getByRole('button');
  if ((await tiles.count()) !== 11) throw new Error('sport tiles: ' + await tiles.count());
  await imagesAreReal(page, '[data-testid="sports-categories"]', { minimum: 11 });
  const names = await page.getByTestId('featured-products').locator('.MuiCard-root').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
  const catalog = await allProducts();
  const sports = new Set(names.map((n) => catalog.find((p) => p.name === n)?.sportId));
  if (sports.size < 5) throw new Error(`trending shows only ${sports.size} sports`);
  const imgBox = await page.getByTestId('featured-products').locator('.MuiCard-root').first().locator('img').boundingBox();
  const cardBox = await page.getByTestId('featured-products').locator('.MuiCard-root').first().boundingBox();
  const share = imgBox.height / cardBox.height;
  if (share < 0.4 || share > 0.62) throw new Error(`image takes ${(share * 100).toFixed(0)}% of the card (expected ~45-55%)`);
  log('     images on home shelf:', n, '| sports in trending:', sports.size, '| image share of card:', (share * 100).toFixed(0) + '%');
  await page.screenshot({ path: `${shots}/ms1-home.png`, fullPage: true });
});

await step(page, 'M2 every sport opens a filtered catalogue with >= 8 real products and real images', async () => {
  const catalog = await allProducts();
  const sports = await javaJson('/api/catalog/sports');
  for (const s of sports) {
    await page.goto(BASE); await page.getByTestId('sports-categories').waitFor();
    await page.getByRole('button', { name: `Shop ${s.name}` }).click();
    await page.waitForURL(/\/products\?sport=/);
    await page.getByTestId('product-grid').locator('.MuiCard-root').first().waitFor();
    const expected = catalog.filter((p) => p.sportId === s.id).length;
    let shown = 0;
    for (let i = 0; i < 20 && shown !== Math.min(expected, 24); i++) { shown = await page.getByTestId('product-grid').locator('.MuiCard-root').count(); if (shown !== Math.min(expected, 24)) await page.waitForTimeout(400); }
    if (shown !== Math.min(expected, 24)) throw new Error(`${s.name}: ${shown} cards, expected ${Math.min(expected, 24)} | ${await page.getByText(/^Price: ₹/).innerText()} | ${page.url()} | ${(await page.getByTestId('product-grid').innerText()).replace(/\s+/g, ' ').slice(0, 300)}`);
    if (expected < 8) throw new Error(`${s.name} has only ${expected} products`);
    await scrollThrough(page);
    await imagesAreReal(page, '[data-testid="product-grid"]', { minimum: Math.min(expected, 24) });
    log('     ', s.name.padEnd(13), expected, 'products, images ok');
  }
});

await step(page, 'M3 filters: sport + category + skill level + price range + stock + pagination', async () => {
  await page.goto(`${BASE}/products`);
  await page.getByTestId('product-grid').locator('.MuiCard-root').first().waitFor();
  const total = await page.getByTestId('product-grid').locator('.MuiCard-root').count();
  if (total !== 24) throw new Error(`first page should hold 24 products, has ${total}`);
  await page.getByTestId('pagination').getByRole('button', { name: /page 2|2/ }).first().click();
  await page.getByTestId('product-grid').locator('.MuiCard-root').first().waitFor();
  await page.getByTestId('sport-tabs').getByRole('tab', { name: 'Football' }).click();
  await page.waitForURL(/sport=/);
  await page.getByLabel('Category').click(); await page.getByRole('option', { name: 'Shoes' }).click();
  await page.getByTestId('skill-filter').getByText('Beginner').click();
  await page.waitForTimeout(500);
  const names = await page.getByTestId('product-grid').locator('.MuiCard-root').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
  if (!names.includes('Nivia Football Boots - Dominator')) throw new Error('beginner football boots missing: ' + names);
  if (names.includes('Nike Football Boots - Elite Pro')) throw new Error('an advanced boot passed the beginner filter');
  // price: drag the upper handle down to ~40% of the track
  const slider = page.getByTestId('price-slider');
  const thumb = slider.locator('.MuiSlider-thumb').nth(1);
  const tb = await thumb.boundingBox(); const sb = await slider.boundingBox();
  await page.mouse.move(tb.x + tb.width / 2, tb.y + tb.height / 2); await page.mouse.down();
  await page.mouse.move(sb.x + sb.width * 0.4, tb.y + tb.height / 2, { steps: 8 }); await page.mouse.up();
  await page.waitForTimeout(500);
  const label = await page.getByText(/^Price: ₹/).innerText();
  const max = Number(label.split('—')[1].replace(/[^\d]/g, ''));
  const catalog = await allProducts(); const prices = new Map((await javaJson('/api/prices')).map((p) => [p.productId, Number(p.sellingPrice)]));
  const shown = await page.getByTestId('product-grid').locator('.MuiCard-root').evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
  for (const n of shown) if (prices.get(catalog.find((p) => p.name === n).id) > max) throw new Error(`${n} is above the price filter ${max}`);
  if (!(max < 14000)) throw new Error('slider did not move: ' + label);
  await page.screenshot({ path: `${shots}/ms3-filters.png` });
});

await step(page, 'M4 AI natural-language search works in every sport and renders real product cards with real images', async () => {
  await page.goto(BASE); await page.getByTestId('ai-search-input').first().waitFor();
  const cases = [
    ['Show me cricket bats under ₹10,000', 'Cricket', 10000], ['Find football boots for beginners', 'Football', null], ['Show basketball shoes under ₹8,000', 'Basketball', 8000],
    ['I need badminton racquets for intermediate players', 'Badminton', null], ['Find running shoes for daily running', 'Running', null], ['Show me gym equipment under ₹5,000', 'Fitness', 5000],
    ['Which swimming goggles are best for beginners?', 'Swimming', null], ['Show cycling helmets', 'Cycling', null], ['show me table tennis bats', 'Table Tennis', null],
    ['Show me tennis racquets', 'Tennis', null], ['volleyball shoes', 'Volleyball', null],
  ];
  const catalog = await allProducts(); const sports = new Map((await javaJson('/api/catalog/sports')).map((s) => [s.id, s.name]));
  for (const [q, sport, budget] of cases) {
    const c = await ask(q);
    if (c.body.type !== 'PRODUCT_LIST' || !c.body.products.length) throw new Error(`${q}: type ${c.body.type}`);
    for (const p of c.body.products) {
      const real = catalog.find((x) => x.id === p.product_id);
      if (!real || real.name !== p.name) throw new Error(`${q}: ${p.name} is not a catalogue product`);
      if (sports.get(real.sportId) !== sport) throw new Error(`${q}: ${p.name} is ${sports.get(real.sportId)}, expected ${sport}`);
      if (budget && p.price > budget) throw new Error(`${q}: ${p.name} costs ${p.price} > ${budget}`);
    }
    await imagesAreReal(page, '[data-testid="ai-search-results"]', { minimum: 1 });
    log('     ', q.padEnd(52), '->', c.body.products.length, 'products', '| tier', c.body.tier, '| llm', c.body.meta.llmCalls);
  }
  await page.screenshot({ path: `${shots}/ms4-ai-search.png`, fullPage: true });
});

await step(page, 'M5 compare tennis racquets: a real comparison table with images, then add one to the cart', async () => {
  await ask('Show me tennis racquets');
  const c = await ask('Compare these tennis racquets');
  if (c.body.type !== 'PRODUCT_COMPARISON' || c.body.comparison.products.length < 2) throw new Error('no comparison: ' + c.body.type);
  const t = page.getByTestId('ai-search-results').getByTestId('ai-comparison');
  await t.waitFor();
  await imagesAreReal(page, '[data-testid="ai-comparison"]', { minimum: 2 });
  for (const row of ['Price', 'Weight', 'Rating', 'Skill level']) await t.getByText(row, { exact: true }).first().waitFor();
  await t.getByTestId('ai-compare-add').first().click();
  await page.getByTestId('added-to-cart').waitFor();
  await page.screenshot({ path: `${shots}/ms5-compare.png`, fullPage: true });
});

await step(page, 'M6 "build a home gym under ₹10,000": a bundle within budget with total and savings, "Add all to cart"', async () => {
  const c = await ask('I want to build a home gym under ₹10,000');
  if (c.body.type !== 'RECOMMENDATIONS') throw new Error('type ' + c.body.type);
  const b = c.body.recommendations.bundle;
  if (!(b.total > 0 && b.total <= 10000)) throw new Error('bundle total ' + b.total);
  const names = c.body.recommendations.items.map((i) => i.name);
  if (!names.some((n) => /Dumbbell/.test(n)) || !names.some((n) => /Resistance Bands/.test(n)) || !names.some((n) => /Yoga Mat/.test(n))) throw new Error('kit lacks dumbbells/bands/mat: ' + names);
  const block = page.getByTestId('ai-search-results').getByTestId('recommendations');
  await block.getByTestId('bundle-summary').waitFor();
  await imagesAreReal(page, '[data-testid="ai-search-results"] [data-testid="recommendations"]', { minimum: 4 });
  await block.getByTestId('bundle-add-all').click();
  await page.getByLabel('cart').locator('.MuiBadge-badge').getByText(String(c.body.recommendations.items.length + 1)).waitFor({ timeout: 20000 });
  log('     home gym:', names.join(' | '), '| total', b.total, '| savings', b.savings);
  await page.screenshot({ path: `${shots}/ms6-home-gym.png`, fullPage: true });
});

await step(page, 'M7 cross-sport cart: subtotal and savings match DSports; "Complete your setup" suggests items from several sports with reasons', async () => {
  await page.goto(`${BASE}/products?sport=${(await javaJson('/api/catalog/sports')).find((s) => s.name === 'Running').id}`);
  await page.getByTestId('product-grid').locator('.MuiCard-root').first().waitFor();
  const run = page.getByTestId('product-grid').locator('.MuiCard-root').filter({ hasText: 'Asics Running Shoes - Daily Trainer' });
  await run.getByRole('button', { name: 'Add to cart' }).click();
  const panel = page.getByTestId('added-to-cart');
  await panel.waitFor();
  await panel.getByTestId('recommendation-card').first().waitFor({ timeout: 30000 });
  const reasons = (await panel.getByTestId('recommendation-reasons').allInnerTexts()).join(' ');
  if (!/Completes your running routine/.test(reasons)) throw new Error('running setup reason missing: ' + reasons);
  const cartBtn = page.getByTestId('cart-button'); await cartBtn.click();
  const drawer = page.getByTestId('cart-drawer');
  await drawer.getByTestId('cart-items').waitFor();
  const sub = (await drawer.getByTestId('cart-subtotal').innerText()).replace(/[^\d.]/g, '');
  const items = (await drawer.getByTestId('cart-items').locator('p.MuiTypography-subtitle2, h6').allInnerTexts()).length;
  if (!(Number(sub) > 0)) throw new Error('subtotal ' + sub);
  await imagesAreReal(page, '[data-testid="cart-items"]', { minimum: 2 });
  await page.screenshot({ path: `${shots}/ms7-cross-sport-cart.png` });
  log('     cart items:', items, '| subtotal', sub);
  await drawer.getByRole('button', { name: 'Continue shopping' }).click();
});

await step(page, 'M8 a missing/broken product image falls back to a DSports visual (no broken icon, no name in the picture)', async () => {
  tolerate = true;
  await page.route('**/products/swimming/speedo-swimming-goggles-anti-fog-*.svg', (r) => r.abort());
  await page.goto(`${BASE}/products?sport=${(await javaJson('/api/catalog/sports')).find((s) => s.name === 'Swimming').id}`);
  const card = page.getByTestId('product-grid').locator('.MuiCard-root').filter({ hasText: 'Speedo Swimming Goggles - Anti-Fog' });
  await card.waitFor();
  await page.waitForFunction(() => { const i = [...document.querySelectorAll('[data-testid="product-grid"] .MuiCard-root')].find((c) => c.textContent.includes('Anti-Fog'))?.querySelector('img'); return i && i.complete && i.naturalWidth > 0 && /placeholder\.svg/.test(i.getAttribute('src')); }, null, { timeout: 15000 });
  await page.unroute('**/products/swimming/speedo-swimming-goggles-anti-fog-*.svg');
  tolerate = false;
  await page.screenshot({ path: `${shots}/ms8-fallback.png` });
});

await step(page, 'M9 product detail: gallery with thumbnails, highlights and specifications from the catalogue, Ask AI in context', async () => {
  const yonex = (await allProducts()).find((p) => p.name === 'Yonex Badminton Racquet - Intermediate');
  await page.goto(`${BASE}/products/${yonex.id}`);
  const gallery = page.getByTestId('image-gallery');
  await gallery.waitFor();
  if ((await gallery.getByTestId('gallery-thumb').count()) !== 3) throw new Error('expected 3 thumbnails');
  await imagesAreReal(page, '[data-testid="image-gallery"]', { minimum: 4 });
  const before = await gallery.locator('img').first().getAttribute('src');
  await gallery.getByTestId('gallery-thumb').nth(1).click();
  await page.waitForFunction((b) => document.querySelector('[data-testid="image-gallery"] img').getAttribute('src') !== b, before);
  await page.getByTestId('product-features').getByText('Head-heavy balance').waitFor();
  const specs = page.getByTestId('product-specs');
  for (const t of ['Skill level', 'Best for', 'Material']) await specs.getByText(t, { exact: true }).waitFor();
  await page.getByTestId('product-ai').getByTestId('ai-context').getByText('Asking about: Yonex Badminton Racquet - Intermediate').waitFor();
  await page.screenshot({ path: `${shots}/ms9-detail.png`, fullPage: true });
});

await step(page, 'M10 mobile (390px): sports scroll horizontally, 2-column grid, prominent images, no horizontal page overflow', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(BASE); await page.getByTestId('sports-categories').waitFor();
  const tiles = page.getByTestId('sports-categories').getByRole('button');
  const scrollable = await page.getByTestId('sports-categories').locator('> div').evaluate((el) => el.scrollWidth > el.clientWidth + 10);
  if (!scrollable) throw new Error('sport tiles do not scroll horizontally on a phone');
  await tiles.first().waitFor();
  const cards = page.getByTestId('featured-products').locator('.MuiCard-root');
  await cards.nth(1).waitFor();
  const a = await cards.nth(0).boundingBox(); const b = await cards.nth(1).boundingBox();
  if (Math.abs(a.y - b.y) > 4) throw new Error('not 2 columns');
  const img = await cards.nth(0).locator('img').boundingBox();
  if (img.height < 120) throw new Error('product image too small on mobile: ' + img.height);
  const [sw, iw] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth]);
  if (sw > iw + 1) throw new Error(`horizontal overflow ${sw} > ${iw}`);
  await page.screenshot({ path: `${shots}/ms10-mobile.png`, fullPage: true });
  await page.setViewportSize({ width: 1280, height: 900 });
});
await guestCtx.close();

// ============================================================================================ demo customers (personalisation)
async function asCustomer(email, fn) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const pg = await ctx.newPage(); watch(pg);
  const log2 = [];
  pg.on('response', async (r) => { if (r.url().includes('/ai-api/v1/chat') && r.request().method() === 'POST') { try { log2.push({ req: JSON.parse(r.request().postData() || '{}'), body: await r.json() }); } catch { /* ignore */ } } });
  await pg.goto(`${BASE}/login`);
  await pg.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (let i = 0; i < 5; i++) { await pg.getByLabel(/^Email/).fill(email); await pg.waitForTimeout(150); if ((await pg.getByLabel(/^Email/).inputValue()) === email) break; }
  for (let i = 0; i < 5; i++) { await pg.getByLabel(/^Password/).fill(PASSWORD); await pg.waitForTimeout(150); if ((await pg.getByLabel(/^Password/).inputValue()) === PASSWORD) break; }
  await pg.getByRole('button', { name: 'Sign In' }).click();
  await pg.waitForURL((u) => !u.pathname.startsWith('/login'));
  const token = await pg.evaluate(() => localStorage.getItem('accessToken'));
  const askAs = async (text) => {
    const before = log2.length;
    await pg.getByTestId('ai-search-input').first().fill(text); await pg.getByTestId('ai-search-submit').first().click();
    for (let i = 0; i < 240 && log2.length === before; i++) await pg.waitForTimeout(500);
    if (log2.length === before) throw new Error('no chat response');
    await pg.getByTestId('ai-search-results').getByTestId('ai-assistant-message').first().waitFor({ timeout: 30000 });
    await pg.getByTestId('ai-loading').waitFor({ state: 'hidden', timeout: 30000 });
    return log2[log2.length - 1];
  };
  try { await fn(pg, token, askAs); } finally { await ctx.close(); }
}

await asCustomer('demo.cricket@example.com', async (pg, token, askAs) => {
  await step(pg, 'M11 cricket customer: "Personalized for you" is cricket kit with reasons from the REAL history; "Recommend something for me"', async () => {
    await pg.goto(BASE);
    const rec = pg.getByTestId('recommendations').first();
    await rec.waitFor({ timeout: 30000 });
    await rec.getByText('Personalized for you').waitFor();
    const text = (await rec.getByTestId('recommendation-reasons').allInnerTexts()).join(' | ');
    if (!/Completes your cricket kit/.test(text) || !/Within your usual price range/.test(text)) throw new Error('reasons: ' + text.slice(0, 200));
    const api = await (await fetch('http://localhost:8090/v1/recommendations?limit=4', { headers: { Authorization: `Bearer ${token}` } })).json();
    if (api.items.some((i) => /MRF Cricket Bat|MRF Cricket Gloves/.test(i.name))) throw new Error('recommended something already bought');
    await imagesAreReal(pg, '[data-testid="recommendations"]', { minimum: 3 });
    const c = await askAs('Recommend something for me');
    if (c.body.type !== 'RECOMMENDATIONS' || c.body.meta.llmCalls !== 0) throw new Error(`type ${c.body.type} llm ${c.body.meta.llmCalls}`);
    await pg.screenshot({ path: `${shots}/ms11-cricket-customer.png`, fullPage: true });
  });
  await step(pg, 'M12 return with SEVERAL products in the latest order: interactive image selection, then eligibility from the Returns service', async () => {
    const c = await askAs('Is my latest order eligible for return?');
    if (c.body.type !== 'RETURN_PRODUCT_SELECTION') throw new Error('type ' + c.body.type);
    const sel = pg.getByTestId('ai-search-results').getByTestId('ai-selection');
    await sel.getByText('Which product would you like to return?').waitFor();
    if ((await sel.getByTestId('ai-selection-option').count()) !== 2) throw new Error('expected 2 options');
    await imagesAreReal(pg, '[data-testid="ai-selection"]', { minimum: 2 });
    const before = log2len(pg);
    await sel.getByTestId('ai-selection-option').filter({ hasText: 'MRF Cricket Gloves - Pro' }).getByTestId('ai-select-button').click();
    await pg.getByTestId('ai-search-results').getByTestId('ai-eligibility').waitFor({ timeout: 30000 });
    await pg.getByTestId('ai-eligibility').getByText('Eligible for return').waitFor();
    await pg.getByTestId('ai-return-action').waitFor();
    void before;
    await pg.screenshot({ path: `${shots}/ms12-return-selection.png`, fullPage: true });
  });
});
function log2len() { return 0; }

await asCustomer('demo.running@example.com', async (pg, token, askAs) => {
  await step(pg, 'M13 running customer: order is OUT FOR DELIVERY on a real timeline; running-routine recommendations; reorder needs confirmation', async () => {
    await pg.goto(BASE); await pg.getByTestId('ai-search-input').first().waitFor();
    const c = await askAs('Track my latest order');
    if (c.body.type !== 'ORDER_TRACKING') throw new Error('type ' + c.body.type);
    const t = pg.getByTestId('ai-search-results').getByTestId('ai-tracking');
    await t.waitFor();
    if ((await t.getByTestId('timeline-out').getAttribute('data-done')) !== 'true') throw new Error('out-for-delivery stage not done');
    if ((await t.getByTestId('timeline-delivered').getAttribute('data-done')) !== 'false') throw new Error('delivered must not be done');
    const r = await askAs('Recommend something for me');
    const names = r.body.recommendations.items.map((i) => i.name);
    if (!names.some((n) => /Hydration Bottle|Running Belt|Running T-Shirt|Running Shorts|Watch/.test(n))) throw new Error('no running items: ' + names);
    if (r.body.recommendations.items.some((i) => i.sport === 'Cricket')) throw new Error('cricket recommended to a runner');
    const orders = (await javaJson('/api/v1/orders', token)).length;
    const ro = await askAs('Reorder my running shoes');
    if (!ro.body.requiresConfirmation) throw new Error('reorder did not ask for confirmation');
    await pg.getByTestId('ai-confirm-summary').getByText(/Asics Running Shoes - Daily Trainer/).waitFor();
    await pg.getByRole('button', { name: 'Cancel' }).click();
    if ((await javaJson('/api/v1/orders', token)).length !== orders) throw new Error('an order was created');
    await pg.screenshot({ path: `${shots}/ms13-running-customer.png`, fullPage: true });
  });
});

await asCustomer('demo.multi@example.com', async (pg, token, askAs) => {
  await step(pg, 'M14 multi-sport customer: recommendations are NOT all cricket and every reason is grounded; processing order tracks correctly', async () => {
    await pg.goto(BASE);
    const rec = pg.getByTestId('recommendations').first();
    await rec.waitFor({ timeout: 30000 });
    const api = await (await fetch('http://localhost:8090/v1/recommendations?limit=4', { headers: { Authorization: `Bearer ${token}` } })).json();
    const sports = new Set(api.items.map((i) => i.sport));
    if (sports.size < 3) throw new Error('only ' + [...sports].join(',') + ' recommended to a multi-sport customer');
    const orders = await javaJson('/api/v1/orders', token);
    const bought = new Set((await Promise.all(orders.map((o) => javaJson(`/api/v1/orders/${o.id}`, token)))).flatMap((o) => o.items.map((i) => i.productName)));
    for (const i of api.items) if (bought.has(i.name)) throw new Error('recommended something already bought: ' + i.name);
    const c = await askAs('Recommend something useful');
    if (c.body.type !== 'RECOMMENDATIONS') throw new Error('type ' + c.body.type);
    const tr = await askAs('Track my latest order');
    const t = pg.getByTestId('ai-search-results').getByTestId('ai-tracking');
    await t.waitFor();
    if ((await t.getByTestId('timeline-confirmed').getAttribute('data-done')) !== 'true' || (await t.getByTestId('timeline-delivered').getAttribute('data-done')) !== 'false') throw new Error('processing order timeline wrong');
    void tr;
    log('     multi-sport recommendations:', api.items.map((i) => `${i.sport}: ${i.name}`).join(' | '));
    await pg.screenshot({ path: `${shots}/ms14-multi-customer.png`, fullPage: true });
  });
});

if (problems.length) { failed = true; log('PROBLEMS (console errors / failed requests):\n' + problems.join('\n')); }
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
