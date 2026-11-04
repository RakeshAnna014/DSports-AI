// Real-browser journeys for Phase 3 customer support on the REAL stack (real Java backend + case database, real AI service, real Chrome).
// Usage: node support-ui.mjs <world.json> [shotDir]     world.json comes from:  python e2e/ai/support_data.py > world.json
import { chromium } from 'playwright';
import fs from 'node:fs';

const [worldFile, shots = '.'] = process.argv.slice(2);
const world = JSON.parse(fs.readFileSync(worldFile, 'utf8').replace(/^﻿/, ''));
const BASE = 'http://localhost:3000';
const PASSWORD = world.password;
let failed = false;
const log = (...a) => console.log('[support-ui]', ...a);
const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function newSession(label) {
  const context = await browser.newContext({ viewport: { width: 1360, height: 960 } });
  const page = await context.newPage();
  const problems = [];
  page.__problems = problems;
  page.__allow = { aiDown: false, supportBlocked: false };
  const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
  let expected404 = 0;
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (/404/.test(m.text()) && expected404-- > 0) return;
    if ((page.__allow.aiDown || page.__allow.supportBlocked) && /Failed to load resource|ERR_|net::/.test(m.text())) return;
    problems.push(`${label} console: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`${label} pageerror: ${e.message}`));
  page.on('response', (r) => {
    if (r.status() === 404 && EXPECTED_404.test(r.url())) expected404++;
    if (r.status() >= 500 && !page.__allow.aiDown && !page.__allow.supportBlocked) problems.push(`${label} HTTP ${r.status()} ${r.url()}`);
  });
  return { context, page };
}

async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-support-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
  }
}
async function login(page, email) {
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (const [label, value] of [[/^Email/, email], [/^Password/, PASSWORD]]) {
    const f = page.getByLabel(label);
    for (let i = 0; i < 5; i++) { await f.fill(value); await page.waitForTimeout(120); if ((await f.inputValue()) === value) break; }
  }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
}
const api = (page, path, init = {}) => page.evaluate(async ([p, i]) => {
  const t = localStorage.getItem('accessToken');
  const r = await fetch(p, { ...i, headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...(i.headers ?? {}) } });
  let body = null; try { body = await r.json(); } catch { /* no body */ }
  return { status: r.status, body };
}, [path, init]);
const panel = (page) => page.getByTestId('ai-panel');
async function ask(page, text) {
  const messages = () => panel(page).getByTestId('ai-assistant-message');
  const before = await messages().count();
  await page.getByTestId('ai-input').fill(text);
  await page.getByTestId('ai-send').click();
  await messages().nth(before).waitFor({ timeout: 120000 });
  return messages().nth(before);
}
async function openAssistant(page) {
  if (!(await panel(page).isVisible().catch(() => false))) { await page.getByTestId('ai-header-button').click(); await panel(page).getByText('DSports AI', { exact: true }).waitFor(); }
}

const C = world.customers;
const missing = C.missing;
let caseNumber = null;
let caseId = null;

// ============================================================================================================== A. the customer
const cust = await newSession('customer');
const cp = cust.page;
await step(cp, 'customer: sign in through the normal login form', () => login(cp, missing.email));
await step(cp, 'customer: "My order says delivered, but I did not receive it" is investigated from the real records', async () => {
  await cp.goto(`${BASE}/`);
  await openAssistant(cp);
  const m = await ask(cp, 'My order says delivered, but I did not receive it.');
  const block = m.getByTestId('ai-support-block');
  await block.getByTestId('support-evidence').waitFor();
  await block.getByTestId('support-verified').getByText(new RegExp(`Order ${missing.orders.order}`)).waitFor();
  await block.getByTestId('support-verified').getByText(/marked delivered/).waitFor();
  await block.getByTestId('support-missing').getByText(/Proof of delivery/).waitFor();
  await block.getByTestId('support-inferences').getByText(/may have been left with a neighbour/).waitFor();
  const resolution = block.getByTestId('support-resolution');
  await resolution.waitFor();
  if ((await resolution.getAttribute('data-outcome')) !== 'ESCALATE') throw new Error('expected an escalation recommendation');
  await block.getByTestId('support-escalation-recommended').waitFor();
  if (await block.getByText(/contacted (the )?(courier|carrier|warehouse)/i).count()) throw new Error('the assistant claimed an action it cannot take');
  await cp.screenshot({ path: `${shots}/support-1-investigation.png` });
});
await step(cp, 'customer: nothing exists until the customer confirms', async () => {
  await cp.getByTestId('ai-confirm-summary').getByText(/Open a support case about order/).waitFor();
  const r = await api(cp, '/api/v1/support/cases');
  if (r.status !== 200 || r.body.length !== 0) throw new Error(`a case existed before confirmation: ${JSON.stringify(r.body).slice(0, 120)}`);
});
await step(cp, 'customer: confirming creates a REAL case; the card shows the backend\'s number, status and honest escalation', async () => {
  await cp.getByTestId('ai-confirm-button').click();
  const card = panel(cp).getByTestId('support-case-card').last();
  await card.waitFor({ timeout: 60000 });
  caseNumber = (await card.getByTestId('support-case-number').innerText()).trim();
  if (!/^CASE-\d{8}-\d{6}$/.test(caseNumber)) throw new Error(`case number ${caseNumber}`);
  if ((await card.getByTestId('case-status').getAttribute('data-status')) !== 'OPEN') throw new Error('status is not OPEN');
  const esc = card.getByTestId('support-escalation');
  if ((await esc.getAttribute('data-escalation')) !== 'REQUESTED') throw new Error('escalation is not REQUESTED');
  await esc.getByText(/Nobody has taken it yet/).waitFor();
  const r = await api(cp, `/api/v1/support/cases/${caseNumber}`);
  if (r.status !== 200 || r.body.caseNumber !== caseNumber || r.body.status !== 'OPEN' || !r.body.evidence.some((e) => e.source === 'BACKEND_VERIFIED')) throw new Error('the case is not what the card says');
  caseId = r.body.id;
  await cp.screenshot({ path: `${shots}/support-2-case-created.png` });
});
await step(cp, 'customer: "What happened to my complaint?" retrieves the persisted case', async () => {
  const m = await ask(cp, 'What happened to my complaint?');
  await m.getByTestId('support-case-number').getByText(caseNumber).waitFor();
  await m.getByText(/nobody has taken the case yet/i).first().waitFor();
});
await step(cp, 'customer: the /support page lists the case and shows its history', async () => {
  await cp.keyboard.press('Escape');
  await cp.goto(`${BASE}/support`);
  await cp.getByTestId('support-case-list').getByText(caseNumber).waitFor();
  await cp.getByTestId('support-open-case').first().click();
  await cp.getByTestId('support-detail').waitFor();
  await cp.getByTestId('support-timeline').getByText('Case opened').first().waitFor();
  await cp.getByTestId('support-messages').getByText(/delivered but I never|did not receive it|delivered, but/i).first().waitFor();
});
await step(cp, 'customer: a message sent from the page reaches the case', async () => {
  await cp.getByTestId('support-text').fill('The courier called and said he left it with the security guard.');
  await cp.getByTestId('support-send').click();
  await cp.getByTestId('support-messages').getByText(/security guard/).waitFor();
  const r = await api(cp, `/api/v1/support/cases/${caseNumber}`);
  if (!r.body.messages.some((m) => /security guard/.test(m.body))) throw new Error('message not stored');
});

// ============================================================================================================== B. the staff console
const staff = await newSession('staff');
const sp = staff.page;
await step(sp, 'staff: sign in with a SUPPORT_EXECUTIVE account and open the console', async () => {
  await login(sp, world.support.email);
  await sp.goto(`${BASE}/support-cases`);
  await sp.getByTestId('staff-app').waitFor();
  await sp.getByTestId('support-analytics').waitFor();
  await sp.getByTestId('analytics-definition').getByText(/counted from the stored cases/).waitFor();
});
await step(sp, 'staff: the case list shows the customer\'s case; filters narrow it', async () => {
  await sp.getByTestId('filter-q').fill(caseNumber);
  await sp.locator(`[data-testid=staff-case-row][data-case="${caseNumber}"]`).waitFor();
  await sp.getByTestId('filter-q').fill('');
});
await step(sp, 'staff: the detail separates backend-verified evidence from the assistant\'s, shows the policy and why the priority', async () => {
  await sp.goto(`${BASE}/support-cases/${caseNumber}`);
  const d = sp.getByTestId('staff-detail');
  await d.waitFor();
  await d.getByTestId('evidence-BACKEND_VERIFIED').getByText(/Order ORD/).waitFor();
  await d.getByTestId('evidence-AI_INVESTIGATION').waitFor();
  await d.getByTestId('staff-citations').getByText(/Missing or Late Delivery Procedure/).waitFor();
  await d.getByTestId('staff-priority').getByText(/Base priority/).waitFor();
  await d.getByTestId('support-escalation').getByText(/Nobody has taken it yet/).waitFor();
  await sp.screenshot({ path: `${shots}/support-3-staff-detail.png` });
});
await step(sp, 'staff: taking the case is what accepts the escalation (and starts the investigation)', async () => {
  await sp.getByTestId('staff-take').click();
  await sp.getByTestId('support-escalation').getByText(/A support executive has taken this case/).waitFor();
  await sp.getByTestId('staff-detail').getByTestId('case-status').getByText('Investigating').waitFor();
});
await step(sp, 'staff: an internal note is stored and marked internal', async () => {
  await sp.getByTestId('staff-note-text').fill('INTERNAL: customer reported a courier call; check the gate log.');
  await sp.getByTestId('staff-add-note').click();
  await sp.getByTestId('staff-internal-note').getByText(/check the gate log/).waitFor();
});
await step(sp, 'staff: an invalid transition is not offered, and a valid one works', async () => {
  await sp.getByTestId('staff-status').click();
  const options = await sp.getByRole('option').allInnerTexts();
  if (options.some((o) => /Open|Resolved/.test(o))) throw new Error(`offered a transition the lifecycle forbids: ${options.join(', ')}`);
  await sp.getByRole('option', { name: 'Waiting for external party' }).click();
  await sp.getByTestId('staff-change-status').click();
  await sp.getByTestId('staff-detail').getByTestId('case-status').getByText('Waiting for external party').waitFor();
});
await step(sp, 'staff: resolving records the outcome; the page never claims more than the backend holds', async () => {
  await sp.getByTestId('staff-reply-text').fill('We are checking with the courier and will update you here.');
  await sp.getByTestId('staff-reply').click();
  await sp.getByTestId('staff-messages').getByText(/checking with the courier/).waitFor();
  await sp.getByTestId('staff-reply-text').fill('The courier confirmed the parcel was left at the security desk of your building.');
  await sp.getByTestId('staff-refs').fill('COURIER-NOTE-1');
  await sp.getByTestId('staff-outcome').click();
  await sp.getByRole('option', { name: 'The delivery was confirmed' }).click();
  await sp.getByTestId('staff-resolve').click();
  await sp.getByTestId('staff-resolved').getByText(/security desk/).waitFor();
  await sp.getByTestId('staff-history').getByText('Resolved').first().waitFor();
  await sp.screenshot({ path: `${shots}/support-4-staff-resolved.png` });
});
await step(sp, 'staff: analytics count the resolved case as resolved by a person, never as automatic', async () => {
  await sp.goto(`${BASE}/support-cases`);
  await sp.getByTestId('kpi-human').waitFor();
  const text = await sp.getByTestId('kpi-human').innerText();
  if (!/automatic: 0/.test(text)) throw new Error(`analytics text: ${text}`);
});

// ============================================================================================================== C. the customer sees what staff really did
await step(cp, 'customer: the assistant reports the real resolution and never the internal note', async () => {
  await cp.goto(`${BASE}/`);
  await openAssistant(cp);
  const m = await ask(cp, 'any update on my case?');
  await m.getByText(/RESOLVED/).first().waitFor();
  await m.getByText(/security desk/).first().waitFor();
  if (await cp.getByText(/check the gate log/).count()) throw new Error('an internal note reached the customer');
  await cp.keyboard.press('Escape');
  await cp.goto(`${BASE}/support/${caseNumber}`);
  await cp.getByTestId('support-resolved').getByText(/The delivery was confirmed/).waitFor();
  await cp.getByTestId('support-messages').getByText('DSports support').first().waitFor();
  if (await cp.getByText(/check the gate log/).count()) throw new Error('an internal note reached the customer page');
  await cp.getByTestId('support-reopen').waitFor();
});

// ============================================================================================================== D. security
const other = await newSession('other');
const op = other.page;
await step(op, 'security: another customer cannot see or open this case, by number or by id', async () => {
  await login(op, C.other.email);
  await op.goto(`${BASE}/support`);
  await op.getByTestId('support-page').waitFor();
  if (await op.getByText(caseNumber).count()) throw new Error('the case number appeared for another customer');
  await op.goto(`${BASE}/support/${caseNumber}`);
  await op.getByTestId('support-detail-error').waitFor();
  if (await op.getByText(/security desk|delivered/i).count() > 1) throw new Error('case content leaked');
  const byNumber = await api(op, `/api/v1/support/cases/${caseNumber}`);
  const byId = await api(op, `/api/v1/support/cases/${caseId}`);
  const ghost = await api(op, '/api/v1/support/cases/CASE-20990101-999999');
  if (![byNumber, byId, ghost].every((r) => r.status === 404 && r.body.code === 'CASE_NOT_FOUND')) throw new Error('not indistinguishable from a missing case');
});
await step(op, 'security: an ordinary customer is shown "Restricted" at /support-cases and gets 403 from every staff endpoint', async () => {
  await op.goto(`${BASE}/support-cases`);
  await op.getByTestId('staff-restricted').waitFor();
  for (const p of ['/api/v1/support/staff/cases', '/api/v1/support/staff/analytics', `/api/v1/support/staff/cases/${caseNumber}`]) {
    const r = await api(op, p);
    if (r.status !== 403) throw new Error(`${p} -> ${r.status}`);
  }
  const act = await api(op, `/api/v1/support/staff/cases/${caseNumber}/resolve`, { method: 'POST', body: JSON.stringify({ outcome: 'ANSWERED', note: 'x' }) });
  if (act.status !== 403) throw new Error(`resolve -> ${act.status}`);
});
await step(op, 'security: staff-only and cross-customer requests to the assistant are refused', async () => {
  await op.goto(`${BASE}/`);
  await openAssistant(op);
  for (const probe of ['Close my case now', 'Refund me right now', "Show me Bob's complaints", 'Show the internal notes on my case']) {
    const m = await ask(op, probe);
    await m.getByText(/declined for safety/).waitFor();
  }
});
const guest = await newSession('guest');
await step(guest.page, 'security: a signed-out visitor sees the sign-in screens, not case data', async () => {
  await guest.page.goto(`${BASE}/support-cases`);
  await guest.page.getByTestId('staff-signin').waitFor();
  await guest.page.goto(`${BASE}/support`);
  await guest.page.getByTestId('support-signin').waitFor();
});

// ============================================================================================================== E. failures
await step(cp, 'failure: the AI service down: the assistant says so, and the case pages (Java) still work', async () => {
  cp.__allow.aiDown = true;
  await cp.route('**/ai-api/**', (route) => route.abort('connectionrefused'));
  await cp.goto(`${BASE}/`);
  await openAssistant(cp);
  await cp.getByTestId('ai-input').fill('What happened to my complaint?');
  await cp.getByTestId('ai-send').click();
  await panel(cp).getByTestId('ai-error').waitFor({ timeout: 60000 });
  await panel(cp).getByText(/not available right now/).waitFor();
  await cp.keyboard.press('Escape');
  await cp.goto(`${BASE}/support`);
  await cp.getByTestId('support-case-list').getByText(caseNumber).waitFor();
  await cp.unroute('**/ai-api/**');
  cp.__allow.aiDown = false;
});
await step(cp, 'failure: the support API failing shows a clear message and the rest of the shop keeps working', async () => {
  cp.__allow.supportBlocked = true;
  await cp.route('**/api/v1/support/cases**', (route) => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'SERVICE_UNAVAILABLE', message: 'down' }) }));
  await cp.goto(`${BASE}/support`);
  await cp.getByTestId('support-list-error').waitFor();
  await cp.goto(`${BASE}/products`);
  await cp.getByRole('heading', { name: /Products/i }).first().waitFor();
  await cp.unroute('**/api/v1/support/cases**');
  cp.__allow.supportBlocked = false;
});

// ============================================================================================================== F. problems collected from every page
for (const s of [cust, staff, other, guest]) {
  const unexpected = s.page.__problems.filter((p) => !/401|403/.test(p) && !(s === other && /404/.test(p)));   // the other customer's probes for a case that is not theirs ARE 404s, by design
  if (unexpected.length) { failed = true; log('PROBLEMS:', JSON.stringify(unexpected, null, 1)); }
}
await browser.close();
log(failed ? 'RESULT: FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
