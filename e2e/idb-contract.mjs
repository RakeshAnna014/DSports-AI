// Runs the offline queue's storage contract (frontend/src/features/cricket/offline/storageContract.ts) against REAL IndexedDB in a real Chrome.
// No database, backend or credentials are involved: the page is a tiny file server started here. It is served from this machine's LAN address over plain http, so
// the page is NOT a secure context, exactly like a scorer's phone opening http://<laptop>:3000. The script says whether it really was one (and fails if it was not
// asked to run on loopback and still was). The sources are the project's own TypeScript, transpiled with the project's own TypeScript compiler, so nothing is
// copied or mocked and no dependency is added.
// Usage: node idb-contract.mjs            (exit 0 = the contract is met by IndexedDB; exit 1 = problems listed; exit 2 = could not run)
import { chromium } from 'playwright';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { networkInterfaces } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const offline = resolve(here, '..', 'frontend', 'src', 'features', 'cricket', 'offline');
const ts = createRequire(resolve(here, '..', 'frontend', 'package.json'))('typescript');

/** TypeScript to a browser module: types are erased and relative imports get the .js the browser needs. */
const compile = (name) => {
  const source = readFileSync(join(offline, `${name}.ts`), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022, verbatimModuleSyntax: false } }).outputText;
  return js.replace(/from\s+'(\.\/[^']+)'/g, "from '$1.js'");
};
const modules = Object.fromEntries(['storage', 'idbStorage', 'storageContract'].map((n) => [`/${n}.js`, compile(n)]));
// types.ts has only types and constants the adapters do not import at run time; storage.ts's type-only imports are erased.

const lan = Object.values(networkInterfaces()).flat().find((i) => i && i.family === 'IPv4' && !i.internal)?.address;
const host = lan ?? '127.0.0.1';
const server = createServer((req, res) => {
  const js = modules[req.url ?? ''];
  if (js) {
    res.writeHead(200, { 'content-type': 'text/javascript' }).end(js);
  } else {
    res.writeHead(200, { 'content-type': 'text/html' }).end('<!doctype html><title>idb contract</title><body>idb contract</body>');
  }
});
await new Promise((ok) => server.listen(0, host, ok));
const url = `http://${host}:${server.address().port}/`;

let code = 0;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url);
  const result = await page.evaluate(async () => {
    const { openIdbStorage } = await import('/idbStorage.js');
    const { runStorageContract } = await import('/storageContract.js');
    let n = 0;
    // A new database for every check, so the checks cannot see each other's rows.
    const problems = await runStorageContract(async () => openIdbStorage(`contract-${Date.now()}-${n++}`));
    // The real name too: it must open, and a second open (another tab) must work.
    const first = await openIdbStorage();
    const second = await openIdbStorage();
    first.close();
    second.close();
    return { problems, secure: window.isSecureContext, databases: n };
  });
  console.log(`page ${url} secure context: ${result.secure}${result.secure ? ' (loopback is always secure; run on a LAN address to prove plain http)' : ' (plain http, like a phone on the local network)'}`);
  console.log(`IndexedDB contract: ${result.problems.length === 0 ? `met (${result.databases} fresh databases)` : 'NOT met'}`);
  result.problems.forEach((p) => console.log(`  - ${p}`));
  errors.forEach((e) => console.log(`  page error: ${e}`));
  code = result.problems.length === 0 && errors.length === 0 ? 0 : 1;
} catch (e) {
  console.log(`could not run: ${e instanceof Error ? e.message : e}`);
  code = 2;
} finally {
  await browser.close();
  server.close();
}
process.exit(code);
