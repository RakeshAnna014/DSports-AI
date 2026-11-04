// Browser verification of PRODUCT-CONTEXTUAL AI conversation. REAL stack, nothing mocked:
//   React :3000 -> /ai-api -> Python :8090 (Customer Agent, LangGraph, tools, RAG) -> Java :8080, Gemini, pgvector.
// Usage: node ai-product-context.mjs <email> <password> [shotDir]      (credentials from e2e/ai/ai_ui_setup.py)
import { chromium } from 'playwright';

const [email, password, shots = '.'] = process.argv.slice(2);
const BASE = 'http://localhost:3000';
const JAVA = 'http://localhost:8080';
let failed = false;
const log = (...a) => console.log('[ai-ctx]', ...a);
async function step(name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) { failed = true; log('FAIL', name, '-', String(e.message || e).split('\n')[0]); try { await page.screenshot({ path: `${shots}/fail-${name.slice(0, 40).replace(/\W+/g, '_')}.png`, fullPage: true }); } catch { /* ignore */ } }
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
const page = await context.newPage();

const chats = [];
const problems = [];
const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
let expected404 = 0;
page.on('response', async (r) => {
  const url = r.url();
  if (url.includes('/ai-api/v1/chat') && r.request().method() === 'POST') {
    try { chats.push({ req: JSON.parse(r.request().postData() || '{}'), status: r.status(), body: await r.json() }); } catch { /* ignore */ }
  }
  if (r.status() === 404 && EXPECTED_404.test(url)) expected404++;
  else if (r.status() >= 400 && !/\/(login|refresh)/.test(url)) problems.push(`HTTP ${r.status()} ${r.request().method()} ${url}`);
});
page.on('console', (m) => { if (m.type() === 'error' && !(/404/.test(m.text()) && expected404-- > 0) && !/status of 4\d\d/.test(m.text())) problems.push(`console: ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));

const tools = (c) => (c.body.meta.tools || []).map((t) => t.tool);
const names = (c) => (c.body.products || []).map((p) => p.name);
const PAGED = '/api/catalog/products?size=100';
async function pagedProducts(base) { const out = []; for (let page = 0; page < 20; page++) { const rows = await (await fetch(`${base}/api/catalog/products?page=${page}&size=100`)).json(); out.push(...rows); if (rows.length < 100) break; } return out; }
const javaJsonRaw = async (path, token) => (await fetch(JAVA + path, token ? { headers: { Authorization: `Bearer ${token}` } } : {})).json();
const javaJson = async (path, token) => (path === PAGED ? pagedProducts(JAVA) : javaJsonRaw(path, token));
const results = () => page.getByTestId('ai-search-results');
const chip = () => page.getByTestId('ai-context');

async function ask(text) {
  const before = chats.length;
  await page.getByTestId('ai-search-input').fill(text);
  await page.getByTestId('ai-search-submit').click();
  for (let i = 0; i < 240 && chats.length === before; i++) await page.waitForTimeout(500);
  if (chats.length === before) throw new Error('no /ai-api/v1/chat response');
  await results().getByTestId('ai-assistant-message').or(results().getByTestId('ai-error')).first().waitFor({ timeout: 20000 });
  await page.getByTestId('ai-loading').waitFor({ state: 'hidden', timeout: 20000 });
  return chats[chats.length - 1];
}
const catalog = async () => javaJson('/api/catalog/products?size=100');
const idOf = async (name) => (await catalog()).find((p) => p.name === name).id;

// ------------------------------------------------------------------------------------------------ sign in
await step('sign in through the normal login form', async () => {
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Email/).fill(email); await page.waitForTimeout(150); if ((await page.getByLabel(/^Email/).inputValue()) === email) break; }
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Password/).fill(password); await page.waitForTimeout(150); if ((await page.getByLabel(/^Password/).inputValue()) === password) break; }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
  await page.goto(BASE); await page.getByTestId('ai-search-input').waitFor();
});

let q1, mrfId;
// ================================================================================================ 1-2: search, results, Ask AI
await step('1-2. search for a beginner bat: real MRF + SS appear as verified product cards', async () => {
  q1 = await ask('I am looking for a beginner cricket bat under ₹8,000.');
  if (!tools(q1).includes('search_products')) throw new Error('no search');
  if (JSON.stringify(names(q1)) !== JSON.stringify(['MRF Cricket Bat - Virgin', 'SS Cricket Bat - Prime'])) throw new Error('unexpected products: ' + names(q1));
  mrfId = await idOf('MRF Cricket Bat - Virgin');
  if (q1.body.products[0].product_id !== mrfId) throw new Error('card id is not the Java id');
  if (await chip().count()) throw new Error('a context chip is shown before any product was chosen');
  await results().getByTestId('ai-product-card').first().waitFor();
  if (!(await page.getByTestId('ai-search-input').getAttribute('placeholder')).includes('follow-up')) throw new Error('general placeholder expected');
  await page.screenshot({ path: `${shots}/p1-results.png`, fullPage: true });
});

await step('3. click "Ask AI" on the MRF card: context chip, new placeholder, input focused; no separate chatbot opened', async () => {
  const card = results().getByTestId('ai-product-card').filter({ hasText: 'MRF Cricket Bat - Virgin' });
  await card.getByTestId('ai-ask-about').click();
  await chip().getByText('Asking about: MRF Cricket Bat - Virgin').waitFor();
  const ph = await page.getByTestId('ai-search-input').getAttribute('placeholder');
  if (ph !== 'Ask about this product, compare it, or find alternatives...') throw new Error('placeholder: ' + ph);
  if (!(await page.getByTestId('ai-search-input').evaluate((el) => el === document.activeElement))) throw new Error('search input not focused');
  if (await page.getByTestId('ai-panel').isVisible().catch(() => false)) throw new Error('the chat drawer opened');
  await page.screenshot({ path: `${shots}/p2-ask-ai.png` });
});

await step('4. "Is this good for tennis-ball cricket?" -> about the MRF bat: live product + RAG with sources; only the id is sent', async () => {
  const c = await ask('Is this good for tennis-ball cricket?');
  const mrf = await idOf('MRF Cricket Bat - Virgin');
  if (c.req.selectedProductId !== mrf) throw new Error('the product id was not sent');
  const extra = Object.keys(c.req).filter((k) => !['message', 'conversationId', 'selectedProductId', 'clearProductContext', 'choiceId'].includes(k));
  if (extra.length) throw new Error('unexpected request fields: ' + extra);
  if (c.req.conversationId !== q1.body.conversationId) throw new Error('conversation not kept');
  if (c.body.intent !== 'PRODUCT_KNOWLEDGE') throw new Error('intent ' + c.body.intent);
  if (!tools(c).includes('get_product') || !tools(c).includes('get_product_knowledge')) throw new Error('tools: ' + tools(c));
  if (!c.body.sources.length) throw new Error('no sources');
  if (c.body.context.selectedProduct.name !== 'MRF Cricket Bat - Virgin') throw new Error('server context: ' + JSON.stringify(c.body.context));
  await results().getByTestId('ai-sources').waitFor();
  await results().getByTestId('ai-verified').getByText('MRF Cricket Bat - Virgin').first().waitFor();       // the verified card comes from DSports
  await results().getByText(/AI explanation/i).first().waitFor();                                            // the RAG explanation is labelled separately
  await chip().getByText('Asking about: MRF Cricket Bat - Virgin').waitFor();
  log('     sources=', c.body.sources.map((s) => s.source).join(', '), '|', c.body.message.slice(0, 140).replace(/\n/g, ' '));
  await page.screenshot({ path: `${shots}/p3-tennis.png`, fullPage: true });
});

await step('5. "What is the weight?" -> the authoritative DSports weight (Tier 0, no model call)', async () => {
  const c = await ask('What is the weight?');
  if (c.body.intent !== 'PRODUCT_DETAIL' || c.body.meta.llmCalls !== 0) throw new Error(`intent ${c.body.intent} llm ${c.body.meta.llmCalls}`);
  const detail = await javaJson(`/api/catalog/products/${await idOf('MRF Cricket Bat - Virgin')}`);
  const grams = String(detail.weightUnit || 'KG').toUpperCase().startsWith('G') && !String(detail.weightUnit).toUpperCase().startsWith('KG') ? Number(detail.weight) : Number(detail.weight) * 1000;
  if (Math.round(c.body.products[0].weight_g) !== Math.round(grams)) throw new Error(`weight ${c.body.products[0].weight_g} vs java ${grams}`);
  if (!c.body.message.includes('1.2 kg')) throw new Error('message: ' + c.body.message);
  await results().getByText(/weighs 1\.2 kg/).first().waitFor();
});

await step('6. "Show me reviews." -> get_product_reviews; the count matches the DSports review summary', async () => {
  const c = await ask('Show me reviews.');
  if (c.body.intent !== 'PRODUCT_REVIEWS' || !tools(c).includes('get_product_reviews')) throw new Error('intent ' + c.body.intent);
  const summary = await javaJson(`/api/v1/reviews/product/${await idOf('MRF Cricket Bat - Virgin')}/summary`);
  if ((c.body.reviews.total_reviews || 0) !== (summary.totalReviews || 0)) throw new Error(`reviews ${c.body.reviews.total_reviews} vs java ${summary.totalReviews}`);
  if (JSON.stringify(c.body.reviews).toLowerCase().includes('userid')) throw new Error('reviewer identity leaked');
  await results().getByTestId('ai-reviews').waitFor();
});

await step('7. "Compare it with the SS bat." -> comparison of the two real products; chip and placeholder switch to "compare"', async () => {
  const c = await ask('Compare it with the SS bat.');
  if (c.body.intent !== 'COMPARE' || !c.body.comparison) throw new Error('no comparison: ' + c.body.intent);
  if (JSON.stringify(c.body.comparison.products.map((p) => p.name)) !== JSON.stringify(['MRF Cricket Bat - Virgin', 'SS Cricket Bat - Prime'])) throw new Error('products');
  await results().getByTestId('ai-comparison').waitFor();
  await chip().getByText(/Comparing: MRF Cricket Bat - Virgin vs SS Cricket Bat - Prime/).waitFor();
  const ph = await page.getByTestId('ai-search-input').getAttribute('placeholder');
  if (ph !== 'Ask about these products or compare them...') throw new Error('placeholder: ' + ph);
  // the numbers in the table are DSports'
  const prices = await javaJson('/api/prices');
  const byId = new Map(prices.map((p) => [p.productId, Number(p.sellingPrice)]));
  const priceRow = c.body.comparison.rows.find((r) => r.attribute === 'Price').values;
  const expected = [await idOf('MRF Cricket Bat - Virgin'), await idOf('SS Cricket Bat - Prime')].map((id) => byId.get(id));
  if (!priceRow.every((v, i) => v.replace(/[^\d.]/g, '').replace(/\.00$/, '') === String(expected[i]).replace(/\.0$/, ''))) throw new Error(`prices ${priceRow} vs ${expected}`);
  await page.screenshot({ path: `${shots}/p4-compare.png`, fullPage: true });
});

await step('8. "Which one is better for a beginner?" -> about those two products; "Which one is lighter?" uses their real weights', async () => {
  const c = await ask('Which one is better for a beginner?');
  const shown = new Set(['MRF Cricket Bat - Virgin', 'SS Cricket Bat - Prime']);
  for (const p of c.body.comparison.products) if (!shown.has(p.name)) throw new Error('other product: ' + p.name);
  if (!/MRF Cricket Bat - Virgin/.test(c.body.message)) throw new Error('recommendation: ' + c.body.message);
  const l = await ask('Which one is lighter?');
  if (!l.body.comparison || l.body.comparison.products.length !== 2) throw new Error('not a comparison of both');
  const weights = l.body.comparison.rows.find((r) => r.attribute === 'Weight').values;
  if (JSON.stringify(weights) !== JSON.stringify(['1.2 kg', '1.15 kg'])) throw new Error('weights ' + weights);
  await page.screenshot({ path: `${shots}/p5-lighter.png`, fullPage: true });
});

await step('9. "Show me a cheaper alternative." -> a real catalogue search; when nothing is cheaper it says so (no invented product)', async () => {
  const c = await ask('Show me a cheaper alternative.');
  if (!tools(c).includes('search_products')) throw new Error('no search: ' + tools(c));
  const all = await catalog();
  const prices = await javaJson('/api/prices');
  const price = new Map(prices.map((p) => [p.productId, Number(p.sellingPrice)]));
  const bats = all.filter((p) => /cricket bat/i.test(p.name));
  const cheapest = Math.min(...bats.map((p) => price.get(p.id)));
  const mrfPrice = price.get(await idOf('MRF Cricket Bat - Virgin'));
  if (cheapest < mrfPrice) { if (!c.body.products.length) throw new Error('a cheaper bat exists but none was shown'); }
  else if (c.body.products.length || !/no cheaper/i.test(c.body.message)) throw new Error('MRF is the cheapest bat: expected an honest "no cheaper" answer: ' + c.body.message);
  for (const p of c.body.products) if (!(p.price < mrfPrice)) throw new Error('not cheaper: ' + p.name);
  log('     ', c.body.message.slice(0, 150).replace(/\n/g, ' '));
});

await step('9b. alternatives that DO exist: Ask AI on the SS bat, then "Is there a cheaper alternative?" -> MRF, cheaper by DSports prices', async () => {
  await page.getByRole('link', { name: 'Products', exact: true }).first().click(); await page.waitForURL('**/products');
  await page.getByPlaceholder('Search products...').fill('SS Cricket Bat'); await page.waitForTimeout(600);
  await page.locator('main .MuiCard-root').filter({ hasText: 'SS Cricket Bat - Prime' }).getByTestId('ask-ai-button').click();
  await chip().getByText('Asking about: SS Cricket Bat - Prime').waitFor();
  const c = await ask('Is there a cheaper alternative?');
  if (c.req.selectedProductId !== (await idOf('SS Cricket Bat - Prime'))) throw new Error('SS id not sent');
  const prices = await javaJson('/api/prices'); const price = new Map(prices.map((p) => [p.productId, Number(p.sellingPrice)]));
  const ss = price.get(await idOf('SS Cricket Bat - Prime'));
  if (!names(c).includes('MRF Cricket Bat - Virgin')) throw new Error('expected MRF: ' + names(c));
  for (const p of c.body.products) if (!(price.get(p.product_id) < ss) || Number(p.price) !== price.get(p.product_id)) throw new Error('not cheaper / wrong price: ' + p.name);
  await page.getByPlaceholder('Search products...').fill('');
  await page.screenshot({ path: `${shots}/p6-alternative.png`, fullPage: true });
});

// ================================================================================================ the exact follow-up conversation
await step('follow-up conversation: search -> which is better -> "Tell me more about the MRF one" -> tennis -> weight -> reviews -> similar but cheaper', async () => {
  await page.goto(BASE); await page.getByTestId('ai-search-input').waitFor();
  await page.getByTestId('ai-search-clear').click().catch(() => undefined);
  const a = await ask('I am looking for a beginner cricket bat under ₹8,000.');
  if (await chip().count()) throw new Error('context left over after Clear');
  const b = await ask('Which one is better?');
  if (!b.body.comparison) throw new Error('no comparison');
  const t = await ask('Tell me more about the MRF one.');
  if (t.body.intent !== 'PRODUCT_DETAIL' || t.body.context.selectedProduct.name !== 'MRF Cricket Bat - Virgin') throw new Error('select: ' + JSON.stringify(t.body.context));
  await chip().getByText('Asking about: MRF Cricket Bat - Virgin').waitFor();
  const e = await ask('Is it good for tennis-ball cricket?');
  if (e.body.intent !== 'PRODUCT_KNOWLEDGE' || !e.body.sources.length) throw new Error('rag: ' + e.body.intent);
  const w = await ask('What is its weight?');
  if (!w.body.message.includes('1.2 kg')) throw new Error('weight: ' + w.body.message);
  const r = await ask('Show me reviews.');
  if (!tools(r).includes('get_product_reviews')) throw new Error('reviews tool');
  const s = await ask('Is there anything similar but cheaper?');
  if (!tools(s).includes('search_products')) throw new Error('no search');
  const ids = new Set([a.body.conversationId, b.body.conversationId, t.body.conversationId, e.body.conversationId, w.body.conversationId, r.body.conversationId, s.body.conversationId]);
  if (ids.size !== 1) throw new Error('conversation id changed');
  await page.screenshot({ path: `${shots}/p7-followups.png`, fullPage: true });
});

// ================================================================================================ product detail page
await step('product detail page: "Ask AI about this product" already knows the product; example chips and questions work', async () => {
  const id = await idOf('MRF Cricket Bat - Virgin');
  await page.goto(`${BASE}/products/${id}`);
  const box = page.getByTestId('product-ai');
  await box.getByTestId('ai-search-title').waitFor();
  await box.getByText('Ask AI about this product').first().waitFor();
  await box.getByTestId('ai-context').getByText('Asking about: MRF Cricket Bat - Virgin').waitFor();
  const before = chats.length;
  await box.getByText('Show me reviews', { exact: true }).click();
  for (let i = 0; i < 120 && chats.length === before; i++) await page.waitForTimeout(500);
  const c = chats[chats.length - 1];
  if (c.req.selectedProductId !== id || c.body.intent !== 'PRODUCT_REVIEWS') throw new Error('detail page did not carry the product: ' + c.body.intent);
  await box.getByTestId('ai-reviews').waitFor();
  const w = await ask('How heavy is it?');
  if (!w.body.message.includes('1.2 kg')) throw new Error('weight: ' + w.body.message);
  await page.screenshot({ path: `${shots}/p8-detail-page.png`, fullPage: true });
});

// ================================================================================================ normal commerce still works
await step('Add to Cart still works from an AI product card (header badge and DSports cart)', async () => {
  await page.goto(BASE); await page.getByTestId('ai-search-input').waitFor();
  const c = await ask('cricket bat');
  if (c.body.meta.llmCalls !== 0) throw new Error('simple search used a model');
  const card = results().getByTestId('ai-product-card').first();
  await card.getByRole('button', { name: 'Add to cart' }).click();
  await card.getByText('Added to cart').waitFor();
  await page.getByLabel('cart').locator('.MuiBadge-badge').getByText('1').waitFor({ timeout: 15000 });
  const token = await page.evaluate(() => localStorage.getItem('accessToken'));
  const cart = JSON.stringify(await javaJson('/api/v1/cart', token));
  if (!cart.includes(c.body.products[0].product_id)) throw new Error('product not in the DSports cart');
});

await step('normal Products page search and filters are untouched and never call the AI service', async () => {
  const before = chats.length;
  await page.getByRole('link', { name: 'Products', exact: true }).first().click(); await page.waitForURL('**/products');
  await page.getByPlaceholder('Search products...').waitFor();
  await page.waitForTimeout(1200);
  const all = await page.locator('main .MuiCard-root').count();
  await page.getByPlaceholder('Search products...').fill('helmet'); await page.waitForTimeout(700);
  const some = await page.locator('main .MuiCard-root').count();
  if (!(some > 0 && some < all)) throw new Error(`no filtering (${all} -> ${some})`);
  if (chats.length !== before) throw new Error('normal search called the AI service');
  await page.screenshot({ path: `${shots}/p9-products-search.png` });
});

await step('security: a forged product id from the browser is ignored by the service (re-read from DSports)', async () => {
  const token = await page.evaluate(() => localStorage.getItem('accessToken'));
  const forged = await fetch(`${BASE}/ai-api/v1/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message: 'How heavy is it?', selectedProductId: '00000000-0000-0000-0000-00000000dead', productName: 'Free Bat', price: 1 }) });
  const body = await forged.json();
  if (body.context.selectedProduct) throw new Error('a forged id became context');
  if (JSON.stringify(body).includes('Free Bat')) throw new Error('client-supplied name leaked into the answer');
});

if (problems.length) { failed = true; log('PROBLEMS (console errors / failed requests):\n' + problems.join('\n')); }
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
