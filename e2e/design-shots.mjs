// Visual inspection helper: signs in with the users from planning_data.py and screenshots the internal screens at a desktop and a phone viewport, reporting horizontal overflow and console problems.
// Usage: node e2e/design-shots.mjs <world.json> <outDir> [prefix] [tabsFile.json]
import { chromium } from 'playwright';
import fs from 'node:fs';

const [worldFile, outDir = 'scratch_shots', prefix = 'shot', listFile] = process.argv.slice(2);
const world = JSON.parse(fs.readFileSync(worldFile, 'utf8').replace(/^﻿/, ''));
const BASE = 'http://localhost:3000';
const DEFAULT = [
  ['/sales-planning?tab=dashboard', 'plan-dashboard'], ['/sales-planning?tab=workspace', 'plan-workspace'], ['/sales-planning?tab=performance', 'plan-performance'],
  ['/sales-planning?tab=analysis', 'plan-analysis'], ['/sales-planning?tab=inventory', 'plan-inventory'], ['/sales-planning?tab=evaluation', 'plan-evaluation'], ['/sales-planning?tab=readiness', 'plan-readiness'],
  ['/inventory-ai/overview', 'inv-overview'], ['/inventory-ai/risk', 'inv-risk'], ['/inventory-ai/replenishment', 'inv-replenishment'],
  ['/product-intelligence?tab=dashboard', 'src-dashboard'], ['/product-intelligence?tab=opportunities', 'src-opportunities'], ['/product-intelligence?tab=suppliers', 'src-suppliers'], ['/product-intelligence?tab=runs', 'src-runs'],
];
const list = listFile ? JSON.parse(fs.readFileSync(listFile, 'utf8')) : DEFAULT;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const problems = [];
for (const [vw, vh, tag] of [[1440, 900, 'd'], [390, 844, 'm']]) {
  const context = await browser.newContext({ viewport: { width: vw, height: vh } });
  const page = await context.newPage();
  page.on('console', (m) => { if (m.type() === 'error' && !/40[349]/.test(m.text())) problems.push(`${tag} console: ${m.text()}`); });
  page.on('pageerror', (e) => problems.push(`${tag} pageerror: ${e.message}`));
  page.on('response', (r) => { if (r.status() >= 500) problems.push(`${tag} HTTP ${r.status()} ${r.url()}`); });
  await page.goto(`${BASE}/login`);
  await page.getByRole('heading', { name: 'Sign In' }).waitFor();
  for (const [label, value] of [[/^Email/, world.admin], [/^Password/, world.password]]) {
    const f = page.getByLabel(label);
    for (let i = 0; i < 5; i++) { await f.fill(value); await page.waitForTimeout(100); if ((await f.inputValue()) === value) break; }
  }
  await page.getByRole('button', { name: 'Sign In' }).click();
  await page.waitForURL((u) => !u.pathname.startsWith('/login'));
  for (const [url, name] of list) {
    await page.goto(BASE + url);
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(1200);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    await page.screenshot({ path: `${outDir}/${prefix}-${name}-${tag}.png`, fullPage: true });
    console.log(`${tag} ${name} overflow=${overflow}px`);
    if (overflow > 2) problems.push(`${tag} ${name}: horizontal overflow ${overflow}px`);
  }
  await context.close();
}
await browser.close();
console.log(problems.length ? 'PROBLEMS\n' + problems.join('\n') : 'no console errors, page errors, 5xx or horizontal overflow');
process.exit(problems.length ? 1 : 0);
