// EMULATED offline scoring on the REAL stack (real Java backend + database, real Chrome). It is NOT a real phone:
//   * the "phone" is a Chrome context with a phone-sized screen and touch input; `context.setOffline(true)` stands in for airplane mode;
//   * the "laptop" is a second Chrome context, signed out, following the public scorecard;
//   * "another scorer" is the organiser scoring through the API with the same token.
// It proves, against the real server and the real database:
//   1. balls tapped with no signal are saved on the phone (IndexedDB) and the pad keeps working: wide, no-ball and its free hit, a wicket with a new batter, the end of an
//      over with a new bowler, a mistaken ball and its undo;
//   2. the laptop does not change while the phone is offline;
//   3. the tab is killed while balls wait; a new tab on the same phone finds them, sends them by itself, in order, once each, and the laptop follows;
//   4. the log in the database has exactly the expected number of events, each client event id once, and the phone and the laptop show the same score;
//   5. another scorer scoring while the phone is offline: a sheet lists their balls and the phone's, "Add mine after theirs" sends the phone's with their original ids;
//   6. the correction window closing before queued balls arrive: they are set aside (saved, not lost), explained, and go in with their original ids after an administrator unlocks.
// Like cricket-ui.mjs, the only SQL is the clean-up and the one-line "close the window" and "grant admin" steps, and the script REFUSES to run unless it is talking to the
// local test stack (see lib/localTestGuard.mjs). Use E2E_BASE=http://<laptop LAN address>:3000 (vite --host) to go through the network address over plain http.
// Usage: node cricket-offline.mjs [shotDir]
import { chromium, devices } from 'playwright';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { assertLocalTestDatabase as assertLocalTestDatabaseWith, TEST_CONTAINER, TEST_DATABASE } from './lib/localTestGuard.mjs';

const shots = process.argv[2] ?? '.';
const BASE = process.env.E2E_BASE ?? 'http://localhost:3000';
const API = process.env.E2E_API ?? 'http://127.0.0.1:8080';
const PASSWORD = 'Passw0rd!x';
const run = Math.random().toString(36).slice(2, 8);
let failed = false;
const log = (...a) => console.log('[cricket-offline]', ...a);

const DOCKER = process.env.DOCKER_BIN || (existsSync('C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe') ? 'C:/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe' : 'docker');
const sql = (q) => execFileSync(DOCKER, ['exec', TEST_CONTAINER, 'psql', '-U', 'dsports', '-d', TEST_DATABASE, '-tAc', q], { encoding: 'utf8' }).trim();
const assertLocalTestDatabase = (matchId) => assertLocalTestDatabaseWith(API, sql, matchId);

const email = `cof-${run}@example.com`;
const adminEmail = `cofa-${run}@example.com`;
const created = { match: null, teams: [], players: [], emails: [email, adminEmail] };

async function api(method, path, token, body) {
  const r = await fetch(`${API}${path}`, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  return { status: r.status, body: text ? JSON.parse(text) : null };
}
const must = (r, expected, what) => { if (r.status !== expected) throw new Error(`${what}: HTTP ${r.status} ${JSON.stringify(r.body)}`); return r.body; };

async function register(addr) {
  must(await api('POST', '/api/auth/register', null, { email: addr, password: PASSWORD, firstName: 'Offline', lastName: 'Tester' }), 201, `register ${addr}`);
  return must(await api('POST', '/api/auth/login', null, { email: addr, password: PASSWORD }), 200, `login ${addr}`).accessToken;
}

/** Lions v Tigers, 4 a side, 3 overs (room for a lot of balls), tennis ball, toss and XIs done, through the API. */
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
  const m = must(await api('POST', '/api/v1/matches', token, { teamAId: teamIds[0], teamBId: teamIds[1], overs: 3, ballType: 'TENNIS', city: `Offlinecity ${run}`, rules: { playersPerSide: 4 } }), 201, 'match');
  created.match = m.id;
  must(await api('POST', `/api/v1/matches/${m.id}/toss`, token, { winnerTeamId: teamIds[0], decision: 'BAT' }), 200, 'toss');
  for (let i = 0; i < 2; i++) must(await api('PUT', `/api/v1/matches/${m.id}/playing-xi`, token, { teamId: teamIds[i], playerIds: playerIds[i] }), 200, `xi ${i}`);
  return { matchId: m.id, playerIds };
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
      page.on('console', (m) => { if (m.type() === 'error' && !/Failed to load resource|net::ERR_INTERNET_DISCONNECTED/.test(m.text())) problems.push(`${label} console: ${m.text()}`); });
      page.on('pageerror', (e) => problems.push(`${label} pageerror: ${e.message}`));
      return page;
    } };
  });
}
async function step(page, name, fn) {
  try { await fn(); log('OK  ', name); } catch (e) {
    failed = true; log('FAIL', name, '-', e.message.split('\n')[0]);
    try { await page.screenshot({ path: `${shots}/fail-offline-${name.replace(/\W+/g, '_').slice(0, 60)}.png`, fullPage: true }); } catch { /* the page may be gone */ }
  }
}

let phoneSession;
let laptopSession;
let token;
let matchId;
let playerIds;
const events = () => Number(sql(`SELECT count(*) FROM ball_events WHERE match_id = '${matchId}'`));
const distinctIds = () => Number(sql(`SELECT count(DISTINCT client_event_id) FROM ball_events WHERE match_id = '${matchId}'`));
const maxSeq = () => Number(sql(`SELECT coalesce(max(seq), 0) FROM ball_events WHERE match_id = '${matchId}'`));
const text = async (page, id) => (await page.getByTestId(id).first().textContent())?.trim() ?? '';

/** What the phone has tapped so far (every tap and every undo is one event in the log): the number the database must reach. */
let tapped = 0;
/** A press that opens a sheet and creates no event. */
async function press(phone, testId) { await phone.getByTestId(testId).tap(); await phone.waitForTimeout(150); }
/** A tap that makes exactly one event in the log (a ball, an undo, a choice). */
async function tap(phone, testId) { await phone.getByTestId(testId).tap(); tapped++; await phone.waitForTimeout(150); }
/** Picks a player in the chooser (one event). The next chooser may replace this one at once (a new batter, then a new bowler), so this only waits for the tap to land. */
async function pick(phone, name) { await phone.getByTestId('choice').filter({ hasText: name }).tap(); tapped++; await phone.waitForTimeout(500); }

try {
  token = await register(email);
  ({ matchId, playerIds } = await setupMatch(token));
  log('match', matchId, 'via', BASE);

  phoneSession = await session('phone', { ...devices['Pixel 7'], viewport: { width: 390, height: 844 } });
  laptopSession = await session('laptop', { viewport: { width: 1360, height: 900 } });
  let phone = await phoneSession.open();
  const laptop = await laptopSession.open();

  await step(phone, 'phone (online): signs in and starts the innings; the laptop follows', async () => {
    await phone.goto(`${BASE}/login`);
    await phone.getByRole('heading', { name: 'Sign In' }).waitFor();
    for (const [label, value] of [[/^Email/, email], [/^Password/, PASSWORD]]) {
      const f = phone.getByLabel(label);
      for (let i = 0; i < 5; i++) { await f.fill(value); await phone.waitForTimeout(120); if ((await f.inputValue()) === value) break; }
    }
    await phone.getByRole('button', { name: 'Sign In' }).click();
    await phone.waitForURL((u) => !u.pathname.startsWith('/login'));
    await phone.goto(`${BASE}/matches/${matchId}/score`);
    await phone.getByTestId('keep-open-hint').waitFor();
    await phone.getByTestId('start-innings-button').tap();
    tapped++;
    await phone.getByTestId('score').filter({ hasText: 'Lions 0/0' }).waitFor();
    await phone.getByTestId('waiting-banner').waitFor({ state: 'detached', timeout: 30000 });
    await laptop.goto(`${BASE}/m/${matchId}`);
    await laptop.getByTestId('public-score').filter({ hasText: 'Lions 0/0' }).waitFor({ timeout: 30000 });
    if (events() !== tapped) throw new Error(`expected ${tapped} event after the start, found ${events()}`);
  });

  let phoneScoreOffline = '';
  await step(phone, 'phone goes offline and scores a wide, a no-ball and its free hit, a wicket, a new batter, the end of an over and a new bowler, and a mistaken ball with its undo', async () => {
    await phoneSession.context.setOffline(true);
    await phone.waitForTimeout(500);
    await phone.getByTestId('chip-offline').waitFor();
    await tap(phone, 'run-1');
    await tap(phone, 'run-4');
    await press(phone, 'btn-wide'); await tap(phone, 'wide-0');
    await press(phone, 'btn-noball'); await tap(phone, 'nb-bat-0');
    await phone.getByTestId('free-hit').waitFor();
    await tap(phone, 'run-2');
    if (await phone.getByTestId('free-hit').count()) throw new Error('the free hit should be over after one legal ball');
    await tap(phone, 'run-1');
    await tap(phone, 'run-0');
    // The wicket: the ball is the sixth legal one, so a new batter AND a new bowler are asked for.
    await press(phone, 'btn-wicket'); await phone.getByTestId('how-BOWLED').tap(); await tap(phone, 'confirm-wicket');
    await phone.getByTestId('chooser').waitFor();
    await pick(phone, 'Chetan');
    await phone.getByTestId('chooser').filter({ hasText: 'Who bowls' }).waitFor();
    await pick(phone, 'Uday');
    await phone.getByTestId('chooser').waitFor({ state: 'detached' });
    await tap(phone, 'run-3');
    await phone.getByTestId('undo').filter({ hasText: 'Undo: 3 runs' }).tap(); tapped++;
    await phone.waitForTimeout(300);
    await tap(phone, 'run-1');
    phoneScoreOffline = await text(phone, 'score');
    const unsent = await text(phone, 'chip-unsent');
    log('     offline:', phoneScoreOffline, `(${unsent}); events tapped since the start: ${tapped - 1}`);
    if (!/unsent/.test(unsent)) throw new Error(`the unsent chip says "${unsent}"`);
    await phone.screenshot({ path: `${shots}/offline-1-phone-waiting.png` });
  });

  await step(laptop, 'laptop: does not change while the phone is offline, and the database still holds only the first event', async () => {
    await laptop.waitForTimeout(9000);
    const during = await text(laptop, 'public-score');
    if (!during.includes('Lions 0/0')) throw new Error(`the laptop changed while the phone was offline: ${during}`);
    if (events() !== 1) throw new Error(`the database should hold 1 event, found ${events()}`);
  });

  await step(phone, 'the tab is killed with balls waiting; a new tab on the same phone finds them, sends them by itself, once each, in order', async () => {
    const waitingBefore = Number((await text(phone, 'chip-unsent')).match(/\d+/)?.[0]);
    await phone.close();
    await phoneSession.context.setOffline(false);
    phone = await phoneSession.open();
    await phone.goto(`${BASE}/matches/${matchId}/score`);
    // The chip may already be gone by the time the page has rendered, so the proof is the database and the laptop, not the chip.
    const since = Date.now();
    await laptop.getByTestId('public-score').filter({ hasText: phoneScoreOffline.replace('Lions ', 'Lions ') }).waitFor({ timeout: 120000 });
    log(`     the laptop showed ${phoneScoreOffline} ${Date.now() - since} ms after the new tab opened (${waitingBefore} were waiting)`);
    await phone.getByTestId('waiting-banner').waitFor({ state: 'detached', timeout: 60000 });
    const n = events();
    if (n !== tapped) throw new Error(`expected ${tapped} events, found ${n}`);
    if (distinctIds() !== n) throw new Error(`a client event id was written twice: ${distinctIds()} distinct of ${n}`);
    if (maxSeq() !== n) throw new Error(`the seqs have a gap: max ${maxSeq()} of ${n}`);
    const shown = await text(phone, 'score');
    if (shown !== phoneScoreOffline) throw new Error(`the phone shows ${shown}, it showed ${phoneScoreOffline} offline`);
    await laptop.screenshot({ path: `${shots}/offline-2-laptop-after-flush.png` });
  });

  await step(phone, 'another scorer scores two balls while the phone is offline: the sheet lists both sets, and "add mine after theirs" sends the phone\'s with their original ids', async () => {
    await phoneSession.context.setOffline(true);
    await phone.waitForTimeout(500);
    await tap(phone, 'run-1'); await tap(phone, 'run-0');
    const view = must(await api('GET', `/api/v1/matches/${matchId}/scoring`, token), 200, 'scoring view');
    // The other scorer: two wides, which change neither the strike nor the over, so the phone's own balls still apply after them.
    for (let i = 0; i < 2; i++) {
      const seq = maxSeq();
      must(await api('POST', `/api/v1/matches/${matchId}/scoring/commands`, token, { clientEventId: crypto.randomUUID(), expectedSeq: seq, command: { type: 'DELIVER', delivery: { kind: 'WIDE', runs: 0, boundary: false } } }), 200, `foreign wide ${i + 1}`);
    }
    if (view.needs) throw new Error('the match unexpectedly needs a choice');
    await phoneSession.context.setOffline(false);
    await phone.getByTestId('conflict-sheet').waitFor({ timeout: 120000 });
    const theirs = await phone.getByTestId('conflict-event').count();
    const mine = await phone.getByTestId('conflict-mine').count();
    if (theirs !== 2 || mine !== 2) throw new Error(`the sheet lists ${theirs} of theirs and ${mine} of mine, expected 2 and 2`);
    await phone.screenshot({ path: `${shots}/offline-3-conflict-sheet.png` });
    await phone.getByTestId('conflict-rebase').tap();
    await phone.getByTestId('conflict-sheet').waitFor({ state: 'detached' });
    await phone.getByTestId('waiting-banner').waitFor({ state: 'detached', timeout: 60000 });
    const n = events();
    tapped += 2;   // the other scorer's two are in the log too
    if (n !== tapped) throw new Error(`expected ${tapped} events, found ${n}`);
    if (distinctIds() !== n || maxSeq() !== n) throw new Error('a gap or a duplicate in the log after the rebase');
    await laptop.getByTestId('public-score').filter({ hasText: await text(phone, 'score') }).waitFor({ timeout: 60000 });
  });

  await step(phone, 'the correction window closes while undos are queued: they are set aside and explained, and go in with their original ids after an administrator unlocks', async () => {
    // Finish the match by calling it off, so that a correction window exists, then queue an undo offline and close the window before it arrives.
    await phone.getByTestId('more').tap();
    await phone.getByTestId('more-end').tap();
    await phone.getByTestId('confirm-abandon').tap();
    tapped++;
    await phone.getByTestId('result-text').waitFor({ timeout: 60000 });
    await phone.getByTestId('waiting-banner').waitFor({ state: 'detached', timeout: 60000 });
    await phoneSession.context.setOffline(true);
    await phone.waitForTimeout(500);
    await phone.getByTestId('undo').tap();   // taking the abandonment back: it reopens a finished match, so the screen asks first
    await phone.getByTestId('confirm-undo').tap();
    tapped++;
    await phone.getByTestId('waiting-banner').waitFor();
    const idsBefore = sql(`SELECT count(*) FROM ball_events WHERE match_id = '${matchId}'`);
    sql(`UPDATE match_snapshots SET first_finished_at = now() - interval '25 hours' WHERE match_id = '${matchId}'`);
    await phoneSession.context.setOffline(false);
    await phone.getByTestId('chip-set-aside').waitFor({ timeout: 120000 });
    await phone.getByTestId('locked-banner').waitFor();
    if (events() !== Number(idsBefore)) throw new Error('the undo should not have been written: the window is closed');
    await phone.getByTestId('chip-set-aside').tap();
    await phone.getByTestId('set-aside-sheet').filter({ hasText: 'not in the match' }).waitFor();
    await phone.getByTestId('set-aside-item').filter({ hasText: 'correction window' }).waitFor();
    await phone.screenshot({ path: `${shots}/offline-4-set-aside.png` });
    // An administrator unlocks (granted by SQL on this local test database, as cricket-ui does for its clean-up).
    const adminToken = await register(adminEmail);
    const adminId = sql(`SELECT id FROM customers WHERE email = '${adminEmail}'`);
    sql(`INSERT INTO customer_roles (customer_id, role) VALUES ('${adminId}', 'ADMIN')`);
    const admin = must(await api('POST', '/api/auth/login', null, { email: adminEmail, password: PASSWORD }), 200, 'admin login').accessToken;
    must(await api('POST', `/api/v1/admin/matches/${matchId}/scoring/unlock`, admin, { reason: 'the scorer phone had no signal at the ground' }), 200, 'unlock');
    void adminToken;
    await phone.getByTestId('set-aside-send-again').tap();
    await phone.getByTestId('chip-set-aside').waitFor({ state: 'detached', timeout: 60000 });
    await phone.getByTestId('waiting-banner').waitFor({ state: 'detached', timeout: 60000 });   // the set-aside ball is now waiting, then sent
    const n = events();
    if (n !== tapped + 1) throw new Error(`expected ${tapped + 1} events (the unlock is one), found ${n}`);
    if (distinctIds() !== n) throw new Error('a client event id was written twice');
    await phone.screenshot({ path: `${shots}/offline-5-after-unlock.png` });
  });

  for (const s of [phoneSession, laptopSession]) {
    for (const p of s.problems) { failed = true; log('PROBLEM', p); }
  }
} finally {
  try {
    if (created.match) {
      assertLocalTestDatabase(created.match);
      const list = (ids) => ids.map((i) => `'${i}'`).join(',');
      const owners = created.emails.map((e) => sql(`SELECT id FROM customers WHERE email = '${e}'`)).filter(Boolean);
      const statements = [
        `DELETE FROM ball_events WHERE match_id = '${created.match}'`, `DELETE FROM match_snapshots WHERE match_id = '${created.match}'`,
        `DELETE FROM match_players WHERE match_id = '${created.match}'`, `DELETE FROM match_scorers WHERE match_id = '${created.match}'`, `DELETE FROM matches WHERE id = '${created.match}'`,
        ...(created.teams.length ? [`DELETE FROM team_members WHERE team_id IN (${list(created.teams)})`, `DELETE FROM teams WHERE id IN (${list(created.teams)})`] : []),
        ...(created.players.length ? [`DELETE FROM players WHERE id IN (${list(created.players)})`] : []),
        ...owners.flatMap((owner) => [`DELETE FROM notifications WHERE user_id = '${owner}'`, `DELETE FROM refresh_tokens WHERE user_id = '${owner}'`, `DELETE FROM customer_roles WHERE customer_id = '${owner}'`,
          `DELETE FROM role_audit_log WHERE actor_id = '${owner}' OR target_id = '${owner}'`,
          `DELETE FROM customer_auth_providers WHERE customer_id = '${owner}'`, `DELETE FROM customer_addresses WHERE customer_id = '${owner}'`, `DELETE FROM customers WHERE id = '${owner}'`]),
      ];
      sql(`SET session_replication_role = replica; ${statements.join('; ')}`);
      log('cleaned up test data (the append-only guard was bypassed with session_replication_role, on the local test database only)');
    }
  } catch (e) { log('CLEANUP PROBLEM', e.message.split('\n')[0]); failed = true; }
  await browser.close();
}
log(failed ? 'FAILED (emulated run)' : 'ALL STEPS PASSED (emulated run, not a real phone)');
process.exit(failed ? 1 : 0);
