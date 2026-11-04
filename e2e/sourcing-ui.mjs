// Real-browser journey for Product & Supplier Intelligence (Phase 5) on the REAL stack: real Java backend (roles, login), real AI service and database, real Chrome.
// Usage: node sourcing-ui.mjs <world.json> [shotDir]     world.json comes from:  python e2e/ai/sourcing_data.py > world.json
// Needs the AI service started with the Victor Sport research provider (e2e/ai/start-ai.sh does that) and outbound internet for the live-research steps.
// The journey creates its own TEST product and suppliers through the UI (marked as test data, so they never reach live figures), so it can run any number of times.
import { chromium } from 'playwright';
import fs from 'node:fs';

const [worldFile, shots = '.'] = process.argv.slice(2);
const world = JSON.parse(fs.readFileSync(worldFile, 'utf8').replace(/^﻿/, ''));
const BASE = 'http://localhost:3000';
const suffix = Math.random().toString(36).replace(/[^a-z]/g, '').slice(0, 5);
const PRODUCT = `E2E Smash Racket ${suffix}`;
let failed = false;
let testOppId = null;
const log = (...a) => console.log('[sourcing-ui]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function newSession(label) {
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  const page = await context.newPage();
  const problems = [];
  page.__problems = problems;
  const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
  let expected404 = 0;
  page.on('console', (m) => { if (m.type() !== 'error') return; if (/404/.test(m.text()) && expected404-- > 0) return; if (/403/.test(m.text()) && page.__allow403) return; problems.push(`${label} console: ${m.text()}`); });
  page.on('pageerror', (e) => problems.push(`${label} pageerror: ${e.message}`));
  page.on('response', (r) => { if (r.status() === 404 && EXPECTED_404.test(r.url())) expected404++; if (r.status() >= 500) problems.push(`${label} HTTP ${r.status()} ${r.url()}`); });
  return { context, page };
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-sourcing-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
  }
}
async function login(page, email) {
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (const [label, value] of [[/^Email/, email], [/^Password/, world.password]]) {
    const f = page.getByLabel(label);
    for (let i = 0; i < 5; i++) { await f.fill(value); await page.waitForTimeout(120); if ((await f.inputValue()) === value) break; }
  }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
}
const tab = async (page, name) => { await page.getByTestId(`src-tab-${name}`).click(); };
const dtab = async (page, name) => { await page.getByTestId(`dtab-${name}`).click(); };

// ------------------------------------------------------------------------------------------------ the customer and the support executive cannot get in
{
  const { page, context } = await newSession('customer');
  page.__allow403 = true;
  await login(page, world.customer);
  await step(page, 'a customer sees no Product Intelligence link and the page is restricted', async () => {
    await page.goto(BASE);
    await page.waitForTimeout(800);
    if ((await page.getByTestId('nav-product-intelligence').count()) !== 0) throw new Error('nav link shown to a customer');
    await page.goto(`${BASE}/product-intelligence`);
    await page.getByTestId('src-restricted').waitFor();
    if ((await page.getByTestId('src-app').count()) !== 0) throw new Error('app rendered');
  });
  await step(page, 'the service itself refuses a customer even when the page is bypassed', async () => {
    const status = await page.evaluate(async () => { const t = localStorage.getItem('accessToken'); const r = await fetch('/ai-api/v1/sourcing/dashboard', { headers: { Authorization: `Bearer ${t}` } }); return r.status; });
    if (status !== 403) throw new Error('status ' + status);
  });
  if (page.__problems.length) { failed = true; log('PROBLEMS', page.__problems); }
  await context.close();
}
{
  const { page, context } = await newSession('support');
  page.__allow403 = true;
  await login(page, world.support);
  await step(page, 'a support executive is restricted as well', async () => {
    await page.goto(`${BASE}/product-intelligence`);
    await page.getByTestId('src-restricted').waitFor();
  });
  await context.close();
}

// ------------------------------------------------------------------------------------------------ the buyer
const { page: b, context: bctx } = await newSession('buyer');
await login(b, world.buyer);
await step(b, 'the buyer sees the Product Intelligence link and the live dashboard (counts from live data only, the research source named)', async () => {
  await b.goto(BASE);
  await b.getByTestId('nav-product-intelligence').click();
  await b.getByTestId('src-dashboard').waitFor();
  await b.getByTestId('metric-total').waitFor();
  await b.getByTestId('src-sources').getByText(/victor-sport/).waitFor();
  await b.screenshot({ path: `${shots}/sourcing-01-dashboard.png`, fullPage: true });
});
await step(b, 'Home goes back to the shop', async () => {
  await b.getByTestId('src-home').click();
  await b.waitForURL((u) => u.pathname === '/');
  await b.goto(`${BASE}/product-intelligence`);
  await b.getByTestId('src-dashboard').waitFor();
});

// ---- LIVE research from the real manufacturer site, driven through the UI
await step(b, 'LIVE: a research run reads the real Victor Sport site and reports what it found', async () => {
  await tab(b, 'runs');
  await b.getByLabel('Keywords (comma separated)').fill('thruster');
  await b.getByTestId('run-start').click();
  await b.locator('[data-testid="run-result"][data-status]:not([data-status="RUNNING"])').waitFor({ timeout: 240000 });   // the banner follows the stored state of the run just started
  const newest = b.getByTestId('run-row').first();                                // newest first
  await newest.getByTestId('run-results-toggle').waitFor();
  await newest.getByTestId('run-stats').getByText(/products read/).waitFor();
  await newest.getByTestId('run-source').first().getByText(/victor-sport: ok/).waitFor();
  await newest.getByTestId('run-results-toggle').click();
  await newest.getByTestId('run-results').getByText(/victorsport\.com\/product\//).first().waitFor();
  await b.screenshot({ path: `${shots}/sourcing-02-live-run.png`, fullPage: true });
});
await step(b, 'LIVE: the discovery page shows product cards with a real picture, the exact-model label and the live-source badge', async () => {
  await tab(b, 'opportunities');
  await b.getByTestId('opp-q').fill('thruster');
  await b.getByTestId('opp-grid').waitFor();
  const card = b.getByTestId('opp-row').first();
  await card.waitFor();
  await card.getByTestId('provenance-chip').getByText('Live source').waitFor();
  await b.waitForFunction(() => { const i = document.querySelector('[data-testid="product-image"] img'); return !!i && i.complete && i.naturalWidth > 0; }, null, { timeout: 30000 });
  await card.getByTestId('image-match').waitFor();
  await b.getByTestId('opp-scope').getByText(/Live data only/).waitFor();
  await b.screenshot({ path: `${shots}/sourcing-03-cards.png`, fullPage: true });
});
await step(b, 'LIVE: a product opens with its gallery and source link, its specifications, evidence, discovered supplier and an honest readiness list', async () => {
  await b.getByTestId('opp-row').first().click();
  await b.getByTestId('opp-detail').waitFor();
  await b.getByTestId('image-gallery').getByTestId('image-caption').waitFor();
  const href = await b.getByTestId('opp-product-url').getAttribute('href');
  if (!/^https:\/\/www\.victorsport\.com\/product\//.test(href ?? '')) throw new Error('source link ' + href);
  await dtab(b, 'specs');
  await b.getByTestId('spec-row').first().waitFor();
  await dtab(b, 'evidence');
  await b.getByTestId('evidence-row').first().waitFor();
  await dtab(b, 'suppliers');
  await b.getByTestId('possible-supplier').getByText(/VICTOR India/).waitFor();
  await b.getByTestId('possible-supplier').getByText(/Discovered \(not verified\)/).waitFor();
  await dtab(b, 'cost');
  await b.getByTestId('opp-calcs').waitFor();                               // the page carries no price, so a margin exists only if a buyer saved one against a quotation (shown with its basis)
  if ((await b.getByTestId('calc-row').count()) === 0) await b.getByTestId('no-calcs').waitFor();
  await dtab(b, 'decisions');
  await b.getByTestId('readiness-summary').waitFor();
  await b.screenshot({ path: `${shots}/sourcing-04-live-detail.png`, fullPage: true });
});
await step(b, 'the table view and the demo/test toggle work; test data stays out until asked for', async () => {
  await b.getByTestId('detail-back').click();
  await b.getByTestId('view-table').click();
  await b.getByRole('columnheader', { name: 'Provenance' }).waitFor();
  await b.getByTestId('view-grid').click();
});

// ---- a TEST product, entered by hand and marked as test data, through the whole buyer workflow
await step(b, 'a buyer adds a test product through the form and it starts unscored', async () => {
  await b.getByTestId('opp-q').fill('');
  await b.getByTestId('opp-create').click();
  const dlg = b.getByTestId('add-product-dialog');
  await dlg.getByTestId('p-name').fill(PRODUCT);
  await dlg.getByLabel('Brand').fill('E2EBrand');
  await dlg.getByLabel('Model number').fill(`E2E-${suffix.toUpperCase()}`);
  await dlg.getByLabel('Sport', { exact: true }).fill('Badminton');
  await dlg.getByLabel('Category', { exact: true }).fill('Rackets');
  await dlg.getByTestId('p-test').check();
  await dlg.getByTestId('p-save').click();
  await b.getByTestId('opp-detail').waitFor();
  await b.getByTestId('opp-name').getByText(PRODUCT).waitFor();
  await b.getByTestId('demo-banner').getByText(/TEST DATA/).waitFor();
  testOppId = new URL(b.url()).searchParams.get('opp');
  await dtab(b, 'score');
  if (!/Not scored yet/.test(await b.getByTestId('opp-score').innerText())) throw new Error('should start unscored');
});
await step(b, 'the test product is hidden from the live list and appears when demo/test data is switched on', async () => {
  await b.getByTestId('detail-back').click();
  await b.getByTestId('opp-q').fill(suffix.toUpperCase());
  await b.getByTestId('opp-empty').waitFor();
  await b.getByTestId('opp-fixtures').check();
  await b.getByTestId('opp-row').first().waitFor();
  if ((await b.getByTestId('opp-row').count()) !== 1) throw new Error('expected one row');
  await b.getByTestId('opp-row').first().getByText('TEST DATA').waitFor();
  await b.getByTestId('opp-q').fill('zzzz-no-such-product');
  await b.getByTestId('opp-empty').waitFor();
});
await step(b, 'test suppliers are added through the form', async () => {
  await tab(b, 'suppliers');
  for (const name of [`E2E Supplier P ${suffix}`, `E2E Supplier Q ${suffix}`]) {
    await b.getByTestId('sup-add').click();
    await b.getByTestId('s-name').fill(name);
    await b.getByTestId('s-test').check();
    await b.getByTestId('s-save').click();
    await b.getByTestId('supplier-dialog').waitFor({ state: 'detached' });
  }
  await b.getByLabel('Show demo and test suppliers').check();
  await b.getByTestId('sup-q').fill(suffix);
  await b.getByTestId('sup-row').nth(1).waitFor();
});
await step(b, 'two offers are recorded through the form and show their source label', async () => {
  await b.goto(`${BASE}/product-intelligence?tab=opportunities&opp=${testOppId}`);
  await b.getByTestId('opp-detail').waitFor();
  await dtab(b, 'suppliers');
  for (const [sup, price, moq, ship] of [[`E2E Supplier P ${suffix}`, '1000', '100', '5000'], [`E2E Supplier Q ${suffix}`, '950', '200', '40000']]) {
    await b.getByTestId('quote-add').click();
    await b.getByTestId('quote-dialog').waitFor();
    await b.getByTestId('q-supplier').click();
    await b.getByRole('option', { name: new RegExp(sup) }).click();
    await b.getByTestId('q-product').fill(PRODUCT);
    await b.getByTestId('q-price').fill(price);
    await b.getByLabel('MOQ').fill(moq);
    await b.getByLabel('Shipping (quote currency)').fill(ship);
    await b.getByLabel('Lead time (days)').fill('21');
    await b.getByTestId('q-save').click();
    await b.getByTestId('quote-dialog').waitFor({ state: 'detached' });
  }
  await b.getByTestId('quote-row').nth(1).waitFor();
  const labels = await b.getByTestId('quote-kind').allInnerTexts();
  if (!labels.every((l) => /Entered from a supplier quotation/.test(l))) throw new Error('labels ' + labels);
});
await step(b, 'compare at 500 units: landed cost per unit is 1010.00 vs 1030.00 and the page explains why the cheaper unit price loses', async () => {
  await dtab(b, 'cost');
  await b.getByTestId('cmp-price').fill('1800');
  await b.getByTestId('cmp-run').click();
  await b.getByTestId('cmp-explanation').waitFor();
  const landed = (await b.getByTestId('cmp-landed').allInnerTexts()).map((t) => t.replace(/[^0-9.]/g, ''));
  if (!(landed.includes('1010.00') && landed.includes('1030.00'))) throw new Error('landed ' + landed);
  if (!/lower landed cost/.test(await b.getByTestId('cmp-explanation').innerText())) throw new Error('no explanation');
  await b.getByTestId('cmp-row').first().getByTestId('cmp-detail').click();
  await b.getByTestId('cmp-breakdown').waitFor();
  await b.screenshot({ path: `${shots}/sourcing-05-compare.png`, fullPage: true });
});
await step(b, 'saving a calculation keeps a snapshot tied to this product and offer; below the MOQ the offers are excluded with the reason', async () => {
  await b.getByTestId('calc-save').click();
  await b.getByTestId('calc-saved').waitFor();
  await b.getByTestId('cmp-qty').fill('50');
  await b.getByTestId('cmp-run').click();
  await b.getByTestId('cmp-result').getByText(/minimum order quantity/).first().waitFor();
  await b.getByTestId('cmp-qty').fill('500');
  await b.getByTestId('cmp-run').click();
  await b.getByTestId('cmp-explanation').waitFor();
});
await step(b, 'the score is calculated, explainable, and separates opportunity, confidence, completeness, supplier risk and the financial basis', async () => {
  await dtab(b, 'score');
  await b.getByTestId('rescore').click();
  await b.getByTestId('score-confidence').waitFor();
  await b.getByTestId('score-financial').waitFor();
  await b.getByTestId('risk-chip').first().waitFor();
  const txt = await b.getByTestId('opp-score').innerText();
  if (!/Supplier risk/.test(txt) || !/Evidence confidence/.test(txt) || !/Financial basis/.test(txt)) throw new Error(txt.slice(0, 200));
});
await step(b, 'the buyer shortlists it; business approval is not offered to a plain buyer, and rejection needs a reason', async () => {
  for (const to of ['UNDER_REVIEW', 'SHORTLISTED']) {
    await b.getByTestId(`to-${to}`).click();
    await b.getByTestId('status-confirm').click();
    await b.getByTestId('status-dialog').waitFor({ state: 'detached' });
    await b.getByTestId('opp-status').getByText(to.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())).waitFor();
  }
  if ((await b.getByTestId('to-APPROVED_OPPORTUNITY').count()) !== 0) throw new Error('approval offered to a plain buyer');
  await b.getByTestId('to-REJECTED').click();
  if (!(await b.getByTestId('status-confirm').isDisabled())) throw new Error('rejection allowed without a reason');
  await b.keyboard.press('Escape');
  await b.getByTestId('status-dialog').waitFor({ state: 'detached' });
});
await step(b, 'the question box answers from the data and says when it cannot', async () => {
  await tab(b, 'ask');
  await b.getByTestId('ask-input').fill('How many products are there? Give me an overview');
  await b.getByTestId('ask-send').click();
  await b.getByTestId('ask-answer').getByText(/live product candidates \(demonstration and test records excluded\)/).first().waitFor({ timeout: 30000 });
  await b.getByTestId('ask-input').fill(`Compare the offers for ${PRODUCT} at 500 units`);   // a TEST product is outside the live scope the assistant answers from
  await b.getByTestId('ask-send').click();
  await b.getByTestId('ask-answer').getByText(/Which product do you mean|could not|No /i).first().waitFor({ timeout: 30000 });
  if (/Zorball/.test(await b.getByTestId('ask-answer').innerText())) throw new Error('a demand gap was offered as a product');
  await b.getByTestId('ask-input').fill('Which products suit the upcoming sporting season?');
  await b.getByTestId('ask-send').click();
  await b.getByTestId('ask-answer').getByText(/no sporting-event or season calendar/i).waitFor({ timeout: 30000 });
});
await step(b, 'the standalone calculator reproduces the documented example (landed 967.00) and shows INCOMPLETE without an exchange rate', async () => {
  await tab(b, 'calculator');
  const set = async (k, v) => { await b.getByTestId(`calc-${k}`).fill(v); };
  await set('quantity', '500'); await set('unit_price', '10'); await set('currency', 'USD');
  await b.getByTestId('calc-run').click();
  await b.getByTestId('calc-status').getByText(/Incomplete/).waitFor();
  await set('fx_rate', '83'); await set('freight_total', '20000'); await set('duty_pct', '10'); await set('handling_per_unit', '5'); await set('packaging_per_unit', '2'); await set('other_fixed_costs', '1500');
  await b.getByTestId('calc-run').click();
  await b.getByTestId('calc-landed').getByText(/967\.00/).waitFor();
});
await step(b, 'the customer-demand tab lists demand as signals, not products', async () => {
  await tab(b, 'gaps');
  await b.getByTestId('src-gaps').getByText(/demand signals, not products/).waitFor();
});
if (b.__problems.length) { failed = true; log('PROBLEMS', b.__problems); }
await bctx.close();

// ------------------------------------------------------------------------------------------------ the category manager gives the two SEPARATE approvals and prepares the proposal
const { page: m, context: mctx } = await newSession('manager');
await login(m, world.manager);
const go = async (to, reason) => {
  await m.getByTestId(`to-${to}`).click();
  if (reason) await m.getByTestId('status-reason').fill(reason);
  await m.getByTestId('status-confirm').click();
  await m.getByTestId('status-dialog').waitFor({ state: 'detached' });
};
await step(m, 'the category manager approves it as a business opportunity, and the dialog says that is not onboarding approval', async () => {
  await m.goto(`${BASE}/product-intelligence?tab=opportunities&opp=${testOppId}`);
  await m.getByTestId('opp-detail').waitFor();
  await m.getByTestId('to-APPROVED_OPPORTUNITY').click();
  await m.getByTestId('status-dialog').getByText(/not approval to onboard/).waitFor();
  await m.getByTestId('status-reason').fill('E2E: worth pursuing');
  await m.getByTestId('status-confirm').click();
  await m.getByTestId('status-dialog').waitFor({ state: 'detached' });
  await m.getByTestId('opp-status').getByText('Approved as a business opportunity').waitFor();
  if ((await m.getByTestId('to-APPROVED_FOR_ONBOARDING').count()) !== 0) throw new Error('onboarding approval offered straight after business approval');
});
await step(m, 'it moves through supplier research and quote comparison, then gets the separate sourcing-preparation approval', async () => {
  await go('SUPPLIER_RESEARCH');
  await m.getByTestId('opp-status').getByText('Supplier research').waitFor();
  await go('QUOTE_COMPARISON');
  await m.getByTestId('opp-status').getByText('Quote comparison').waitFor();
  await m.getByTestId('to-APPROVED_FOR_ONBOARDING').click();
  await m.getByTestId('status-dialog').getByText(/does not authorise a purchase/).waitFor();
  await m.getByTestId('status-reason').fill('E2E: margin and supplier fit the plan');
  await m.getByTestId('status-confirm').click();
  await m.getByTestId('status-dialog').waitFor({ state: 'detached' });
  await m.getByTestId('opp-status').getByText('Approved for sourcing preparation').waitFor();
});
await step(m, 'approval does not make it catalogue-ready: the independent readiness list names what is missing, and the proposal stays a draft that publishes nothing', async () => {
  await dtab(m, 'decisions');
  await m.getByTestId('readiness-summary').getByText(/Approval and readiness are separate/).waitFor();
  await m.getByTestId('readiness-summary').getByText(/Not ready/).waitFor();
  await m.getByTestId('proposal-quote').selectOption({ index: 1 });
  await m.getByTestId('proposal-create').click();
  await m.getByTestId('proposal-row').waitFor();
  await m.getByTestId('proposal-missing').getByText(/description/i).waitFor();
  await m.getByTestId('proposal-row').getByText(/does not create, publish or price a product/).waitFor();
  await m.screenshot({ path: `${shots}/sourcing-06-proposal.png`, fullPage: true });
});
await step(m, 'the decision history shows who did what and why', async () => {
  const rows = await m.getByTestId('history-row').allInnerTexts();
  if (!rows.some((r) => /Status change/.test(r) && /Approved for onboarding/.test(r) && /margin and supplier fit/.test(r))) throw new Error(rows.join('\n').slice(0, 400));
});
await step(m, 'the proposals tab lists it and the page survives a reload', async () => {
  await tab(m, 'proposals');
  await m.getByTestId('proposal-card').filter({ hasText: PRODUCT }).waitFor();
  await m.reload();
  await m.getByTestId('proposal-card').filter({ hasText: PRODUCT }).waitFor();
});
if (m.__problems.length) { failed = true; log('PROBLEMS', m.__problems); }
await mctx.close();
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
