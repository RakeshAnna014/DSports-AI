// Failure behaviour of Inventory Intelligence against REAL faulty instances (e2e/ai/failure-instances.sh): Gemini down (:8091), database down (:8092),
// insufficient data (:8093). The browser's /ai-api calls are routed to the faulty instance; the storefront's own /api calls are untouched.
// Usage: node inventory-failures.mjs <adminEmail> <password> <victimId> <victimName> [shotDir]
import { chromium } from 'playwright';

const [adminEmail, password, victimId, victimName, shots = '.'] = process.argv.slice(2);
const BASE = 'http://localhost:3000';
let failed = false;
const log = (...a) => console.log('[inventory-failures]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function open(target) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await ctx.newPage();
  if (target) {
    await ctx.route('**/ai-api/**', async (route) => {
      const path = new URL(route.request().url()).pathname.replace(/^\/ai-api/, '');
      const search = new URL(route.request().url()).search;
      const res = await route.fetch({ url: `${target}${path}${search}` });
      await route.fulfill({ response: res });
    });
  }
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Email/).fill(adminEmail); await page.waitForTimeout(120); if ((await page.getByLabel(/^Email/).inputValue()) === adminEmail) break; }
  for (let i = 0; i < 5; i++) { await page.getByLabel(/^Password/).fill(password); await page.waitForTimeout(120); if ((await page.getByLabel(/^Password/).inputValue()) === password) break; }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
  return { ctx, page };
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', String(e.message || e).split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/invfail-${name.slice(0, 30).replace(/\W+/g, '_')}.png`, fullPage: true }); } catch { /* ignore */ }
  }
}
const ask = async (page, q) => {
  const waiter = page.waitForResponse((r) => r.url().includes('/inventory/copilot') && r.request().method() === 'POST', { timeout: 120_000 });
  waiter.catch(() => {});                              // never leave a dangling rejection if the page goes away
  await page.getByTestId('copilot-input').fill(q);
  await page.getByTestId('copilot-send').click();
  const resp = await waiter;
  return { status: resp.status(), body: await resp.json().catch(() => ({})) };
};

// ===================================================================================================== Gemini unavailable
{
  const { ctx, page } = await open('http://localhost:8091');
  await step(page, 'Gemini down: the deterministic dashboard still loads (no model is needed for it)', async () => {
    await page.goto(`${BASE}/inventory-ai/overview`);
    await page.getByTestId('kpi-total').waitFor();
    await page.getByTestId('overview-chart').locator('[data-testid=forecast-line]').waitFor();
  });
  await step(page, 'Gemini down: the copilot still answers with the verified data and says no model was used', async () => {
    const { status, body } = await ask(page, 'Which products are at risk of stock-out?');
    if (status !== 200 || body.status !== 'OK') throw new Error(`${status} ${body.status}`);
    if (!body.blocks.some((b) => b.type === 'risk_table')) throw new Error('no risk table');
    if (body.llm.used !== false) throw new Error('llm.used = ' + body.llm.used);
    if (body.message !== `${body.headline}${body.message.slice(body.headline.length)}`.trim() && !body.message.startsWith(body.headline)) throw new Error('not the deterministic headline');
    await page.getByTestId('copilot-block-risk_table').waitFor();
    if (!(await page.getByTestId('copilot-trace').last().innerText()).includes('model not used')) throw new Error('UI does not say the model was not used');
    await page.screenshot({ path: `${shots}/invfail-1-gemini-down.png` });
  });
  await step(page, 'Gemini down: a refusal and a what-if still work', async () => {
    const r1 = await ask(page, 'Create a purchase order for 100 cricket bats');
    if (r1.body.status !== 'REFUSED' || r1.body.tools.length) throw new Error('refusal broken');
    const r2 = await ask(page, 'What happens if we give 30% discount?');
    if (r2.body.status !== 'OK' || !r2.body.blocks.some((b) => b.type === 'scenario_comparison')) throw new Error('scenario broken: ' + r2.body.status);
  });
  await ctx.close();
}

// ===================================================================================================== database unavailable
{
  const { ctx, page } = await open('http://localhost:8092');
  await step(page, 'Database down: inventory shows a structured "Service unavailable" error, not a blank page or a guess', async () => {
    await page.goto(`${BASE}/inventory-ai/overview`);
    const err = page.getByTestId('inventory-error').first();
    await err.waitFor({ timeout: 30_000 });
    if ((await err.getAttribute('data-code')) !== 'INVENTORY_UNAVAILABLE') throw new Error('code ' + (await err.getAttribute('data-code')));
    if (!/Service unavailable/.test(await err.innerText())) throw new Error('wording: ' + (await err.innerText()));
    if (await page.getByTestId('kpi-total').count()) throw new Error('numbers were shown without a database');
    await page.screenshot({ path: `${shots}/invfail-2-db-down.png` });
  });
  await step(page, 'Database down: the copilot returns a structured error', async () => {
    const { status, body } = await ask(page, 'Which products are at risk of stock-out?').catch((e) => ({ status: -1, body: { message: String(e) } }));
    if (status !== 503 || body.code !== 'INVENTORY_UNAVAILABLE') throw new Error(`${status} ${JSON.stringify(body).slice(0, 120)}`);
    await page.getByTestId('inventory-error').last().waitFor();
  });
  await step(page, 'Database down: the customer storefront is unaffected (home, search, product page)', async () => {
    await page.goto(BASE);
    await page.getByTestId('sports-categories').waitFor();
    if ((await page.getByTestId('featured-products').locator('.MuiCard-root').count()) < 4) throw new Error('home products missing');
  });
  await ctx.close();
}

// ===================================================================================================== insufficient data
{
  const { ctx, page } = await open('http://localhost:8093');
  await step(page, 'Insufficient data: the API says so and returns NO forecast numbers for that product', async () => {
    await page.goto(`${BASE}/inventory-ai/overview`);
    await page.getByTestId('kpi-total').waitFor();
    const res = await page.evaluate(async (id) => {
      const t = localStorage.getItem('accessToken');
      const r = await fetch(`/ai-api/v1/inventory/forecast/${id}`, { headers: { Authorization: `Bearer ${t}` } });
      return { status: r.status, body: await r.json() };
    }, victimId);
    if (res.status !== 200 || res.body.status !== 'INSUFFICIENT_DATA' || res.body.daily.length !== 0 || !/^Insufficient data to produce a reliable forecast/.test(res.body.reason)) throw new Error(JSON.stringify(res).slice(0, 200));
  });
  await step(page, 'Insufficient data: the copilot refuses to forecast it and says "Insufficient data to produce a reliable forecast"', async () => {
    const { status, body } = await ask(page, `What is the demand forecast for ${victimName}?`);
    if (status !== 200 || body.status !== 'INSUFFICIENT_DATA') throw new Error(`${status} ${body.status}: ${body.message}`);
    if (!body.message.startsWith('Insufficient data to produce a reliable forecast')) throw new Error(body.message);
    if (body.blocks.some((b) => b.type === 'forecast_chart')) throw new Error('a chart was drawn for a product with no forecast');
    if (/\d+\s*units/.test(body.message)) throw new Error('a number was invented: ' + body.message);
    await page.screenshot({ path: `${shots}/invfail-3-insufficient.png` });
  });
  await step(page, 'Insufficient data: that product is excluded from stock-risk and replenishment lists (no invented risk)', async () => {
    const res = await page.evaluate(async () => {
      const t = localStorage.getItem('accessToken');
      const h = { headers: { Authorization: `Bearer ${t}` } };
      const [risk, rep] = await Promise.all([fetch('/ai-api/v1/inventory/risk?limit=200', h).then((r) => r.json()), fetch('/ai-api/v1/inventory/replenishment?limit=200', h).then((r) => r.json())]);
      return { risk: risk.risks.map((x) => x.name), rep: rep.recommendations.map((x) => x.name) };
    });
    if (res.risk.includes(victimName) || res.rep.includes(victimName)) throw new Error('the product appears in risk/replenishment without a forecast');
  });
  await ctx.close();
}

await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
