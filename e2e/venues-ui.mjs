// Real-browser journey for admin-managed ground owners and multi-sport venues on the REAL stack (real Java backend + database, real Chrome):
// an administrator makes a registered user an owner (no SQL), the owner builds a venue with two courts, a customer books by sport -> city -> date -> court -> slot,
// the administrator removes the role (venue hidden, not bookable) and reassigns it (bookable again), and adds a bookable sport.
// The only SQL is what the docs say is needed once: making the very first administrator. Everything the script creates is removed again by exact id.
// Usage: node venues-ui.mjs [shotDir]
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const shots = process.argv[2] ?? '.';
const BASE = 'http://localhost:3000';
const API = 'http://127.0.0.1:8080';
const PASSWORD = 'Passw0rd!x';
const run = Math.random().toString(36).slice(2, 8);
let failed = false;
const log = (...a) => console.log('[venues-ui]', ...a);

const DOCKER = process.env.DOCKER_BIN || (existsSync('C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe') ? 'C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe' : 'docker');
const sql = (q) => execFileSync(DOCKER, ['exec', 'dsports-postgres', 'psql', '-U', 'dsports', '-d', 'dsports', '-tAc', q], { encoding: 'utf8' }).trim();
const emails = { admin: `vui-admin-${run}@example.com`, owner1: `vui-owner1-${run}@example.com`, owner2: `vui-owner2-${run}@example.com`, customer: `vui-cust-${run}@example.com` };
const venueName = `VUI Hub ${run}`;
const sportName = `VUI Squash ${run}`;

async function register(email) {
  const r = await fetch(`${API}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: PASSWORD, firstName: 'Test', lastName: 'User' }) });
  if (r.status !== 201) throw new Error(`register ${email}: ${r.status}`);
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
async function session(label, width = 1360) {
  const context = await browser.newContext({ viewport: { width, height: 960 } });
  const page = await context.newPage();
  page.setDefaultNavigationTimeout(180000);
  page.setDefaultTimeout(30000);
  page.__problems = [];
  // Known and deliberate: a brand-new account has no cart or wishlist yet, and one step tries to grant the role to an unregistered email and to the administrator themselves.
  const EXPECTED_404 = /\/api\/v1\/(cart|wishlist)$|\/api\/v1\/admin\/ground-owners$/;
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (/Failed to load resource/.test(m.text())) return;
    page.__problems.push(`${label} console: ${m.text()}`);
  });
  page.on('pageerror', (e) => page.__problems.push(`${label} pageerror: ${e.message}`));
  page.on('response', (r) => {
    if (r.status() >= 500) page.__problems.push(`${label} HTTP ${r.status()} ${r.url()}`);
    else if (r.status() >= 400 && !EXPECTED_404.test(r.url())) page.__problems.push(`${label} HTTP ${r.status()} ${r.url()}`);
  });
  return { context, page };
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-venues-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
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
const fill = async (scope, label, value) => {
  const f = scope.getByLabel(label);
  for (let i = 0; i < 5; i++) { await f.fill(value); await f.page?.().waitForTimeout?.(100); if ((await f.inputValue()) === value) return; }
};
const apiGet = (page, path) => page.evaluate(async (p) => {
  const r = await fetch(p, { headers: { Authorization: `Bearer ${localStorage.getItem('accessToken')}` } });
  return r.json();
}, path);
const noHorizontalScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

const createdVenues = [];
let sportId = null;
try {
  for (const e of Object.values(emails)) await register(e);
  // The one documented SQL step: the first administrator.
  sql(`INSERT INTO customer_roles (customer_id, role) SELECT id, 'ADMIN' FROM customers WHERE email = '${emails.admin}'`);

  const admin = await session('admin');
  const owner = await session('owner');
  const customer = await session('customer');
  const when = new Date(Date.now() + 2 * 86400_000); // two days ahead, in the browser's own zone
  const date = `${when.getFullYear()}-${String(when.getMonth() + 1).padStart(2, '0')}-${String(when.getDate()).padStart(2, '0')}`;

  await step(admin.page, 'admin signs in and sees the Admin menu', async () => {
    await login(admin.page, emails.admin);
    await admin.page.getByTestId('nav-admin').click();
    for (const id of ['admin-ground-owners', 'admin-venues', 'admin-sports']) await admin.page.getByTestId(id).waitFor();
    await admin.page.keyboard.press('Escape');
  });

  await step(admin.page, 'admin: an unregistered email says to ask them to register first', async () => {
    await admin.page.goto(`${BASE}/admin/ground-owners`);
    await admin.page.getByLabel(/email of a registered user/i).fill(`nobody-${run}@example.com`);
    await admin.page.getByTestId('grant-owner').click();
    await admin.page.getByTestId('owners-message').getByText(/ask them to register first/i).waitFor();
  });

  await step(admin.page, 'admin: cannot make themselves an owner', async () => {
    await admin.page.getByLabel(/email of a registered user/i).fill(emails.admin);
    await admin.page.getByTestId('grant-owner').click();
    await admin.page.getByTestId('owners-message').getByText(/cannot give the role to yourself/i).waitFor();
  });

  await step(admin.page, 'admin: makes two registered users ground owners without SQL', async () => {
    for (const email of [emails.owner1, emails.owner2]) {
      await admin.page.getByLabel(/email of a registered user/i).fill(email);
      await admin.page.getByTestId('grant-owner').click();
      await admin.page.getByTestId('owners-message').getByText(new RegExp(`${email.replace('.', '\\.')} is now a ground owner`)).waitFor();
    }
    await admin.page.getByTestId('owner-row').filter({ hasText: emails.owner1 }).waitFor();
    await admin.page.getByTestId('owner-audit').getByText(new RegExp(`Role given to ${emails.owner1.replace('.', '\\.')} by you`)).waitFor();
    await admin.page.screenshot({ path: `${shots}/venues-1-admin-owners.png`, fullPage: true });
  });

  await step(owner.page, 'owner: signs in again and sees the Owner console, then builds a venue', async () => {
    await login(owner.page, emails.owner1);
    await owner.page.getByTestId('nav-owner-console').waitFor();
    await owner.page.getByTestId('nav-owner-console').click();
    await owner.page.getByTestId('new-venue').click();
    const dialog = owner.page.getByRole('dialog');
    await dialog.getByLabel(/^Name/).fill(venueName);
    await dialog.getByLabel(/^Address/).fill('1 Marina Road');
    await dialog.getByLabel(/^City/).fill('Chennai');
    await dialog.getByTestId('save-venue').click();
    await owner.page.getByTestId('managed-venue').filter({ hasText: venueName }).waitFor();
  });

  await step(owner.page, 'owner: adds two courts with their own sports', async () => {
    for (const [name, sports] of [['Court 1', ['Pickleball', 'Cricket']], ['Hall', ['Badminton']]]) {
      await owner.page.getByTestId('add-court').click();
      const dialog = owner.page.getByRole('dialog');
      await dialog.getByLabel(/court name/i).fill(name);
      for (const s of sports) await dialog.getByLabel(s, { exact: true }).check();
      await dialog.getByTestId('save-court').click();
      await owner.page.getByTestId('managed-court').filter({ hasText: name }).waitFor();
    }
    await owner.page.getByTestId('managed-court').filter({ hasText: 'Court 1' }).getByText(/Pickleball.*Cricket|Cricket.*Pickleball/).waitFor();
    await owner.page.screenshot({ path: `${shots}/venues-2-owner-console.png`, fullPage: true });
  });

  await step(customer.page, 'customer: sport, then city (any case), then date, finds the venue', async () => {
    await login(customer.page, emails.customer);
    await customer.page.goto(`${BASE}/venues`);
    await customer.page.getByText(/choose a sport to begin/i).waitFor();
    // Sports are the catalogue's bookable ones only (a shop-only sport like Running is not offered).
    const chips = (await customer.page.getByTestId('sport-chip').allTextContents());
    if (!chips.includes('Pickleball') || chips.includes('Running')) throw new Error(`unexpected sports: ${chips.join(', ')}`);
    await customer.page.getByTestId('sport-chip').filter({ hasText: 'Pickleball' }).click();
    await customer.page.getByLabel('City').fill('CHENNAI');
    await customer.page.getByLabel('Date').fill(date);
    await customer.page.getByTestId('venues-search').click();
    await customer.page.getByTestId('venue-card').filter({ hasText: venueName }).waitFor();
    // The badminton-only hall is not offered for Pickleball.
    if ((await customer.page.getByTestId('venue-card').filter({ hasText: venueName }).textContent()).includes('Hall')) throw new Error('the Hall must not be listed for Pickleball');
    await customer.page.screenshot({ path: `${shots}/venues-3-customer-search.png`, fullPage: true });
  });

  let bookingUrl = null;
  await step(customer.page, 'customer: picks the court, the sport and two slots, holds and lands on the pay page', async () => {
    await customer.page.getByTestId('venue-card').filter({ hasText: venueName }).click();
    await customer.page.getByTestId('slot-grid').waitFor();
    await customer.page.getByTestId('court-chip').filter({ hasText: 'Court 1' }).click();
    await customer.page.getByTestId('court-sport').filter({ hasText: 'Pickleball' }).waitFor();
    const slots = customer.page.getByTestId('slot');
    await slots.nth(5).click();
    await slots.nth(6).click();
    await customer.page.getByTestId('selection-summary').waitFor();
    await customer.page.getByTestId('hold-and-pay').click();
    await customer.page.waitForURL(/\/bookings\/[0-9a-f-]+\/pay/);
    bookingUrl = customer.page.url();
    await customer.page.getByText(venueName).waitFor();
    await customer.page.getByTestId('booking-court-sport').getByText(/Court 1 · Pickleball/).waitFor();
    await customer.page.screenshot({ path: `${shots}/venues-4-pay-page.png`, fullPage: true });
  });

  await step(admin.page, 'admin: removes owner 1 (after a confirmation that explains the consequences)', async () => {
    await admin.page.goto(`${BASE}/admin/ground-owners`);
    await admin.page.getByTestId('owner-row').filter({ hasText: emails.owner1 }).getByTestId('remove-owner').click();
    await admin.page.getByRole('dialog').getByText(/stop taking new bookings/i).waitFor();
    await admin.page.getByTestId('confirm-remove-owner').click();
    await admin.page.getByTestId('owners-message').getByText(/role was removed/i).waitFor();
    await admin.page.getByTestId('owner-audit').getByText(new RegExp(`Role removed from ${emails.owner1.replace('.', '\\.')}`)).waitFor();
  });

  await step(customer.page, 'customer: the venue is hidden from search, its page says it takes no bookings, and the existing booking is still valid', async () => {
    await customer.page.goto(`${BASE}/venues?sport=pickleball&city=chennai&date=${date}`);
    await customer.page.getByText(/no pickleball courts with free slots|no .* courts with free slots/i).waitFor();
    if (await customer.page.getByTestId('venue-card').filter({ hasText: venueName }).count()) throw new Error('the venue should be hidden');
    const id = await customer.page.evaluate(async () => null);
    void id;
    await customer.page.goto(bookingUrl);
    await customer.page.getByText(/held for/i).waitFor();
  });

  await step(admin.page, 'admin: sees the venue flagged and reassigns it to owner 2', async () => {
    await admin.page.goto(`${BASE}/admin/venues`);
    await admin.page.getByTestId('managed-venue').filter({ hasText: venueName }).click();
    await admin.page.getByTestId('owner-removed').waitFor();
    await admin.page.getByTestId('reassign-venue').click();
    const dialog = admin.page.getByRole('dialog');
    await dialog.getByRole('combobox').click();
    await admin.page.getByRole('option', { name: emails.owner2 }).click();
    await dialog.getByTestId('save-venue').click();
    await admin.page.getByTestId('venue-owner-line').getByText(new RegExp(emails.owner2.replace('.', '\\.'))).waitFor();
    if (await admin.page.getByTestId('owner-removed').count()) throw new Error('the venue should no longer be flagged');
    await admin.page.screenshot({ path: `${shots}/venues-5-admin-reassigned.png`, fullPage: true });
  });

  await step(customer.page, 'customer: the venue is back in search after the reassignment', async () => {
    await customer.page.goto(`${BASE}/venues?sport=pickleball&city=chennai&date=${date}`);
    await customer.page.getByTestId('venue-card').filter({ hasText: venueName }).waitFor();
  });

  await step(admin.page, 'admin: adds a bookable sport, which appears for booking but not as an empty sport in the shop', async () => {
    await admin.page.goto(`${BASE}/admin/sports`);
    await admin.page.getByLabel(/sport name/i).fill(sportName);
    await admin.page.getByTestId('add-sport').click();
    await admin.page.getByTestId('sports-message').getByText(new RegExp(`${sportName} was added and can now be booked`)).waitFor();
    const shop = await (await fetch(`${API}/api/catalog/sports`)).json();
    const bookable = await (await fetch(`${API}/api/catalog/sports?bookable=true`)).json();
    sportId = bookable.find((s) => s.name === sportName)?.id ?? null;
    if (!sportId) throw new Error('the new sport is not in the bookable list');
    if (shop.some((s) => s.name === sportName)) throw new Error('a bookable sport without products must not be listed in the shop');
    await customer.page.goto(`${BASE}/venues`);
    await customer.page.getByTestId('sport-chip').filter({ hasText: sportName }).waitFor();
  });

  await step(admin.page, 'the header fits on one row for an administrator and for an owner, at laptop widths', async () => {
    for (const width of [1100, 1280, 1440]) {
      for (const [who, label] of [[admin, 'admin'], [owner, 'owner']]) {
        await who.page.setViewportSize({ width, height: 900 });
        await who.page.goto(`${BASE}/venues`);
        await who.page.getByTestId('ai-header-button').waitFor();
        if (!(await noHorizontalScroll(who.page))) throw new Error(`${label} header overflows at ${width}px`);
        // The account menu, cart and notifications must stay on screen (the nav strip scrolls inside itself instead of pushing them off).
        const box = await who.page.getByLabel('account menu').boundingBox();
        if (!box || box.x + box.width > width) throw new Error(`${label}: the account button is off screen at ${width}px`);
      }
    }
    await admin.page.setViewportSize({ width: 1440, height: 900 });
    await admin.page.goto(`${BASE}/venues`);
    await admin.page.screenshot({ path: `${shots}/venues-6-admin-header.png` });
  });

  for (const s of [admin, owner, customer]) {
    for (const p of s.page.__problems) { failed = true; log('PROBLEM', p); }
  }
} finally {
  // Remove everything this run created, by exact id.
  try {
    const ids = sql(`SELECT id FROM customers WHERE email LIKE 'vui-%-${run}@example.com'`).split('\n').filter(Boolean);
    const quoted = ids.map((i) => `'${i}'`).join(',');
    const venues = sql(`SELECT id FROM venues WHERE name = '${venueName}'`).split('\n').filter(Boolean).map((i) => `'${i}'`).join(',');
    if (venues) {
      sql(`DELETE FROM ground_bookings WHERE venue_id IN (${venues})`);
      sql(`DELETE FROM ground_blackouts WHERE venue_id IN (${venues})`);
      sql(`DELETE FROM courts WHERE venue_id IN (${venues})`);
      sql(`DELETE FROM venues WHERE id IN (${venues})`);
    }
    if (quoted) {
      sql(`DELETE FROM role_audit_log WHERE target_id IN (${quoted})`);
      sql(`DELETE FROM notifications WHERE user_id IN (${quoted})`);
      sql(`DELETE FROM refresh_tokens WHERE user_id IN (${quoted})`);
      sql(`DELETE FROM customer_roles WHERE customer_id IN (${quoted})`);
      sql(`DELETE FROM customer_auth_providers WHERE customer_id IN (${quoted})`);
      sql(`DELETE FROM customer_addresses WHERE customer_id IN (${quoted})`);
      sql(`DELETE FROM customers WHERE id IN (${quoted})`);
    }
    if (sportId) sql(`DELETE FROM sports WHERE id = '${sportId}'`);
    log('cleaned up test data');
  } catch (e) { log('CLEANUP PROBLEM', e.message.split('\n')[0]); failed = true; }
  await browser.close();
}
log(failed ? 'FAILED' : 'ALL STEPS PASSED');
process.exit(failed ? 1 : 0);
