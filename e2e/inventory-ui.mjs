// Real-browser verification of DSports Inventory Intelligence (Phase 2). REAL stack: Chrome, Vite, Java backend, AI service, real Gemini, real mart.
// Nothing is mocked except where a step says "network fault": the browser's request is aborted to see the UI's failure handling.
// Usage: node inventory-ui.mjs <adminEmail> <customerEmail> <password> [shotDir]      (accounts: e2e/ai/inventory_setup.py)
import { chromium } from 'playwright';

const [adminEmail, customerEmail, password, shots = '.'] = process.argv.slice(2);
const BASE = 'http://localhost:3000';
const AI = `${BASE}/ai-api/v1/inventory`;
let failed = false;
const log = (...a) => console.log('[inventory-ui]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true;
    log('FAIL', name, '-', String(e.message || e).split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/inv-fail-${name.slice(0, 36).replace(/\W+/g, '_')}.png`, fullPage: true }); } catch { /* ignore */ }
  }
}
const newPage = async (viewport = { width: 1440, height: 1000 }) => {
  const ctx = await browser.newContext({ viewport });
  const page = await ctx.newPage();
  const problems = [];
  const EXPECTED = /\/api\/v1\/(cart|wishlist)$/;
  page.on('response', (r) => { if (r.status() >= 400 && !EXPECTED.test(r.url()) && !/\/login/.test(r.url())) problems.push(`HTTP ${r.status()} ${r.url()}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !/status of 4\d\d/.test(m.text())) problems.push(`console: ${m.text()}`); });
  return { page, problems, ctx };
};
async function login(page, email) {
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Email/).fill(email); await page.waitForTimeout(120); if ((await page.getByLabel(/^Email/).inputValue()) === email) break; }
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Password/).fill(password); await page.waitForTimeout(120); if ((await page.getByLabel(/^Password/).inputValue()) === password) break; }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
}
const token = (page) => page.evaluate(() => localStorage.getItem('accessToken'));
async function api(page, path, init = {}) {
  const t = await token(page);
  const r = await fetch(`${AI}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...(t ? { Authorization: `Bearer ${t}` } : {}), ...(init.headers || {}) } });
  return { status: r.status, body: await r.json().catch(() => ({})) };
}
const gotoTab = async (page, tab) => { await page.goto(`${BASE}/inventory-ai/${tab}`); await page.getByTestId('inventory-title').waitFor(); };
const num = (t) => Number(String(t).replace(/[^0-9.\-]/g, ''));

// ============================================================================================ customer: storefront still works, inventory is closed
const cust = await newPage();
await step(cust.page, '1. Customer Home still works (AI commerce home, featured products, sports)', async () => {
  await cust.page.goto(BASE);
  await cust.page.getByTestId('sports-categories').waitFor();
  const n = await cust.page.getByTestId('featured-products').locator('.MuiCard-root').count();
  if (n < 4) throw new Error(`featured products: ${n}`);
});
await step(cust.page, '2. Customer AI product search still works (typed request -> verified product cards)', async () => {
  await cust.page.goto(BASE);
  const box = cust.page.getByTestId('ai-search-input');
  await box.fill('Show me cricket bats under 10000');
  await cust.page.getByTestId('ai-search-submit').click();
  await cust.page.getByTestId('ai-product-card').first().waitFor({ timeout: 90_000 });
  const n = await cust.page.getByTestId('ai-product-card').count();
  if (n < 1) throw new Error('no product cards');
});
await step(cust.page, '3. Customer product context still works (Ask AI on a product card knows the product)', async () => {
  await cust.page.goto(`${BASE}/products`);
  await cust.page.getByTestId('ask-ai-button').first().click();
  await cust.page.getByTestId('ai-context').getByText(/^Asking about:/).waitFor({ timeout: 30_000 });
});
await login(cust.page, customerEmail);
await step(cust.page, '4. A signed-in CUSTOMER is blocked from /inventory-ai (no data requested) and the API says 403', async () => {
  const calls = [];
  cust.page.on('request', (r) => { if (r.url().includes('/ai-api/v1/inventory')) calls.push(r.url()); });
  await cust.page.goto(`${BASE}/inventory-ai`);
  await cust.page.getByTestId('inventory-restricted').waitFor();
  if (calls.length) throw new Error('inventory requests were made by a customer: ' + calls.join(', '));
  const o = await api(cust.page, '/overview');
  if (o.status !== 403) throw new Error(`customer overview -> ${o.status}`);
  for (const p of ['/risk', '/replenishment', '/clearance', '/regions', '/quality', '/events']) {
    const r = await api(cust.page, p);
    if (r.status !== 403) throw new Error(`customer ${p} -> ${r.status}`);
  }
  const s = await api(cust.page, '/scenario', { method: 'POST', body: JSON.stringify({ sport: 'Cricket', discounts: [30] }) });
  const c = await api(cust.page, '/copilot', { method: 'POST', body: JSON.stringify({ message: 'Which products are at risk?' }) });
  if (s.status !== 403 || c.status !== 403) throw new Error(`scenario ${s.status}, copilot ${c.status}`);
});
await cust.ctx.close();

// ============================================================================================ business user
const biz = await newPage();
const page = biz.page;
await login(page, adminEmail);
let overviewApi;
await step(page, '5. Business user opens /inventory-ai: dashboard KPIs match the API', async () => {
  await gotoTab(page, 'overview');
  await page.getByTestId('kpi-total').waitFor();
  overviewApi = (await api(page, '/overview')).body;
  const checks = { 'kpi-total': overviewApi.total, 'kpi-stockout': overviewApi.stockout_high, 'kpi-overstock': overviewApi.overstock, 'kpi-slow': overviewApi.slow_moving, 'kpi-aging': overviewApi.aging_over_90, 'kpi-at-risk': overviewApi.at_risk };
  for (const [id, expected] of Object.entries(checks)) {
    const shown = num((await page.getByTestId(id).locator('h4').innerText()));
    if (shown !== expected) throw new Error(`${id}: UI ${shown} vs API ${expected}`);
  }
  if (!(await page.getByTestId('data-badge').first().innerText()).toLowerCase().includes('synthetic')) throw new Error('synthetic data badge missing');
  await page.screenshot({ path: `${shots}/inv-1-overview.png` });
});
await step(page, '6. Forecast chart loads with a real forecast line and interval band; totals match the API', async () => {
  await page.getByTestId('overview-chart').locator('[data-testid=forecast-line]').waitFor();
  await page.getByTestId('overview-chart').locator('[data-testid=forecast-band]').waitFor();
  const apiFc = (await api(page, '/forecast')).body;
  const shown = num(await page.getByTestId('forecast-total').innerText().then((t) => t.split(' ')[0]));
  if (Math.abs(shown - Math.round(apiFc.totals['30'])) > 1) throw new Error(`30-day total UI ${shown} vs API ${apiFc.totals['30']}`);
  await page.getByTestId('horizon-7').click();
  const seven = num((await page.getByTestId('forecast-total').innerText()).split(' ')[0]);
  if (Math.abs(seven - Math.round(apiFc.totals['7'])) > 1) throw new Error(`7-day total UI ${seven} vs API ${apiFc.totals['7']}`);
  await page.getByTestId('overview-chart').hover({ position: { x: 300, y: 120 } });
  if (!/units|forecast/.test(await page.getByTestId('chart-hover').first().innerText())) throw new Error('chart tooltip empty');
});
await step(page, '7. Stock risk loads; ranking equals the API; a row opens the product reasons', async () => {
  await gotoTab(page, 'risk');
  await page.getByTestId('risk-row').first().waitFor();
  const apiRisks = (await api(page, '/risk?limit=60')).body.risks;
  const first = await page.getByTestId('risk-row').first().innerText();
  if (!first.includes(apiRisks[0].name)) throw new Error(`top risk UI "${first.slice(0, 40)}" vs API ${apiRisks[0].name}`);
  const rows = await page.getByTestId('risk-row').count();
  if (rows !== Math.min(60, apiRisks.length)) throw new Error(`rows ${rows} vs ${apiRisks.length}`);
  await page.getByTestId('risk-row').first().getByText(apiRisks[0].name).click();
  await page.getByTestId('why-this-rating').waitFor({ timeout: 20_000 });
  const why = await page.getByTestId('why-this-rating').innerText();
  if (!why.includes('days of cover')) throw new Error('no cover explanation: ' + why.slice(0, 80));
  await page.getByTestId('product-forecast-chart').waitFor();
  await page.screenshot({ path: `${shots}/inv-2-risk-dialog.png` });
  await page.keyboard.press('Escape');
});
await step(page, '8. Replenishment loads: quantities equal the API, "no purchase order created" is stated', async () => {
  await gotoTab(page, 'replenishment');
  await page.getByTestId('replenishment-row').first().waitFor();
  await page.getByTestId('no-po-notice').waitFor();
  const apiRows = (await api(page, '/replenishment')).body.recommendations;
  const withQty = apiRows.filter((r) => r.reorder_qty);
  const firstOrder = await page.getByTestId('replenishment-row').filter({ hasText: withQty[0].name }).first().innerText();
  if (!firstOrder.includes(String(withQty[0].reorder_qty))) throw new Error(`qty ${withQty[0].reorder_qty} not in "${firstOrder.replace(/\s+/g, ' ').slice(0, 120)}"`);
});
await step(page, '9. Overstock loads: clearance candidates, aging distribution, overstock and slow-moving tables', async () => {
  await gotoTab(page, 'overstock');
  await page.getByTestId('clearance-row').first().waitFor();
  await page.getByTestId('aging-bars').waitFor();
  await page.getByTestId('overstock-row').first().waitFor();
  const aging = (await api(page, '/aging')).body;
  const shown = await page.getByTestId('aging-bars').innerText();
  for (const b of aging.buckets) if (!shown.includes(b.bucket)) throw new Error('bucket missing ' + b.bucket);
  const total = aging.buckets.reduce((s, b) => s + b.skus, 0);
  if (total !== aging.total_skus) throw new Error('aging buckets do not add up');
  await page.screenshot({ path: `${shots}/inv-3-overstock.png` });
});
let scenarioApi;
await step(page, '10. Promotions: the UI scenario equals the API calculation, labelled calculation-only', async () => {
  await gotoTab(page, 'promotions');
  await page.getByTestId('run-scenario').click();
  await page.getByTestId('scenario-view').waitFor({ timeout: 30_000 });
  scenarioApi = (await api(page, '/scenario', { method: 'POST', body: JSON.stringify({ discounts: [30, 50], days: 14, slowMovingOnly: true }) })).body;
  const s30 = scenarioApi.scenarios.find((s) => s.discount_pct === 30);
  const text = (await page.getByTestId('scenario-table').innerText()).replace(/\s+/g, ' ');
  if (!text.includes(Math.round(s30.units_sold).toLocaleString('en-IN'))) throw new Error(`units ${s30.units_sold} not in table`);
  if (!text.includes(`₹${Math.round(s30.revenue).toLocaleString('en-IN')}`)) throw new Error(`revenue ${s30.revenue} not in table`);
  await page.getByTestId('scenario-conclusion').waitFor();
  await page.getByTestId('scenario-assumptions').waitFor();
  if (!(await page.getByTestId('ai-recommendation-badge').first().isVisible())) throw new Error('recommendation badge missing');
  await page.screenshot({ path: `${shots}/inv-4-promotions.png`, fullPage: true });
});
await step(page, '11. Events: upcoming cricket tournament with uplift evidence, readiness and replenishment', async () => {
  await gotoTab(page, 'events');
  await page.getByTestId('event-view').waitFor();
  await page.getByTestId('uplift-bars').waitFor();
  await page.getByTestId('readiness-row').first().waitFor();
  const text = await page.getByTestId('event-view').innerText();
  if (!/Premier Cricket Tournament 2026/.test(text)) throw new Error('cricket tournament not shown first');
  await page.getByTestId('event-replenishment-table').waitFor();
  await page.getByTestId('region-table').first().waitFor();
  await page.screenshot({ path: `${shots}/inv-5-events.png`, fullPage: true });
});

// ============================================================================================ copilot (real Gemini)
const ask = async (q) => {
  const before = await page.getByTestId('copilot-message-assistant').count();
  const waiter = page.waitForResponse((r) => r.url().includes('/inventory/copilot') && r.request().method() === 'POST', { timeout: 120_000 });
  await page.getByTestId('copilot-input').fill(q);
  await page.getByTestId('copilot-send').click();
  const resp = await waiter;
  const body = await resp.json();
  await page.waitForFunction((n) => document.querySelectorAll('[data-testid=copilot-message-assistant]').length > n, before, { timeout: 30_000 });
  return { body, last: page.getByTestId('copilot-message-assistant').last() };
};
await gotoTab(page, 'overview');
await step(page, '12. Copilot: "Which products are likely to stock out next month?" shows the risk table, from tools, numbers match the API', async () => {
  const { body, last } = await ask('Which products are likely to stock out next month?');
  if (body.intent !== 'STOCK_RISK' || body.status !== 'OK') throw new Error(`${body.intent} ${body.status}`);
  if (!body.tools.some((t) => t.tool === 'get_stock_risk')) throw new Error('no get_stock_risk call');
  await last.getByTestId('copilot-block-risk_table').waitFor();
  const apiTop = (await api(page, '/risk?limit=1')).body.risks[0];
  if (!(await last.getByTestId('copilot-block-risk_table').innerText()).includes(apiTop.name)) throw new Error('top risk not in the copilot table');
  if (body.llm.grounding_fallback) log('     note: wording replaced by the verified summary');
  if (!(await last.getByTestId('ai-recommendation-badge').first().isVisible())) throw new Error('no recommendation badge');
  await page.screenshot({ path: `${shots}/inv-6-copilot-risk.png` });
});
await step(page, '13. Copilot: "Why?" explains with the drivers (cover vs lead time) and keeps the context', async () => {
  const { body, last } = await ask('Why?');
  if (body.intent !== 'STOCK_RISK') throw new Error('lost the context: ' + body.intent);
  const text = await last.getByTestId('copilot-text').innerText();
  if (!/cover|lead time|arriv|stock-out|stockout/i.test(text)) throw new Error('no drivers: ' + text.slice(0, 100));
  if (!body.tools.some((t) => t.tool === 'get_sales_history')) throw new Error('drivers were not looked up');
});
await step(page, '14. Copilot: "Which products should we replenish?" quantities equal the API', async () => {
  const { body, last } = await ask('Which products should we replenish?');
  if (body.intent !== 'REPLENISH') throw new Error(body.intent);
  await last.getByTestId('copilot-block-replenishment_table').waitFor();
  const apiRows = (await api(page, '/replenishment?limit=6')).body.recommendations.filter((r) => r.reorder_qty);
  const table = (await last.getByTestId('copilot-block-replenishment_table').innerText()).replace(/\s+/g, ' ');
  if (!table.includes(apiRows[0].name) || !table.includes(String(apiRows[0].reorder_qty))) throw new Error('first recommendation differs from the API');
});
await step(page, '15. Copilot: "Which products should go on clearance?"', async () => {
  const { body, last } = await ask('Which products should go on clearance?');
  if (body.intent !== 'CLEARANCE') throw new Error(body.intent);
  await last.getByTestId('copilot-block-clearance_table').waitFor();
  const api0 = (await api(page, '/clearance?limit=1')).body.candidates[0];
  if (!(await last.getByTestId('copilot-block-clearance_table').innerText()).includes(api0.name)) throw new Error('top candidate differs');
});
let s30, s50;
await step(page, '16. Copilot: 30% and 50% discount scenarios equal the scenario engine', async () => {
  const a = await ask('What happens if we give 30% discount?');
  const b = await ask('What happens if we give 50% discount?');
  for (const r of [a, b]) if (r.body.intent !== 'PROMOTION' || r.body.status !== 'OK') throw new Error(r.body.intent + ' ' + r.body.status);
  s30 = a.body.blocks.find((x) => x.type === 'scenario_comparison').data;
  s50 = b.body.blocks.find((x) => x.type === 'scenario_comparison').data;
  const direct = (await api(page, '/scenario', { method: 'POST', body: JSON.stringify({ discounts: [30], days: 14, slowMovingOnly: true }) })).body;
  const d30 = direct.scenarios.find((s) => s.discount_pct === 30);
  const c30 = s30.scenarios.find((s) => s.discount_pct === 30);
  if (c30.units_sold !== d30.units_sold || c30.revenue !== d30.revenue) throw new Error('copilot 30% differs from the engine');
  if (!s50.scenarios.some((s) => s.discount_pct === 50)) throw new Error('no 50% scenario');
});
await step(page, '17. Copilot: "Which scenario is better?" keeps the scenarios, never contradicts the engine, states the trade-off', async () => {
  const { body, last } = await ask('Which scenario is better?');
  if (body.intent !== 'COMPARE_SCENARIOS') throw new Error(body.intent);
  const sc = body.blocks.find((x) => x.type === 'scenario_comparison').data;
  const text = await last.getByTestId('copilot-text').innerText();
  const rec = sc.recommended_discount_pct;
  if (rec == null && /recommend[^.]*\b(30|50)%/i.test(text) && !/no (single|uniform)|not recommend|margin floor/i.test(text)) throw new Error('text recommends a discount the engine does not: ' + text.slice(0, 160));
  await last.getByTestId('scenario-conclusion').waitFor();
  await page.screenshot({ path: `${shots}/inv-7-copilot-scenarios.png`, fullPage: true });
});
await step(page, '18. Copilot: "How will the upcoming cricket tournament affect inventory?" event impact, readiness, replenishment, regions', async () => {
  const { body, last } = await ask('How will the upcoming cricket tournament affect inventory?');
  if (body.intent !== 'EVENT') throw new Error(body.intent);
  for (const t of ['event_readiness', 'replenishment_table', 'region_table']) if (!body.blocks.some((b) => b.type === t)) throw new Error('missing block ' + t);
  const ev = body.blocks.find((b) => b.type === 'event_readiness').data;
  const direct = (await api(page, `/events/${ev.event.id}`)).body;
  if (ev.impact.length !== direct.impact.length || ev.impact[0].uplift !== direct.impact[0].uplift) throw new Error('event impact differs from the API');
  await last.getByTestId('copilot-block-event_readiness').waitFor();
  await page.screenshot({ path: `${shots}/inv-8-copilot-event.png`, fullPage: true });
});
await step(page, '19. Copilot refuses to execute: "Create a purchase order" and "Change the price" change nothing', async () => {
  for (const q of ['Create a purchase order for 100 cricket bats', 'Change the price of the MRF cricket bat to 5000']) {
    const { body } = await ask(q);
    if (body.status !== 'REFUSED' || body.tools.length) throw new Error(`${q} -> ${body.status}, tools ${body.tools.length}`);
  }
});
await step(page, '20. Copilot policy question is answered from the internal documents with sources', async () => {
  const { body, last } = await ask('What is our clearance policy for inventory older than 90 days?');
  if (body.intent !== 'POLICY') throw new Error(body.intent);
  if (!body.sources.length) throw new Error('no sources');
  await last.getByTestId('copilot-sources').waitFor();
});

// ============================================================================================ failure handling
await step(page, '21. Failure: inventory service unreachable (network fault) -> a clear structured error, storefront unaffected', async () => {
  await page.route('**/ai-api/v1/inventory/overview', (r) => r.abort());
  await gotoTab(page, 'overview');
  const err = page.getByTestId('inventory-error');
  await err.waitFor({ timeout: 30_000 });
  if ((await err.getAttribute('data-code')) !== 'UNREACHABLE') throw new Error('code ' + (await err.getAttribute('data-code')));
  await page.unroute('**/ai-api/v1/inventory/overview');
  await page.goto(BASE);
  await page.getByTestId('sports-categories').waitFor();
});

// ============================================================================================ mobile
const mob = await newPage({ width: 390, height: 844 });
await login(mob.page, adminEmail);
await step(mob.page, '22. Mobile (390px): dashboard readable, no horizontal page scroll, copilot opens from the button', async () => {
  await gotoTab(mob.page, 'overview');
  await mob.page.getByTestId('kpi-total').waitFor();
  const overflow = await mob.page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  if (overflow > 2) throw new Error('horizontal overflow ' + overflow);
  await mob.page.getByTestId('open-copilot').click();
  await mob.page.getByTestId('copilot-input').waitFor();
  await mob.page.screenshot({ path: `${shots}/inv-9-mobile.png` });
});
await mob.ctx.close();

const problems = [...biz.problems];
await step(page, '23. No unexpected HTTP errors or console errors across the business session', async () => {
  const real = problems.filter((p) => !/inventory\/overview/.test(p) && !/ERR_FAILED|net::/.test(p));
  if (real.length) throw new Error(real.slice(0, 4).join(' | '));
});
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
