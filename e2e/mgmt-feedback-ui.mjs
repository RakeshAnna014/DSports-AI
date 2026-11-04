// Real-browser journeys for the Management Feedback features on the REAL stack (real Java backend + database, real AI service, real Chrome):
// raise a ticket, staff dashboard handling, profile preferences, family profile switching, personalised home, budget-aware recommendations, add to cart.
// Usage: node mgmt-feedback-ui.mjs [shotDir]
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const shots = process.argv[2] ?? '.';
const BASE = 'http://localhost:3000';
const API = 'http://127.0.0.1:8080';
const PASSWORD = 'Passw0rd!x';
const RACQUET = '00000000-0000-0000-0000-000000001070';   // Head Tennis Racquet - Performance (about Rs 10,000)
const SS_BAT = '00000000-0000-0000-0000-000000001002';
const run = Math.random().toString(36).slice(2, 8);
let failed = false;
const log = (...a) => console.log('[mgmt-ui]', ...a);

// Same Docker lookup as e2e/ai/stack.py: an explicit path first, because a shell started by a script may not have Docker on its PATH.
const DOCKER = process.env.DOCKER_BIN || (existsSync('C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe') ? 'C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe' : 'docker');
const sql = (q) => execFileSync(DOCKER, ['exec', 'dsports-postgres', 'psql', '-U', 'dsports', '-d', 'dsports', '-tAc', q], { encoding: 'utf8' }).trim();
async function register(prefix) {
  const email = `${prefix}-${run}@example.com`;
  await fetch(`${API}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: PASSWORD, firstName: 'Asha', lastName: 'Rao' }) });
  return email;
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
async function session(label) {
  const context = await browser.newContext({ viewport: { width: 1360, height: 960 } });
  const page = await context.newPage();
  page.setDefaultNavigationTimeout(180000);     // the first load after a Vite restart pre-bundles MUI and can be slow
  page.setDefaultTimeout(60000);
  page.__problems = [];
  const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$/;
  let expected404 = 0;
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (/404/.test(m.text()) && expected404-- > 0) return;
    page.__problems.push(`${label} console: ${m.text()}`);
  });
  page.on('pageerror', (e) => page.__problems.push(`${label} pageerror: ${e.message}`));
  page.on('response', (r) => {
    if (r.status() === 404 && EXPECTED_404.test(r.url())) expected404++;
    if (r.status() >= 500) page.__problems.push(`${label} HTTP ${r.status()} ${r.url()}`);
  });
  return { context, page };
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-mgmt-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
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
const choose = async (page, name, option) => {
  await page.getByRole('combobox', { name }).click();
  await page.getByRole('option', { name: option }).click();
};

const customerEmail = await register('mfui-cust');
const staffEmail = await register('mfui-staff');
sql(`INSERT INTO customer_roles (customer_id, role) SELECT id, 'SUPPORT_EXECUTIVE' FROM customers WHERE email='${staffEmail}' ON CONFLICT DO NOTHING`);
const subject = `MFUI-${run} the website shows an error at checkout`;
let ticketNumber = '';

// ------------------------------------------------------------------ customer: ticket
const cust = await session('customer');
await login(cust.page, customerEmail);
await step(cust.page, 'raise a ticket from the account menu', async () => {
  const p = cust.page;
  await p.getByRole('button', { name: 'account menu' }).click();
  await p.getByTestId('menu-raise-ticket').click();
  await p.getByTestId('raise-ticket-dialog').waitFor();
  await choose(p, /category/i, 'Website / technical');
  await p.getByLabel(/subject/i).fill(subject);
  await p.getByLabel(/describe the problem/i).fill('The checkout page shows an error after I choose delivery.');
  await p.getByTestId('ticket-review-btn').click();
  await p.getByTestId('ticket-review').waitFor();
  const review = await p.getByTestId('ticket-review').innerText();
  if (!review.includes('Website / technical') || !review.includes(subject)) throw new Error('review step does not show what was entered');
  await p.getByTestId('ticket-submit').click();
  await p.waitForURL(/\/support\/CASE-/);
  ticketNumber = new URL(p.url()).pathname.split('/').pop();
  await p.getByTestId('support-detail').waitFor();
});
await step(cust.page, 'the new ticket is listed with its real number and is persisted', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/support`);
  await p.getByText(ticketNumber).first().waitFor();
  if (sql(`select count(*) from support_cases where case_number='${ticketNumber}' and category='TECHNICAL_ISSUE'`) !== '1') throw new Error('ticket is not in the database');
});
await step(cust.page, 'Contact support is also reachable from the profile page', async () => {
  await cust.page.goto(`${BASE}/profile`);
  await cust.page.getByTestId('profile-contact-support').click();
  await cust.page.getByTestId('raise-ticket-dialog').waitFor();
  await cust.page.keyboard.press('Escape');
});

// ------------------------------------------------------------------ staff: dashboard
const staff = await session('staff');
await login(staff.page, staffEmail);
await step(staff.page, 'staff sees database-backed metrics and finds the ticket by number', async () => {
  const p = staff.page;
  await p.goto(`${BASE}/support-cases`);
  await p.getByTestId('support-analytics').waitFor();
  const shown = Number((await p.getByTestId('kpi-open').innerText()).match(/\n(\d+)/)?.[1]);
  const db = Number(sql("select count(*) from support_cases where status='OPEN'"));
  if (shown !== db) throw new Error(`dashboard OPEN ${shown} != database ${db}`);
  await p.getByTestId('filter-q').fill(ticketNumber);
  await p.locator(`[data-testid="staff-case-row"][data-case="${ticketNumber}"]`).waitFor();
  const rows = await p.getByTestId('staff-case-row').count();
  if (rows !== 1) throw new Error(`search by ticket number returned ${rows} rows`);
});
await step(staff.page, 'staff takes the ticket, raises its priority with a reason, replies and adds an internal note', async () => {
  const p = staff.page;
  await p.locator(`[data-testid="staff-case-row"][data-case="${ticketNumber}"]`).click();
  await p.getByTestId('staff-detail').waitFor();
  await p.getByTestId('staff-take').click();
  await p.getByText(/Investigating/).first().waitFor();
  await choose(p, /change priority/i, 'HIGH');
  await p.getByTestId('staff-priority-reason').fill('Blocks checkout for the customer');
  await p.getByTestId('staff-change-priority').click();
  await p.getByTestId('staff-priority').getByText(/Blocks checkout/).waitFor();
  await p.getByTestId('staff-note-text').fill('INTERNAL-ONLY gateway timeout in the logs');
  await p.getByTestId('staff-add-note').click();
  await p.getByTestId('staff-internal-note').first().waitFor();
  await p.getByTestId('staff-reply-text').fill('Thanks, we are investigating the checkout error.');
  await p.getByTestId('staff-reply').click();
  await p.getByTestId('staff-message').getByText(/investigating the checkout error/).waitFor();
  await choose(p, /change status/i, /Waiting for customer/);
  await p.getByTestId('staff-change-status').click();
  await p.getByText(/Waiting for customer/).first().waitFor();
});
await step(staff.page, 'the dashboard reflects the persisted change after a refresh', async () => {
  const p = staff.page;
  await p.reload();
  await p.getByTestId('support-analytics').waitFor();
  const db = Number(sql("select count(*) from support_cases where status='WAITING_FOR_CUSTOMER'"));
  const inProgress = Number((await p.getByTestId('kpi-in-progress').innerText()).match(/\n(\d+)/)?.[1]);
  const dbProgress = Number(sql("select count(*) from support_cases where status in ('INVESTIGATING','WAITING_FOR_CUSTOMER','WAITING_FOR_EXTERNAL_PARTY')"));
  if (inProgress !== dbProgress || db < 1) throw new Error(`in-progress ${inProgress} vs database ${dbProgress}`);
});
await step(staff.page, 'a customer cannot open the staff console (backend 403, restricted page)', async () => {
  const other = await session('customer2');
  await login(other.page, customerEmail);
  await other.page.goto(`${BASE}/support-cases`);
  await other.page.getByTestId('staff-restricted').waitFor();
  const r = await api(other.page, '/api/v1/support/staff/cases');
  if (r.status !== 403) throw new Error(`staff API answered ${r.status} to a customer`);
  await other.context.close();
});
await step(cust.page, 'the customer sees the reply and status, and never the internal note', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/support/${ticketNumber}`);
  await p.getByTestId('support-detail').waitFor();
  await p.getByText(/investigating the checkout error/).first().waitFor();
  const text = await p.getByTestId('support-detail').innerText();
  if (text.includes('INTERNAL-ONLY') || text.includes('Blocks checkout')) throw new Error('internal information is visible to the customer');
});

// ------------------------------------------------------------------ customer: preferences and family
await step(cust.page, 'save sports and budget preferences; they persist after a reload', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/profile`);
  await p.getByTestId('preferences-editor').waitFor();
  const sports = p.getByRole('combobox', { name: /^sports/i });
  await sports.click();
  await p.getByRole('option', { name: 'Tennis', exact: true }).click();
  await p.keyboard.press('Escape');
  await p.getByLabel(/price up to/i).fill('12000');
  await p.getByTestId('preferences-save').click();
  await p.getByTestId('preferences-message').waitFor();
  await p.reload();
  await p.getByTestId('preferences-editor').waitFor();
  await p.getByText('Tennis').first().waitFor();
  if ((await p.getByLabel(/price up to/i).inputValue()) !== '12000') throw new Error('price range was not persisted');
});
await step(cust.page, 'add a family member with an age band and switch to them', async () => {
  const p = cust.page;
  await p.getByTestId('family-add').click();
  await p.getByTestId('family-name').fill('Meera');
  await choose(p, /age band/i, '13 to 17');
  await p.getByTestId('family-save').click();
  await p.getByTestId('profile-chip-family').waitFor();
  const chips = await p.locator('[data-testid^="profile-chip"]').allInnerTexts();
  if (!chips.some((c) => c.includes('Me (account holder)')) || !chips.some((c) => c.includes('Meera · family member'))) throw new Error(`owner and member not clearly distinguished: ${chips}`);
  await p.getByTestId('profile-use').click();
  await p.getByText('Active for recommendations').waitFor();
});

// ------------------------------------------------------------------ home page personalisation follows the selected profile
await step(cust.page, 'home page: the owner profile gets Tennis picks with factual reasons', async () => {
  const p = cust.page;
  const r = await api(p, '/api/customers/me/profiles');
  const owner = r.body.profiles.find((x) => x.kind === 'OWNER');
  await api(p, '/api/customers/me/profiles/active', { method: 'PUT', body: JSON.stringify({ profileId: owner.id }) });
  await p.goto(BASE);
  const section = p.getByTestId('home-personalized');
  await section.waitFor({ timeout: 60000 });
  const cards = await section.getByTestId('recommendation-card').allInnerTexts();
  if (cards.length === 0) throw new Error('no personalised cards');
  if (!(await section.innerText()).includes('sport in your profile')) throw new Error('reasons do not name the real signal');
  if (!(await p.getByTestId('profile-switcher').isVisible())) throw new Error('profile switcher missing');
});
await step(cust.page, 'home page: switching profile changes the personalised section', async () => {
  const p = cust.page;
  const before = await p.getByTestId('home-personalized').innerText();
  await p.getByTestId('switch-family').click();
  await p.waitForTimeout(1500);
  const prompt = await p.getByTestId('personalization-prompt').isVisible().catch(() => false);
  const after = prompt ? 'PROMPT' : await p.getByTestId('home-personalized').innerText().catch(() => 'NONE');
  if (after === before) throw new Error('the section did not change when the profile changed');
  await p.getByTestId('switch-owner').click().catch(() => {});
});

// ------------------------------------------------------------------ product page: budget-aware recommendations
await step(cust.page, 'product page: complementary items respect the 20% ceiling and say so', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/products/${RACQUET}`);
  const section = p.getByTestId('scenario-recommendations');
  await section.waitFor({ timeout: 60000 });
  await section.getByText(/Up to 20% of the price/).first().waitFor({ timeout: 60000 });
  const prices = (await section.getByTestId('recommendation-card').allInnerTexts()).map((t) => Number((t.match(/₹([\d,]+\.\d\d)/) ?? [])[1]?.replace(/,/g, '')));
  if (prices.length === 0 || prices.some((v) => !(v <= 2099.8))) throw new Error(`prices above the 20% ceiling: ${prices}`);
});
await step(cust.page, 'product page: adjust budget applies the explicit budget', async () => {
  const section = cust.page.getByTestId('scenario-recommendations');
  await section.getByTestId('reco-adjust-budget').click();
  await section.getByTestId('reco-budget-input').fill('500');
  await section.getByTestId('reco-budget-apply').click();
  await section.getByText(/Within your budget of ₹500/).first().waitFor({ timeout: 60000 });
  await section.getByTestId('reco-clear-budget').waitFor();
});
await step(cust.page, 'product page: premium alternatives only on request, dearer comparable bats', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/products/${SS_BAT}`);
  const section = p.getByTestId('scenario-recommendations');
  await section.waitFor({ timeout: 60000 });
  await section.getByTestId('reco-show-premium').click();
  await section.getByText(/Premium options priced/).first().waitFor({ timeout: 60000 });
  const names = await section.getByTestId('recommendation-card').allInnerTexts();
  if (!names.some((t) => /Bat/i.test(t))) throw new Error(`no premium bats shown: ${names.join(' | ').slice(0, 200)}`);
});
await step(cust.page, 'product page: add a recommended product to the cart through the real cart API', async () => {
  const p = cust.page;
  await p.goto(`${BASE}/products/${RACQUET}`);
  const section = p.getByTestId('scenario-recommendations');
  await section.getByTestId('recommendation-add').first().waitFor({ timeout: 60000 });
  await section.getByTestId('recommendation-add').first().click();
  await p.getByText(/added to cart/).first().waitFor();
  const cart = await api(p, '/api/v1/cart');
  if (cart.status !== 200 || cart.body.items.length < 1) throw new Error('the cart does not contain the added product');
});

for (const s of [cust, staff]) {
  if (s.page.__problems.length) { failed = true; log('PROBLEMS', s.page.__problems.slice(0, 6).join(' || ')); }
}
await browser.close();
log(failed ? 'RESULT: some steps FAILED' : 'RESULT: all steps passed');
process.exit(failed ? 1 : 0);
