// EMULATED two-device run of ball-by-ball scoring on the REAL stack (real Java backend + database, real Chrome). It is NOT a real phone:
//   * the "phone" is a Chrome context with a phone-sized screen, touch input, a mobile user agent and a throttled-off network switch (setOffline stands in for airplane mode);
//   * the "laptop" is a second Chrome context, signed out, on a desktop-sized screen.
// The organiser scores on the phone context while the signed-out laptop context follows the public scorecard, and each change is asserted on the laptop:
// the match not started yet, the first ball, runs, a wicket (with how the batter was out), a mistaken ball and its undo, and one ball sent while the phone is offline.
// It also checks that the signed-out visitor sends no credentials, that the polling uses the ETag (304s), that nothing else about the match is readable without a sign-in,
// the live list with its city filter, the share buttons and that the public page fits a phone.
// Use E2E_BASE=http://<laptop LAN address>:3000 (with `pnpm exec vite --host`) to go through the network address, over plain http, as a real phone would.
// The only SQL is the clean-up: the scoring log is append-only (a trigger refuses DELETE), so this local test data is removed with session_replication_role = replica,
// which only a database superuser may use. Everything the script creates is removed by exact id. Because that switch turns the guard off for the whole session, the script
// REFUSES to run unless it is talking to the local test stack (see assertLocalTestDatabase): a loopback API address, the local container "dsports-postgres", the database
// "dsports" reached over the container's own socket, and, before the clean-up, proof that the match it made is in that very database.
// Usage: node cricket-ui.mjs [shotDir]
import { chromium, devices } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const shots = process.argv[2] ?? '.';
const BASE = process.env.E2E_BASE ?? 'http://localhost:3000';
const API = process.env.E2E_API ?? 'http://127.0.0.1:8080';
const PASSWORD = 'Passw0rd!x';
const run = Math.random().toString(36).slice(2, 8);
const city = `Emucity ${run}`;
let failed = false;
const log = (...a) => console.log('[cricket-ui]', ...a);

const DOCKER = process.env.DOCKER_BIN || (existsSync('C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe') ? 'C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe' : 'docker');
const sql = (q) => execFileSync(DOCKER, ['exec', TEST_CONTAINER, 'psql', '-U', 'dsports', '-d', TEST_DATABASE, '-tAc', q], { encoding: 'utf8' }).trim();

const email = `cui-${run}@example.com`;
const created = { match: null, teams: [], players: [] };

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);
const TEST_CONTAINER = 'dsports-postgres';
const TEST_DATABASE = 'dsports';

/** Throws unless every check says this is the local test database. Run before the run starts (no match yet) and again, with the match's id, before the clean-up bypasses the guard. */
function assertLocalTestDatabase(matchId) {
  const apiHost = new URL(API).hostname;
  if (!LOCAL_HOSTS.has(apiHost)) throw new Error(`refusing to run: the API host is "${apiHost}", not this machine (loopback only)`);
  const row = sql(`SELECT current_database() || '|' || coalesce(inet_server_addr()::text, 'socket') || '|' || (SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user)`);
  const [database, address] = row.split('|');
  if (database !== TEST_DATABASE) throw new Error(`refusing to run: the database is "${database}", not "${TEST_DATABASE}"`);
  if (address !== 'socket') throw new Error(`refusing to run: the database was reached over the network (${address}), not the container's own socket`);
  if (matchId && sql(`SELECT count(*) FROM matches WHERE id = '${matchId}'`) !== '1') throw new Error('refusing to clean up: the match this run made is not in this database, so it is not the one the API wrote to');
}

async function api(method, path, token, body) {
  const r = await fetch(`${API}${path}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null };
}
const must = (r, expected, what) => { if (r.status !== expected) throw new Error(`${what}: HTTP ${r.status} ${JSON.stringify(r.body)}`); return r.body; };

/** A match between Lions and Tigers (4 a side, 2 overs, tennis ball) with the toss and both XIs done, made through the API. */
async function setupMatch(token) {
  const sides = [['Lions', ['Arun', 'Bala', 'Chetan', 'Dev']], ['Tigers', ['Tarun', 'Uday', 'Vik', 'Wasim']]];
  const teamIds = [];
  const playerIds = [];
  for (const [team, names] of sides) {
    const t = must(await api('POST', '/api/v1/teams', token, { name: team }), 201, `team ${team}`);
    created.teams.push(t.id);
    teamIds.push(t.id);
    const ids = [];
    for (const name of names) {
      const p = must(await api('POST', '/api/v1/players', token, { name }), 201, `player ${name}`);
      created.players.push(p.id);
      ids.push(p.id);
      must(await api('POST', `/api/v1/teams/${t.id}/members`, token, { playerId: p.id }), 201, `member ${name}`);
    }
    playerIds.push(ids);
  }
  const m = must(await api('POST', '/api/v1/matches', token, { teamAId: teamIds[0], teamBId: teamIds[1], overs: 2, ballType: 'TENNIS', city, rules: { playersPerSide: 4 } }), 201, 'match');
  created.match = m.id;
  must(await api('POST', `/api/v1/matches/${m.id}/toss`, token, { winnerTeamId: teamIds[0], decision: 'BAT' }), 200, 'toss');
  for (let i = 0; i < 2; i++) must(await api('PUT', `/api/v1/matches/${m.id}/playing-xi`, token, { teamId: teamIds[i], playerIds: playerIds[i] }), 200, `xi ${i}`);
  return m.id;
}

try { assertLocalTestDatabase(null); } catch (e) { log(e.message); process.exit(2); }
const browser = await chromium.launch({ channel: 'chrome', headless: true });
function session(label, options) {
  return browser.newContext(options).then((context) => {
    const problems = [];
    return { context, problems, label, open: async () => {
      const page = await context.newPage();
      page.setDefaultNavigationTimeout(180000);
      page.setDefaultTimeout(30000);
      page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource/.test(m.text())) problems.push(`${label} console: ${m.text()}`); });
      page.on('pageerror', (e) => problems.push(`${label} pageerror: ${e.message}`));
      return page;
    } };
  });
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-cricket-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
  }
}
const noHorizontalScroll = (page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1);

/** The laptop's page shows `text` in the element within `ms`; says how long it took after `since`. */
async function laptopShows(laptop, testId, text, since, ms = 20000) {
  await laptop.getByTestId(testId).filter({ hasText: text }).waitFor({ timeout: ms });
  return Date.now() - since;
}

let phoneSession;
let laptopSession;
try {
  must(await api('POST', '/api/auth/register', null, { email, password: PASSWORD, firstName: 'Cricket', lastName: 'Tester' }), 201, 'register');
  const login = must(await api('POST', '/api/auth/login', null, { email, password: PASSWORD }), 200, 'login');
  const token = login.accessToken;
  const matchId = await setupMatch(token);
  log('match', matchId, 'in', city, 'via', BASE);

  phoneSession = await session('phone', { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } });
  laptopSession = await session('laptop', { viewport: { width: 1360, height: 900 } });
  const phone = await phoneSession.open();
  const laptop = await laptopSession.open();

  // What the signed-out laptop sends and receives for the scorecard.
  const seen = { statuses: [], withAuthorization: 0, withEtag: 0, scorecardCalls: 0 };
  laptop.on('request', (r) => {
    if (!/\/api\//.test(r.url())) return;
    if (r.headers()['authorization']) seen.withAuthorization++;
    if (/\/scorecard$/.test(r.url())) { seen.scorecardCalls++; if (r.headers()['if-none-match']) seen.withEtag++; }
  });
  laptop.on('response', (r) => {
    if (/\/api\//.test(r.url())) seen.statuses.push([r.url().replace(BASE, ''), r.status()]);
  });

  await step(laptop, 'laptop (signed out): a match that has not started says so, and the rest of the match stays private', async () => {
    await laptop.goto(`${BASE}/m/${matchId}`);
    await laptop.getByTestId('public-not-found').waitFor();
    const mustBe = async (path, status) => {
      const code = await laptop.evaluate(async (p) => (await fetch(p)).status, path);
      if (code !== status) throw new Error(`${path}: expected ${status}, got ${code}`);
    };
    await mustBe(`/api/v1/matches/${matchId}/scorecard`, 404);
    await mustBe(`/api/v1/matches/${matchId}`, 401);
    await mustBe(`/api/v1/matches/${matchId}/scoring`, 401);
    await mustBe('/api/v1/matches', 401);
    await mustBe('/api/v1/matches?city=x', 401);
    await mustBe('/api/v1/matches?status=LIVE', 401);
    await mustBe('/api/v1/public/matches/live', 200);
    if (await laptop.evaluate(() => localStorage.getItem('accessToken'))) throw new Error('the laptop is not signed out');
    await laptop.screenshot({ path: `${shots}/cricket-1-not-started.png` });
    // The deliberate probes above answered 401 and 404; what is counted from here on is only what the page itself asks for.
    seen.statuses.length = 0;
    seen.withAuthorization = 0;
  });

  await step(phone, 'phone: signs in and starts the innings; the laptop picks the match up by itself', async () => {
    await phone.goto(`${BASE}/login`);
    await phone.getByRole('heading', { name: 'Sign In' }).waitFor();
    for (const [label, value] of [[/^Email/, email], [/^Password/, PASSWORD]]) {
      const f = phone.getByLabel(label);
      for (let i = 0; i < 5; i++) { await f.fill(value); await phone.waitForTimeout(120); if ((await f.inputValue()) === value) break; }
    }
    await phone.getByRole('button', { name: 'Sign In' }).click();
    await phone.waitForURL((u) => !u.pathname.startsWith('/login'));
    await phone.goto(`${BASE}/matches/${matchId}/score`);
    await phone.getByTestId('start-innings-button').tap();
    await phone.getByTestId('score').filter({ hasText: 'Lions 0/0' }).waitFor();
    const since = Date.now();
    const took = await laptopShows(laptop, 'public-score', 'Lions 0/0', since, 30000);
    log('     the laptop showed Lions 0/0', took, 'ms after the phone started the innings (a not-started page checks every 10 s)');
    await laptop.getByTestId('public-status').filter({ hasText: 'Live' }).waitFor();
    await laptop.screenshot({ path: `${shots}/cricket-2-started.png` });
  });

  const tapRun = async (n, expectedPhone, expectedLaptop, label) => {
    await phone.getByTestId(`run-${n}`).tap();
    await phone.getByTestId('score').filter({ hasText: expectedPhone }).waitFor();
    const took = await laptopShows(laptop, 'public-score', expectedLaptop, Date.now());
    log(`     ${label}: phone ${expectedPhone}, laptop ${expectedLaptop} after ${took} ms`);
  };

  await step(phone, 'phone taps 1, 4, 0: the laptop follows each ball', async () => {
    await tapRun(1, 'Lions 1/0', 'Lions 1/0', 'one run');
    await tapRun(4, 'Lions 5/0', 'Lions 5/0', 'a four');
    await tapRun(0, 'Lions 5/0', 'Lions 5/0', 'a dot');
    await laptopShows(laptop, 'public-overs', '0.3 / 2 ov', Date.now());
    await laptopShows(laptop, 'public-run-rate', 'Run rate 10.00', Date.now());
  });

  await step(phone, 'phone: a wicket (bowled); the laptop shows 5/1 and how the batter was out, before the next batter is chosen', async () => {
    await phone.getByTestId('btn-wicket').tap();
    await phone.getByTestId('how-BOWLED').tap();
    await phone.getByTestId('confirm-wicket').tap();
    await phone.getByTestId('chooser').waitFor();
    const took = await laptopShows(laptop, 'public-score', 'Lions 5/1', Date.now());
    log('     the laptop showed Lions 5/1 after', took, 'ms');
    await laptop.getByTestId('public-batter').filter({ hasText: 'Bala' }).filter({ hasText: 'b Tarun' }).first().waitFor();
    await laptop.getByTestId('public-fall').filter({ hasText: '1-5 (Bala' }).first().waitFor();
    await phone.getByTestId('choice').filter({ hasText: 'Chetan' }).tap();
    await phone.getByTestId('chooser').waitFor({ state: 'detached' });
    await laptop.getByTestId('public-batter').filter({ hasText: 'Chetan' }).first().waitFor();
    await laptop.screenshot({ path: `${shots}/cricket-3-wicket.png`, fullPage: true });
  });

  await step(phone, 'phone: a ball tapped by mistake shows on the laptop, and the undo takes it off again', async () => {
    await tapRun(3, 'Lions 8/1', 'Lions 8/1', 'a mistaken 3');
    await phone.getByTestId('undo').filter({ hasText: 'Undo: 3 runs' }).tap();
    await phone.getByTestId('score').filter({ hasText: 'Lions 5/1' }).waitFor();
    const took = await laptopShows(laptop, 'public-score', 'Lions 5/1', Date.now());
    log('     the laptop was back to Lions 5/1 after', took, 'ms');
    const cells = await laptop.getByTestId('public-batter').filter({ hasText: 'Chetan' }).first().locator('td').allTextContents();
    if (cells[1] !== '0' || cells[2] !== '0') throw new Error(`Chetan's line still shows the undone ball: ${JSON.stringify(cells)}`);
  });

  await step(phone, 'phone goes offline (the airplane-mode stand-in) and taps a ball: it is saved, the laptop does not change, then it arrives once', async () => {
    await phoneSession.context.setOffline(true);
    await phone.getByTestId('run-1').tap();
    await phone.getByTestId('outbox-banner').filter({ hasText: 'Saved on this phone' }).waitFor();
    await phone.screenshot({ path: `${shots}/cricket-4-offline-phone.png` });
    await laptop.waitForTimeout(9000);
    const during = await laptop.getByTestId('public-score').textContent();
    if (!during.includes('Lions 5/1')) throw new Error(`the laptop changed while the phone was offline: ${during}`);
    await phoneSession.context.setOffline(false);
    const since = Date.now();
    await phone.getByTestId('outbox-banner').waitFor({ state: 'detached', timeout: 30000 });
    const took = await laptopShows(laptop, 'public-score', 'Lions 6/1', since);
    log('     the saved ball reached the phone screen and the laptop (Lions 6/1) within', took, 'ms of the network coming back');
    const events = Number(sql(`SELECT count(*) FROM ball_events WHERE match_id = '${matchId}'`));
    // START_INNINGS, 1, 4, dot, wicket, SELECT_BATTER, 3, UNDO, 1 = 9
    if (events !== 9) throw new Error(`expected 9 log rows (the offline ball once), found ${events}`);
  });

  await step(laptop, 'laptop: the polling used the ETag (304s), sent no credentials and had no errors', async () => {
    const answers = seen.statuses.filter(([url]) => /\/scorecard$/.test(url));
    const notModified = answers.filter(([, s]) => s === 304).length;
    const ok = answers.filter(([, s]) => s === 200).length;
    log(`     ${seen.scorecardCalls} scorecard requests: ${ok} full answers, ${notModified} 304 (nothing new), ${seen.withEtag} sent If-None-Match`);
    if (notModified < 1) throw new Error('no 304 was seen: the ETag is not being used');
    if (seen.withEtag < 1) throw new Error('no request carried If-None-Match');
    if (seen.withAuthorization) throw new Error(`${seen.withAuthorization} laptop requests carried an Authorization header`);
    const bad = seen.statuses.filter(([url, s]) => (s === 401 || s >= 500) || (s === 404 && !/\/scorecard$/.test(url)) || (s === 403));
    if (bad.length) throw new Error(`unexpected answers on the laptop: ${JSON.stringify(bad)}`);
  });

  await step(laptop, 'laptop: the live list shows the match and filters by city in any letter case', async () => {
    await laptop.goto(`${BASE}/live`);
    await laptop.getByLabel('City').fill(city.toLowerCase());
    const card = laptop.getByTestId('live-match').filter({ hasText: 'Lions vs Tigers' });
    await card.waitFor();
    await card.getByTestId('live-score').filter({ hasText: 'Lions 6/1' }).waitFor();
    await laptop.getByLabel('City').fill(`Nowhere ${run}`);
    await laptop.getByText(`No live matches in Nowhere ${run}`).waitFor();
    await laptop.getByLabel('City').fill(city);
    await card.waitFor();
    await card.click();
    await laptop.getByTestId('public-score').filter({ hasText: 'Lions 6/1' }).waitFor();
    await laptop.screenshot({ path: `${shots}/cricket-5-live-list-and-card.png`, fullPage: true });
  });

  await step(phone, 'phone: the share buttons give the public link, and the public page fits the phone', async () => {
    await phone.getByTestId('copy-link').first().tap();
    await phone.locator('[data-testid=copied], [data-testid=manual-link]').first().waitFor();
    const link = `${BASE}/m/${matchId}`;
    const whatsapp = await phone.getByTestId('whatsapp-share').first().getAttribute('href');
    if (!whatsapp.includes(encodeURIComponent(link))) throw new Error(`WhatsApp link does not carry ${link}: ${whatsapp}`);
    const secure = await phone.evaluate(() => window.isSecureContext);
    log('     copy link:', (await phone.getByTestId('copied').count()) ? 'copied' : 'shown to copy by hand', `(secure context: ${secure})`);
    const page = await phoneSession.open();
    await page.goto(link);
    await page.getByTestId('public-score').filter({ hasText: 'Lions 6/1' }).waitFor();
    if (!(await noHorizontalScroll(page))) throw new Error('the public scorecard scrolls sideways on the phone');
    await page.screenshot({ path: `${shots}/cricket-6-public-on-phone.png`, fullPage: true });
    await page.close();
  });

  for (const s of [phoneSession, laptopSession]) {
    for (const p of s.problems) { failed = true; log('PROBLEM', p); }
  }
} finally {
  try {
    if (created.match) {
      assertLocalTestDatabase(created.match);
      const list = (ids) => ids.map((i) => `'${i}'`).join(',');
      const owner = sql(`SELECT id FROM customers WHERE email = '${email}'`);
      const statements = [
        `DELETE FROM ball_events WHERE match_id = '${created.match}'`, `DELETE FROM match_snapshots WHERE match_id = '${created.match}'`,
        `DELETE FROM match_players WHERE match_id = '${created.match}'`, `DELETE FROM match_scorers WHERE match_id = '${created.match}'`, `DELETE FROM matches WHERE id = '${created.match}'`,
        ...(created.teams.length ? [`DELETE FROM team_members WHERE team_id IN (${list(created.teams)})`, `DELETE FROM teams WHERE id IN (${list(created.teams)})`] : []),
        ...(created.players.length ? [`DELETE FROM players WHERE id IN (${list(created.players)})`] : []),
        ...(owner ? [`DELETE FROM notifications WHERE user_id = '${owner}'`, `DELETE FROM refresh_tokens WHERE user_id = '${owner}'`, `DELETE FROM customer_roles WHERE customer_id = '${owner}'`,
          `DELETE FROM customer_auth_providers WHERE customer_id = '${owner}'`, `DELETE FROM customer_addresses WHERE customer_id = '${owner}'`, `DELETE FROM customers WHERE id = '${owner}'`] : []),
      ];
      sql(`SET session_replication_role = replica; ${statements.join('; ')}`);
      log('cleaned up test data (the append-only guard was bypassed with session_replication_role, on the local test database only)');
    }
  } catch (e) { log('CLEANUP PROBLEM', e.message.split('\n')[0]); failed = true; }
  await browser.close();
}
log(failed ? 'FAILED (emulated run)' : 'ALL STEPS PASSED (emulated run, not a real phone)');
process.exit(failed ? 1 : 0);
