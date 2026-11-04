// Real-browser journey for Sales & Inventory Planning (Phase 6) on the REAL stack: real Java backend (roles, login, the sales feed already ingested), real AI service and database, real Chrome.
// Usage: node planning-ui.mjs <world.json> [shotDir]     world.json comes from:  python e2e/ai/planning_data.py > world.json
// The store network, its history and its stock are the labelled DEMONSTRATION data: this journey shows the workflow, not validated predictions. The "store actual" it imports is a test fixture.
import { chromium } from 'playwright';
import fs from 'node:fs';

const [worldFile, shots = '.'] = process.argv.slice(2);
const world = JSON.parse(fs.readFileSync(worldFile, 'utf8').replace(/^﻿/, ''));
const BASE = 'http://localhost:3000';
const suffix = Math.random().toString(36).replace(/[^a-z]/g, '').slice(0, 5);
const PLAN = `E2E UI plan ${suffix}`;
let failed = false;
let planId = null;
let reviewPlan = null;
const log = (...a) => console.log('[planning-ui]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function newSession(label) {
  const context = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
  const page = await context.newPage();
  const problems = [];
  page.__problems = problems;
  const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
  let expected404 = 0;
  page.on('console', (m) => { if (m.type() !== 'error') return; if (/40[349]|409|422/.test(m.text()) && (page.__allow4xx || expected404-- > 0)) return; problems.push(`${label} console: ${m.text()}`); });
  page.on('pageerror', (e) => problems.push(`${label} pageerror: ${e.message}`));
  page.on('response', (r) => { if (r.status() === 404 && EXPECTED_404.test(r.url())) expected404++; if (r.status() >= 500) problems.push(`${label} HTTP ${r.status()} ${r.url()}`); });
  return { context, page };
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-planning-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
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
const token = async (email) => (await (await fetch(`${BASE}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: world.password }) })).json()).accessToken;
const api = async (tok, method, path, body) => { const r = await fetch(`${BASE}/ai-api/v1/planning${path}`, { method, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tok}` }, body: body ? JSON.stringify(body) : undefined }); return { status: r.status, body: await r.json() }; };
const tab = async (page, name) => { await page.getByTestId(`pln-tab-${name}`).click(); };
const pick = async (page, testId, option) => { await page.getByTestId(testId).click(); await page.getByRole('option', { name: option, exact: true }).click(); };
let storeName = null;
const useRow = async (page) => {
  if (!storeName) { const l = await api(await token(world.planner), 'GET', '/locations'); storeName = l.body.items.find((x) => x.id === 'N-Z1-S1').name; }
  await pick(page, 'f-store', storeName);
  await page.getByTestId('f-q').fill('MRF Cricket Bat');
  await page.getByTestId('ws-row').first().waitFor();
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="ws-row"]').length >= 1);
};

// ------------------------------------------------------------------------------------------------ a customer and a store manager without a plan
{
  const { page, context } = await newSession('customer');
  page.__allow4xx = true;
  await login(page, world.customer);
  await step(page, 'a customer sees no Sales Planning link, the page is restricted, and the service refuses a bypass', async () => {
    await page.goto(BASE);
    await page.waitForTimeout(800);
    if ((await page.getByTestId('nav-sales-planning').count()) !== 0) throw new Error('nav link shown to a customer');
    await page.goto(`${BASE}/sales-planning`);
    await page.getByTestId('pln-restricted').waitFor();
    if ((await page.getByTestId('pln-app').count()) !== 0) throw new Error('app rendered');
    const status = await page.evaluate(async () => { const t = localStorage.getItem('accessToken'); const r = await fetch('/ai-api/v1/planning/plans', { headers: { Authorization: `Bearer ${t}` } }); return r.status; });
    if (status !== 403) throw new Error('status ' + status);
  });
  await context.close();
}

// ------------------------------------------------------------------------------------------------ the planner builds a plan
const { page: p, context: pctx } = await newSession('planner');
// the journey provokes one 409 (stale save) and one 422 (invalid import) on purpose; 5xx and page errors still fail it
p.__allow4xx = true;
await login(p, world.planner);
await step(p, 'the planner sees the link and the dashboard, with the demonstration banner and no invented numbers', async () => {
  await p.goto(BASE);
  await p.getByTestId('nav-sales-planning').click();
  await p.getByTestId('dashboard').waitFor();
  await p.getByTestId('scope-banner-demo').first().waitFor();
  const txt = await p.getByTestId('dashboard').innerText();
  if (!/DEMONSTRATION DATA/.test(txt)) throw new Error('no demonstration label');
  await p.screenshot({ path: `${shots}/planning-01-dashboard.png`, fullPage: true });
});
await step(p, 'the data page lists which inputs are demonstration, limited or unavailable, and what the live feed holds', async () => {
  await tab(p, 'readiness');
  await p.getByTestId('cap-row').first().waitFor();
  const status = async (k) => (await p.locator(`[data-testid="cap-row"][data-key="${k}"]`).getByTestId('cap-status').innerText());
  if (!/Insufficient|Limited|Reliable/.test(await status('online_sales_actuals'))) throw new Error('online status ' + await status('online_sales_actuals'));
  if (!/Demonstration/.test(await status('store_level_history'))) throw new Error('store history status');
  await p.getByTestId('reconcile').getByText(/exact/).waitFor();
  if ((await p.getByTestId('ingest-incremental').count()) !== 0) throw new Error('a planner was offered the admin ingest');
  await p.screenshot({ path: `${shots}/planning-02-readiness.png`, fullPage: true });
});
await step(p, 'the planner creates a plan for Cricket bats from the current forecast', async () => {
  await tab(p, 'dashboard');
  await pick(p, 'f-sport', 'Cricket');
  await pick(p, 'f-category', 'Bat');
  await p.getByTestId('plan-new').click();
  await p.getByTestId('plan-name').fill(PLAN);
  await p.getByTestId('plan-create').click();
  await p.getByTestId('new-plan-dialog').waitFor({ state: 'detached' });
  await p.getByTestId('plan-status').getByText('Draft').waitFor();
  planId = new URL(p.url()).searchParams.get('plan');
  if (!planId) throw new Error('plan id not in the URL');
});
await step(p, 'the weekly grid shows model forecast, manager forecast, target and actual as separate columns and is paginated by the server', async () => {
  await tab(p, 'workspace');
  await useRow(p);
  const head = await p.locator('thead').innerText();
  for (const h of ['Model forecast', 'Latest model', 'Manager forecast', 'Approved forecast', 'Target', 'Actual']) if (!head.includes(h)) throw new Error('missing column ' + h);
  await p.getByTestId('ws-pagination').waitFor();
  await p.screenshot({ path: `${shots}/planning-03-workspace.png`, fullPage: true });
});
await step(p, 'a target and a manager forecast are edited; the forecast adjustment asks for a reason; both are saved', async () => {
  const row = p.getByTestId('ws-row').first();
  const model = Number((await row.getByTestId('ws-model').innerText()).split('\n')[0].replace(/,/g, ''));
  await row.getByTestId('ws-target').fill(String(Math.round(model * 1.2)));
  await row.getByTestId('ws-manager').fill(String(Math.round(model * 1.1)));
  await p.getByTestId('ws-save').click();
  const dlg = p.getByTestId('reason-dialog');
  await dlg.waitFor();
  await dlg.getByTestId('reason-note').fill('E2E regional tournament');
  await dlg.getByTestId('reason-save').click();
  await p.getByTestId('ws-saved').waitFor();
  if (!(await p.getByTestId('ws-row').first().getByTestId('ws-manager').inputValue())) throw new Error('manager forecast not shown after save');
});
await step(p, 'the stock effect of the adjustment is explained from the Inventory AI rules', async () => {
  await p.getByTestId('ws-row').first().getByTestId('ws-impact').click();
  const dlg = p.getByTestId('impact-dialog');
  await dlg.getByTestId('impact-risk_level').waitFor();
  await dlg.getByText(/Inventory AI/).waitFor();
  await p.keyboard.press('Escape');
});
await step(p, 'a second manager changes the same line meanwhile: the planner\'s stale save is refused as a conflict and nothing is saved', async () => {
  const mgrTok = await token(world.manager);
  const planTok = await token(world.planner);
  const cur = await api(planTok, 'GET', `/plans/${planId}/lines?product_id=${world.bat}&store=N-Z1-S1&size=1`);
  const l = cur.body.items[0];
  const r = await api(mgrTok, 'PATCH', `/plans/${planId}/lines`, { edits: [{ product_id: l.product_id, location_id: l.location_id, week_start: l.week_start, expected_line_version: l.line_version, target_units: 777 }] });
  if (r.status !== 200) throw new Error('manager edit ' + r.status);
  await p.getByTestId('ws-row').first().getByTestId('ws-target').fill('55');
  await p.getByTestId('ws-save').click();
  const alert = p.getByTestId('ws-conflict');
  await alert.waitFor();
  if (!/Nothing was saved/.test(await alert.innerText())) throw new Error('no conflict message');
  await alert.getByRole('button', { name: 'Reload' }).click();
  await p.getByTestId('ws-row').first().getByTestId('ws-target').waitFor();
  await p.waitForFunction(() => document.querySelector('[data-testid="ws-row"] [data-testid="ws-target"] input, [data-testid="ws-row"] input[data-testid="ws-target"]')?.value === '777', null, { timeout: 15000 }).catch(async () => { throw new Error('the other manager\'s target should now show, got ' + await p.getByTestId('ws-row').first().getByTestId('ws-target').inputValue()); });
});
await step(p, 'the planner edits again on fresh data, submits the plan, and it becomes read-only', async () => {
  await p.getByTestId('ws-row').first().getByTestId('ws-target').fill('60');
  await p.getByTestId('ws-save').click();
  await p.getByTestId('ws-saved').waitFor();
  await p.getByTestId('plan-to-SUBMITTED').click();
  await p.getByTestId('transition-confirm').click();
  await p.getByTestId('transition-dialog').waitFor({ state: 'detached' });
  await p.getByTestId('plan-status').getByText('Submitted').waitFor();
  await p.getByTestId('ws-frozen').waitFor();
  if ((await p.getByTestId('plan-to-APPROVED').count()) !== 0) throw new Error('approval offered to a planner');
});

// ------------------------------------------------------------------------------------------------ the manager approves; the planner cannot
const { page: m, context: mctx } = await newSession('manager');
await login(m, world.manager);
await step(m, 'the category manager reviews and approves, with the warning that approval buys and publishes nothing', async () => {
  await m.goto(`${BASE}/sales-planning?tab=workspace&plan=${planId}`);
  await m.getByTestId('plan-to-UNDER_REVIEW').click();
  await m.getByTestId('transition-confirm').click();
  await m.getByTestId('transition-dialog').waitFor({ state: 'detached' });
  await m.getByTestId('plan-status').getByText('Under review').waitFor();
  await m.getByTestId('plan-to-APPROVED').click();
  await m.getByTestId('transition-dialog').getByText(/orders, publishes and prices nothing/).waitFor();
  await m.getByTestId('transition-confirm').click();
  await m.getByTestId('transition-dialog').waitFor({ state: 'detached' });
  await m.getByTestId('plan-status').getByText('Approved').waitFor();
});
await step(m, 'the approved plan shows the approved forecast next to the unchanged model forecast and the target', async () => {
  await useRow(m);
  const row = m.getByTestId('ws-row').first();
  const model = await row.getByTestId('ws-model').innerText();
  const approved = await row.getByTestId('ws-approved').innerText();
  if (approved.trim() === '—' || model.split('\n')[0].trim() === approved.trim()) throw new Error(`approved ${approved} model ${model}`);
  if ((await m.getByTestId('ws-target').count()) !== 0) throw new Error('inputs shown on a frozen plan');
  await m.screenshot({ path: `${shots}/planning-04-approved.png`, fullPage: true });
});
await step(m, 'the plan history lists every step with who and when', async () => {
  await m.getByTestId('plan-audit').click();
  await m.getByTestId('audit-row').first().waitFor();
  const t = await m.getByTestId('audit-dialog').innerText();
  for (const w of ['Created', 'Edited', 'Status submitted', 'Status under review', 'Status approved']) if (!t.includes(w)) throw new Error('history misses ' + w);
  await m.keyboard.press('Escape');
});

// ------------------------------------------------------------------------------------------------ actuals and variance (planner)
await step(p, 'before any week closes the plan shows no actual and no variance (never zero)', async () => {
  await p.goto(`${BASE}/sales-planning?tab=performance&plan=${planId}`);
  await p.getByTestId('perf-notice').waitFor();
  const txt = await p.getByTestId('perf-total').innerText();
  if (!/no closed week/.test(txt)) throw new Error(txt);
});
await step(p, 'the planner imports closed-week store actuals through the controlled import (a labelled fixture)', async () => {
  const tok = await token(world.planner);
  const week = (await api(tok, 'GET', `/plans/${planId}/lines?product_id=${world.bat}&store=N-Z1-S1&size=1`)).body.items[0].week_start;
  await p.goto(`${BASE}/sales-planning?tab=actuals&plan=${planId}`);
  await p.getByTestId('act-import').click();
  const dlg = p.getByTestId('import-dialog');
  await dlg.getByTestId('import-batch').fill(`ui-e2e-${suffix}`);
  await dlg.getByTestId('import-text').fill(`sku,store,week_start,gross_units,cancelled_units,returned_units\nCR-BAT-MRF-001,N-Z1-S1,${week},12,1,0`);
  await dlg.getByTestId('import-run').click();
  await dlg.getByTestId('import-result').getByText(/1 row\(s\) imported/).waitFor();
  await dlg.getByTestId('import-run').click();
  await dlg.getByText(/already imported/).waitFor();
  await dlg.getByRole('button', { name: 'Close' }).click();
});
await step(p, 'an invalid import is rejected with the reason and nothing is imported', async () => {
  await p.getByTestId('act-import').click();
  const dlg = p.getByTestId('import-dialog');
  await dlg.getByTestId('import-batch').fill(`ui-e2e-bad-${suffix}`);
  await dlg.getByTestId('import-text').fill('sku,store,week_start,gross_units,cancelled_units,returned_units\nCR-BAT-MRF-001,ONL-S,2026-10-05,3,0,0');
  await dlg.getByTestId('import-run').click();
  await dlg.getByTestId('import-error').getByText(/STORE|nothing was imported/).waitFor();
  await dlg.getByRole('button', { name: 'Close' }).click();
});
await step(p, 'the grid now shows the actual (net 11 = 12 - 1) with its source and the variances against forecast and target', async () => {
  await p.goto(`${BASE}/sales-planning?tab=workspace&plan=${planId}`);
  await useRow(p);
  const row = p.getByTestId('ws-row').first();
  await row.getByTestId('ws-actual').getByText(/^11/).waitFor();
  if (!/Imported/.test(await row.getByTestId('ws-actual').innerText())) throw new Error('source not shown');
  const fv = await row.getByTestId('ws-fvar').innerText();
  if (fv.trim() === '—') throw new Error('no forecast variance');
  await p.screenshot({ path: `${shots}/planning-05-actual.png`, fullPage: true });
});
await step(p, 'the investigation separates recorded facts from labelled hypotheses', async () => {
  await p.getByTestId('ws-row').first().getByTestId('ws-why').click();
  const dlg = p.getByTestId('investigate-dialog');
  await dlg.getByTestId('fact').first().waitFor();
  if (!/hypotheses, not findings/.test(await dlg.innerText())) throw new Error('hypotheses not labelled');
  await p.keyboard.press('Escape');
});
await step(p, 'plan versus actual follows the formulas and says which basis it used', async () => {
  await tab(p, 'performance');
  await p.getByTestId('perf-row').first().waitFor();
  await pick(p, 'perf-grain', 'Store');
  await p.getByTestId('perf-row').first().waitFor();
  const t = await p.getByTestId('perf-notice').innerText();
  if (!/Forecast variance = actual − forecast/.test(t) || !/APPROVED forecast/.test(t)) throw new Error(t);
  await p.getByTestId('perf-basis-model').click();
  await p.getByTestId('perf-notice').getByText(/MODEL forecast/).waitFor();
  await p.screenshot({ path: `${shots}/planning-06-performance.png`, fullPage: true });
});

// ------------------------------------------------------------------------------------------------ inventory planning
await step(p, 'inventory recommendations are generated as drafts; a planner cannot approve them', async () => {
  await tab(p, 'inventory');
  await p.getByTestId('inv-generate').click();
  await p.getByTestId('inv-generated').waitFor({ timeout: 120000 });
  await p.getByTestId('inv-row').first().waitFor();
  if ((await p.getByTestId('inv-approve').count()) !== 0) throw new Error('approve offered to a planner');
  await p.screenshot({ path: `${shots}/planning-07-inventory.png`, fullPage: true });
});
await step(m, 'the manager approves a recommendation and is told that nothing was ordered or moved', async () => {
  await m.goto(`${BASE}/sales-planning?tab=inventory&plan=${planId}`);
  await m.getByTestId('inv-approve').first().click();
  await m.getByTestId('inv-decided').getByText(/No purchase order/).waitFor();
});

// ------------------------------------------------------------------------------------------------ accuracy, regions, scenarios, assistant
await step(p, 'forecast accuracy is shown against the baseline with the synthetic label, and thin slices are withheld', async () => {
  await tab(p, 'evaluation');
  await p.getByTestId('eval-row').first().waitFor();
  if (/Withheld/.test(await p.getByTestId('metric-ev-wape').innerText())) throw new Error('overall accuracy withheld');
  if (!/SYNTHETIC/.test(await p.getByTestId('eval-notice').innerText())) throw new Error('no synthetic label');
  await pick(p, 'eval-scope', 'Store');
  await p.waitForFunction(() => [...document.querySelectorAll('[data-testid="eval-wape"]')].some((e) => /withheld/.test(e.textContent ?? '')) || document.querySelectorAll('[data-testid="eval-wape"]').length > 3);
  await p.screenshot({ path: `${shots}/planning-08-evaluation.png`, fullPage: true });
});
await step(p, 'regions and categories show shares with their sample sizes', async () => {
  await tab(p, 'analysis');
  await p.getByTestId('an-region').first().waitFor();
  await p.getByTestId('an-notice').getByText(/SYNTHETIC/).waitFor();
  await p.screenshot({ path: `${shots}/planning-09-analysis.png`, fullPage: true });
});
await step(p, 'a scenario is a saved simulation that leaves the approved plan alone', async () => {
  await tab(p, 'scenarios');
  await pick(p, 'f-sport', 'Cricket');
  await pick(p, 'f-category', 'Bat');
  await p.getByTestId('scn-run').click();
  await p.getByTestId('scn-result').getByText(/A simulation/).waitFor({ timeout: 120000 });
  await p.getByTestId('scn-row').first().waitFor();
  await p.screenshot({ path: `${shots}/planning-10-scenario.png`, fullPage: true });
});
await step(p, 'the assistant answers from the stored evaluation, with a link to its evidence', async () => {
  await tab(p, 'ask');
  await p.getByTestId('ask-input').fill('Show the forecast accuracy');
  await p.getByTestId('ask-send').click();
  await p.getByTestId('ask-answer').getByText(/WAPE/).waitFor({ timeout: 60000 });
  await p.getByTestId('ask-evidence').first().waitFor();
});
await step(p, 'after a reload the plan, its status and the saved numbers are still there', async () => {
  await p.goto(`${BASE}/sales-planning?tab=workspace&plan=${planId}`);
  await p.getByTestId('plan-status').getByText('Approved').waitFor();
  await useRow(p);
  if (!/^11/.test(await p.getByTestId('ws-row').first().getByTestId('ws-actual').innerText())) throw new Error('actual lost');
});

// ------------------------------------------------------------------------------------------------ AI analysis and the review queue (real LLM, real database)
await step(p, 'the dashboard shows its charts, the heatmap and honest "unavailable" states for what the data cannot support', async () => {
  await p.goto(`${BASE}/sales-planning?tab=dashboard&plan=${planId}`);
  await p.getByTestId('dash-chart').waitFor();
  await p.getByTestId('dash-heatmap').waitFor();
  await p.locator('[data-testid="dash-heatmap"] [data-testid="heat-cell"]').first().waitFor();
  await p.getByTestId('dash-insights').waitFor();
  await p.getByTestId('workflow').waitFor();
  await p.screenshot({ path: `${shots}/planning-11-dashboard-redesign.png`, fullPage: true });
});
await step(p, 'the AI analysis runs on stored records: facts and hypotheses are separated, provenance and limits are shown, and nothing is approved', async () => {
  await tab(p, 'insights');
  await p.getByTestId('ins-run').click();
  await p.getByTestId('ins-summary').waitFor({ timeout: 120000 });
  const summary = await p.getByTestId('ins-summary').innerText();
  if (!/(AI-worded|Rules-based) summary/.test(summary)) throw new Error('summary source not shown: ' + summary.slice(0, 80));
  await p.getByTestId('finding').first().waitFor();
  const first = p.getByTestId('finding').first();
  const text = await first.innerText();
  if (!/Observed \(from stored records\)/i.test(text)) throw new Error('facts not labelled');
  if (!/Demonstration/.test(text)) throw new Error('provenance missing');
  if (/Possible explanations/i.test(text) && !/Hypothesis, not a finding/.test(text)) throw new Error('hypotheses not labelled');
  const body = await p.getByTestId('insights').innerText();
  if (/approve the plan|place an order/i.test(body)) throw new Error('the analysis tells the user to approve or order');
  await p.screenshot({ path: `${shots}/planning-12-ai-analysis.png`, fullPage: true });
});
await step(p, 'a plan is submitted for review; the planner sees the queue read-only and cannot approve', async () => {
  const planTok = await token(world.planner);
  const r = await api(planTok, 'POST', '/plans', { name: `E2E UI review ${suffix}`, source: 'POC_SYNTHETIC', scope: { region: 'N', category: 'Bat' } });
  if (r.status !== 201) throw new Error('create ' + r.status);
  reviewPlan = r.body.id;
  const s = await api(planTok, 'POST', `/plans/${reviewPlan}/transition`, { status: 'SUBMITTED', expected_version: r.body.version });
  if (s.status !== 200) throw new Error('submit ' + s.status);
  await p.goto(`${BASE}/sales-planning?tab=review`);
  await p.getByTestId('rq-row').first().waitFor();
  await p.getByTestId('rq-readonly').waitFor();
  if ((await p.getByTestId('rq-approve').count()) !== 0 || (await p.getByTestId('rq-pick').count()) !== 0) throw new Error('a planner can select or approve');
  const bad = await api(planTok, 'POST', '/review/bulk', { plan_ids: [reviewPlan], action: 'APPROVE', preview: false, acknowledge_synthetic: true });
  if (bad.status !== 422 || bad.body.code !== 'NOT_ALL_ELIGIBLE') throw new Error('the server let a planner approve: ' + bad.status + ' ' + bad.body.code);
});
await step(m, 'the reviewer sees the regional breakdown, previews the consequences, must acknowledge demonstration data, and approves', async () => {
  await m.goto(`${BASE}/sales-planning?tab=review`);
  const row = m.locator('[data-testid="rq-row"]', { hasText: `E2E UI review ${suffix}` });
  await row.waitFor();
  await row.getByTestId('rq-breakdown-open').click();
  await m.getByTestId('rq-bars').waitFor();
  const bd = await m.getByTestId('rq-breakdown-table').innerText();
  if (!/\bN\b/.test(bd)) throw new Error('the north region is not in the breakdown');
  await m.keyboard.press('Escape');
  await row.getByTestId('rq-pick').click();
  await m.getByTestId('rq-approve').click();
  await m.getByTestId('rq-warning').waitFor();
  if (!/Orders, moves, publishes and prices nothing/.test(await m.getByTestId('rq-preview').innerText())) throw new Error('consequence not shown');
  if (!(await m.getByTestId('rq-confirm').isDisabled())) throw new Error('approval possible without acknowledging demonstration data');
  await m.getByTestId('rq-ack').click();
  await m.getByTestId('rq-confirm').click();
  await m.getByTestId('rq-result').getByText(/applied to 1 plan/).waitFor();
  const mgrTok = await token(world.manager);
  const after = await api(mgrTok, 'GET', `/plans/${reviewPlan}`);
  if (after.body.status !== 'APPROVED') throw new Error('status ' + after.body.status);
  await m.screenshot({ path: `${shots}/planning-13-review-approved.png`, fullPage: true });
});

// ------------------------------------------------------------------------------------------------ a store manager sees only their store
const { page: sm, context: sctx } = await newSession('store');
await login(sm, world.store);
await step(sm, 'a store manager with a grant sees only their store in the grid and the hierarchy', async () => {
  await sm.goto(`${BASE}/sales-planning?tab=workspace&plan=${planId}`);
  await sm.getByTestId('ws-row').first().waitFor();
  const stores = new Set(await sm.getByTestId('ws-row').evaluateAll((els) => els.map((e) => (e.getAttribute('data-key') ?? '').split('|')[1])));
  if (stores.size !== 1 || !stores.has('N-Z1-S1')) throw new Error('stores seen: ' + [...stores].join(','));
  if ((await sm.getByTestId('plan-to-APPROVED').count()) !== 0) throw new Error('approval offered to a store manager');
});

await step(sm, 'a store manager sees the review queue read-only', async () => {
  await sm.goto(`${BASE}/sales-planning?tab=review`);
  await sm.getByTestId('review-queue').waitFor();
  await sm.waitForTimeout(800);
  if ((await sm.getByTestId('rq-approve').count()) !== 0 || (await sm.getByTestId('rq-pick').count()) !== 0) throw new Error('a store manager can select or approve plans');
});
for (const [name, page] of [['planner', p], ['manager', m], ['store', sm]]) {
  if (page.__problems.length) { failed = true; log('PROBLEMS', name, page.__problems); }
}
await pctx.close(); await mctx.close(); await sctx.close();
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
