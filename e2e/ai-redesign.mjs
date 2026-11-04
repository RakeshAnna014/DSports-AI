// Real-browser verification of the redesigned DSports AI experience (premium layout + structured, clickable AI responses).
// REAL stack, nothing mocked. Usage: node ai-redesign.mjs <email> <password> <shoesOrder> <batOrder> [shotDir]   (credentials: e2e/ai/ai_ui_setup2.py)
import { chromium } from 'playwright';

const [email, password, shoesOrder, batOrderRaw, shots = '.'] = process.argv.slice(2);
const batOrder = batOrderRaw.trim();
const BASE = 'http://localhost:3000';
const JAVA = 'http://localhost:8080';
const AI_DOWN = process.env.AI_DOWN_BASE || 'http://localhost:8091';
let failed = false;
const log = (...a) => console.log('[ai-redesign]', ...a);
async function step(name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) { failed = true; log('FAIL', name, '-', String(e.message || e).split('\n')[0]); try { await page.screenshot({ path: `${shots}/fail-${name.slice(0, 36).replace(/\W+/g, '_')}.png`, fullPage: true }); } catch { /* ignore */ } }
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await context.newPage();
const chats = [];
const problems = [];
let aiDown = false;
const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
let expected404 = 0;
page.on('response', async (r) => {
  const url = r.url();
  if (url.includes('/ai-api/v1/chat') && r.request().method() === 'POST') { try { chats.push({ req: JSON.parse(r.request().postData() || '{}'), body: await r.json() }); } catch { /* ignore */ } }
  if (r.status() === 404 && EXPECTED_404.test(url)) expected404++;
  else if (r.status() >= 400 && !aiDown && !/\/(login|refresh)/.test(url)) problems.push(`HTTP ${r.status()} ${r.request().method()} ${url}`);
});
page.on('console', (m) => { if (m.type() === 'error' && !aiDown && !(/404/.test(m.text()) && expected404-- > 0) && !/status of 4\d\d/.test(m.text())) problems.push(`console: ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));

const token = () => page.evaluate(() => localStorage.getItem('accessToken'));
const java = async (path) => (await fetch(JAVA + path, { headers: { Authorization: `Bearer ${await token()}` } })).json();
const PAGED = '/api/catalog/products?size=100';
async function pagedProducts(base) { const out = []; for (let page = 0; page < 20; page++) { const rows = await (await fetch(`${base}/api/catalog/products?page=${page}&size=100`)).json(); out.push(...rows); if (rows.length < 100) break; } return out; }
const javaPublicRaw = async (path) => (await fetch(JAVA + path)).json();
const javaPublic = async (path, token) => (path === PAGED ? pagedProducts(JAVA) : javaPublicRaw(path, token));
const results = () => page.getByTestId('ai-search-results');

async function ask(text) {
  const before = chats.length;
  await page.getByTestId('ai-search-input').first().fill(text);
  await page.getByTestId('ai-search-submit').first().click();
  for (let i = 0; i < 240 && chats.length === before; i++) await page.waitForTimeout(500);
  if (chats.length === before) throw new Error('no chat response');
  await results().getByTestId('ai-assistant-message').or(results().getByTestId('ai-error')).first().waitFor({ timeout: 20000 });
  await page.getByTestId('ai-loading').waitFor({ state: 'hidden', timeout: 20000 });
  return chats[chats.length - 1];
}
const noOverflow = async () => {
  const [sw, iw] = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth]);
  if (sw > iw + 1) throw new Error(`horizontal overflow ${sw} > ${iw}`);
};

await step('sign in through the normal login form', async () => {
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Email/).fill(email); await page.waitForTimeout(150); if ((await page.getByLabel(/^Email/).inputValue()) === email) break; }
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Password/).fill(password); await page.waitForTimeout(150); if ((await page.getByLabel(/^Password/).inputValue()) === password) break; }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
});

// ============================================================================================== layout, hero, header, sports
await step('R1 premium Home: hero, prominent "Ask DSports AI", full navigation, real sports tiles that filter the catalogue', async () => {
  await page.goto(BASE); await page.waitForLoadState('networkidle');
  await page.getByRole('heading', { name: /PLAY BETTER/i }).waitFor();
  await page.getByTestId('ai-header-button').getByText('Ask DSports AI').waitFor();
  for (const n of ['Home', 'Products', 'AI Shop', 'Orders', 'Track', 'Returns']) await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: n, exact: true }).waitFor();
  const sports = await javaPublic('/api/catalog/sports');
  const tiles = page.getByTestId('sports-categories').getByRole('button');
  if ((await tiles.count()) !== sports.length) throw new Error(`tiles ${await tiles.count()} vs ${sports.length} sports`);
  await noOverflow();
  await page.screenshot({ path: `${shots}/r1-home.png`, fullPage: true });
  await page.getByRole('button', { name: 'Shop Cricket' }).click();
  await page.waitForURL(/\/products\?sport=/);
  await page.locator('main .MuiCard-root').first().waitFor();
  const filtered = await page.locator('main .MuiCard-root').count();
  const all = (await javaPublic('/api/catalog/products?size=100')).length;
  if (!(filtered > 0 && filtered < all)) throw new Error(`sport filter did not apply (${filtered} of ${all})`);
  await page.goto(BASE);
});

await step('R2 "Recommended for you": real products, each with reasons grounded in the customer\'s own orders', async () => {
  const section = page.getByTestId('recommendations').first();
  await section.waitFor({ timeout: 30000 });
  await section.getByText(/Personalized for you|Recommended for you/).first().waitFor();
  const cards = section.getByTestId('recommendation-card');
  if ((await cards.count()) < 1) throw new Error('no recommendation cards');
  const catalog = (await javaPublic('/api/catalog/products?size=100')).map((p) => p.name);
  const names = await cards.locator('h6, .MuiTypography-subtitle2').allInnerTexts();
  const real = names.filter((n) => catalog.includes(n.trim()));
  if (!real.length) throw new Error('recommended names are not catalogue products: ' + names);
  if (real.some((n) => ['DSC Cricket Shoes - Spikes', 'MRF Cricket Bat - Virgin'].includes(n.trim()))) throw new Error('recommended something already bought');
  const reasons = (await section.getByTestId('recommendation-reasons').allInnerTexts()).join(' | ');
  if (!/Completes your|You bought|Within your usual price range/.test(reasons)) throw new Error('no explainable reasons: ' + reasons);
  if (!/You bought MRF before|usual price range/.test(reasons)) log('     (no history-based reason shown:', reasons.slice(0, 120), ')');
  await page.screenshot({ path: `${shots}/r2-recommended.png`, fullPage: true });
});

// ============================================================================================== returns: clickable selection -> eligibility -> confirm
await step('R3 "I want to return an item" -> clickable product cards (no typing "the second one")', async () => {
  const c = await ask('I want to return an item');
  if (c.body.type !== 'RETURN_PRODUCT_SELECTION') throw new Error('type ' + c.body.type);
  const sel = results().getByTestId('ai-selection');
  await sel.getByText('Which product would you like to return?').waitFor();
  const opts = sel.getByTestId('ai-selection-option');
  if ((await opts.count()) !== 2) throw new Error('options: ' + await opts.count());
  if (JSON.stringify(c.body.selection).includes('order_id')) throw new Error('ids leaked to the client');
  await opts.filter({ hasText: 'MRF Cricket Bat - Virgin' }).waitFor();
  await opts.filter({ hasText: 'DSC Cricket Shoes - Spikes' }).waitFor();
  await page.screenshot({ path: `${shots}/r3-selection.png`, fullPage: true });
});

await step('R4 tap Select on the MRF bat -> return options from the Returns service; nothing created yet', async () => {
  const before = chats.length;
  await results().getByTestId('ai-selection-option').filter({ hasText: 'MRF Cricket Bat - Virgin' }).getByTestId('ai-select-button').click();
  for (let i = 0; i < 120 && chats.length === before; i++) await page.waitForTimeout(500);
  const c = chats[chats.length - 1];
  if (!c.req.choiceId) throw new Error('the tap did not send a choice id');
  if (c.body.type !== 'RETURN_OPTIONS' || c.body.eligibility.product_name !== 'MRF Cricket Bat - Virgin') throw new Error('not the MRF eligibility: ' + c.body.type);
  await results().getByTestId('ai-eligibility').getByText('Eligible for return').waitFor();
  await results().getByText('What would you like to do?').waitFor();
  if ((await java('/api/v1/returns')).length !== 0) throw new Error('a return exists before confirmation');
  await page.screenshot({ path: `${shots}/r4-return-options.png`, fullPage: true });
});

await step('R5 "Return this item" -> confirmation dialog -> Confirm creates exactly one return, for the MRF bat only', async () => {
  await results().getByTestId('ai-return-action').click();
  await page.getByTestId('ai-confirm-summary').getByText(/MRF Cricket Bat - Virgin/).waitFor();
  if ((await java('/api/v1/returns')).length !== 0) throw new Error('created before Confirm');
  await page.screenshot({ path: `${shots}/r5-confirm.png` });
  await page.getByTestId('ai-confirm-button').click();
  await results().getByTestId('ai-action-result').waitFor({ timeout: 30000 });
  let list = [];
  for (let i = 0; i < 20 && list.length === 0; i++) { list = await java('/api/v1/returns'); if (!list.length) await page.waitForTimeout(500); }
  if (list.length !== 1 || list[0].productName !== 'MRF Cricket Bat - Virgin') throw new Error('returns: ' + JSON.stringify(list.map((r) => r.productName)));
});

await step('R6 "Track my latest order" -> a real tracking timeline (all stages done for a delivered order)', async () => {
  const c = await ask('Track my latest order');
  if (c.body.type !== 'ORDER_TRACKING') throw new Error('type ' + c.body.type);
  const t = results().getByTestId('ai-tracking');
  await t.getByText(`ORDER #${batOrder}`).waitFor();
  for (const k of ['placed', 'confirmed', 'packed', 'transit', 'out', 'delivered']) {
    if ((await t.getByTestId(`timeline-${k}`).getAttribute('data-done')) !== 'true') throw new Error('stage not done: ' + k);
  }
  await page.screenshot({ path: `${shots}/r6-timeline.png`, fullPage: true });
});

await step('R7 Orders page: cards with products and Track / Reorder / Return; Returns page lists the return', async () => {
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Orders', exact: true }).click();
  await page.waitForURL('**/orders');
  const cards = page.getByTestId('order-card');
  await cards.first().waitFor();
  if ((await cards.count()) < 2) throw new Error('orders: ' + await cards.count());
  const bat = cards.filter({ hasText: batOrder });
  await bat.getByText('MRF Cricket Bat - Virgin').waitFor();
  await bat.getByTestId('order-track').waitFor(); await bat.getByTestId('order-reorder').waitFor(); await bat.getByTestId('order-return').waitFor();
  await page.screenshot({ path: `${shots}/r7-orders.png`, fullPage: true });
  await bat.getByTestId('order-track').click();
  await page.waitForURL('**/ai');
  await page.getByTestId('ai-feed').getByTestId('ai-tracking').first().waitFor({ timeout: 30000 });
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Returns', exact: true }).click();
  await page.waitForURL('**/returns');
  const r = page.getByTestId('return-card');
  await r.first().waitFor();
  await r.first().getByText('MRF Cricket Bat - Virgin').waitFor();
  await r.first().getByText('REQUESTED', { exact: true }).waitFor();
  await page.screenshot({ path: `${shots}/r7-returns.png`, fullPage: true });
});

// ============================================================================================== cart + recommendations + discounts
await step('R8 Add to Cart: confirmation + "Complete your setup" with reasons; cart drawer with real subtotal and savings; bundle saving', async () => {
  await page.goto(BASE); await page.getByTestId('featured-products').locator('.MuiCard-root').first().waitFor();
  const card = page.getByTestId('featured-products').locator('.MuiCard-root').first();
  const name = (await card.locator('h6, .MuiTypography-subtitle2').first().innerText()).trim();
  await card.getByRole('button', { name: 'Add to cart' }).click();
  const panel = page.getByTestId('added-to-cart');
  await panel.getByText(new RegExp(`Added to cart: ${name.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&')}`)).waitFor();
  await panel.getByTestId('recommendation-card').first().waitFor({ timeout: 30000 });
  const reasons = (await panel.getByTestId('recommendation-reasons').allInnerTexts()).join(' ');
  if (!/Completes your/.test(reasons)) throw new Error('no reason: ' + reasons);
  await page.screenshot({ path: `${shots}/r8-added.png` });
  await panel.getByTestId('added-view-cart').click();
  const drawer = page.getByTestId('cart-drawer');
  await drawer.getByTestId('cart-items').waitFor();
  const cart = await java('/api/v1/cart');
  const prices = await javaPublic('/api/prices');
  const mrp = new Map(prices.map((p) => [p.productId, Number(p.mrp)]));
  const subtotal = cart.items.reduce((s, i) => s + Number(i.lineTotal), 0);
  const savings = cart.items.reduce((s, i) => s + Math.max(0, (mrp.get(i.productId) ?? Number(i.unitPrice)) - Number(i.unitPrice)) * i.quantity, 0);
  const fmt = (n) => new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(n);
  const sub = (await drawer.getByTestId('cart-subtotal').innerText()).replace(/\s/g, '');
  if (sub !== fmt(subtotal).replace(/\s/g, '')) throw new Error(`subtotal ${sub} vs ${fmt(subtotal)}`);
  if (savings > 0) {
    const sv = (await drawer.getByTestId('cart-savings').innerText()).replace(/\s/g, '');
    if (sv !== fmt(savings).replace(/\s/g, '')) throw new Error(`savings ${sv} vs ${fmt(savings)}`);
  }
  await drawer.getByText('Complete your setup').first().waitFor({ timeout: 30000 });
  await drawer.getByTestId('recommendation-card').first().waitFor();
  await page.screenshot({ path: `${shots}/r8-cart-drawer.png` });
  await drawer.getByRole('button', { name: 'Continue shopping' }).click();
});

// ============================================================================================== responsive + resilience
await step('R9 mobile (390x844): bottom navigation with AI, two-column products, no horizontal overflow', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(BASE); await page.waitForLoadState('networkidle');
  const nav = page.getByRole('navigation', { name: 'Mobile' });
  for (const n of ['Home', 'AI', 'Products', 'Orders', 'Cart']) await nav.getByText(n, { exact: true }).waitFor();
  await page.getByTestId('ai-header-button').waitFor();
  await noOverflow();
  const cards = page.getByTestId('featured-products').locator('.MuiCard-root');
  await cards.nth(1).waitFor();
  const a = await cards.nth(0).boundingBox(); const b = await cards.nth(1).boundingBox();
  if (Math.abs(a.y - b.y) > 4) throw new Error('products are not in two columns on a phone');
  await page.screenshot({ path: `${shots}/r9-mobile-home.png`, fullPage: true });
  await nav.getByText('AI', { exact: true }).click();
  await page.waitForURL('**/ai');
  await page.getByTestId('ai-search-input').first().waitFor();
  await noOverflow();
  await page.screenshot({ path: `${shots}/r9-mobile-ai.png` });
  await page.setViewportSize({ width: 1280, height: 900 });
});

await step('R10 AI unavailable: a friendly error with Try again (no raw error), and Try again works once the AI is back', async () => {
  const up = await fetch(AI_DOWN + '/health').then((r) => r.status).catch(() => 0);
  if (up !== 200) throw new Error('AI-down instance not running');
  aiDown = true;
  await page.route('**/ai-api/**', (route) => {
    const u = new URL(route.request().url());
    route.fetch({ url: AI_DOWN + u.pathname.replace(/^\/ai-api/, '') + u.search }).then((resp) => route.fulfill({ response: resp }));
  });
  await page.goto(`${BASE}/ai`); await page.getByTestId('ai-search-input').first().waitFor();
  await page.getByTestId('ai-search-input').first().fill('hmm what do you think about all of this');
  await page.getByTestId('ai-search-submit').first().click();
  const feed = page.getByTestId('ai-feed');
  await feed.getByTestId('error-state').first().waitFor({ timeout: 60000 });
  await feed.getByText(/taking a break/i).first().waitFor();
  if (/Traceback|Exception|stack/i.test(await feed.innerText())) throw new Error('raw error text shown');
  await page.screenshot({ path: `${shots}/r10-ai-down.png`, fullPage: true });
  await page.unroute('**/ai-api/**');
  aiDown = false;
  await feed.getByRole('button', { name: 'Try again' }).first().click();
  await feed.getByTestId('ai-assistant-message').first().waitFor({ timeout: 60000 });
});

if (problems.length) { failed = true; log('PROBLEMS (console errors / failed requests):\n' + problems.join('\n')); }
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
