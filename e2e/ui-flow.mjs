// Real browser journey through the DSports UI. Usage: node ui-flow.mjs <scenario> <email> <productId>
// scenario: happy | capture-failure | capture-timeout
import { chromium } from 'playwright';

const [scenario = 'happy', email, productId] = process.argv.slice(2);
const BASE = 'http://localhost:3000';
const PASSWORD = 'Passw0rd!x';

const problems = [];
let orderId = null;
const log = (...a) => console.log(`[${scenario}]`, ...a);

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
page.on('response', (r) => { if (r.status() >= 500) problems.push(`HTTP ${r.status()} ${r.request().method()} ${r.url()}`); });

/** Fills a field and re-fills until React has settled and the value sticks. */
async function fillStable(locator, value) {
  for (let i = 0; i < 5; i++) {
    await locator.fill(value);
    await page.waitForTimeout(150);
    if ((await locator.inputValue()) === value) return;
  }
  throw new Error('field did not keep its value');
}

async function step(name, fn) {
  try {
    await fn();
    log('OK  ', name);
  } catch (e) {
    log('FAIL', name, '-', e.message.split('\n')[0]);
    await page.screenshot({ path: `fail-${scenario}-${name.replace(/\W+/g, '_')}.png`, fullPage: true });
    throw e;
  }
}

try {
  await step('register', async () => {
    await page.goto(`${BASE}/register`);
    await page.getByLabel(/^First Name/).fill('Ui');
    await page.getByLabel(/^Last Name/).fill('Tester');
    await page.getByLabel(/^Email/).fill(email);
    await page.getByLabel(/^Password/).fill(PASSWORD);
    await page.getByLabel(/^Confirm Password/).fill(PASSWORD);
    await page.getByRole('button', { name: 'Create Account' }).click();
    await page.waitForURL('**/login');
  });

  await step('login', async () => {
    await page.getByRole('heading', { name: 'Sign In' }).waitFor();
    await fillStable(page.getByLabel(/^Email/), email);
    await fillStable(page.getByLabel(/^Password/), PASSWORD);
    await page.getByRole('button', { name: 'Sign In' }).click();
    await page.waitForURL((u) => !u.pathname.startsWith('/login'));
  });

  await step('add product to cart', async () => {
    await page.goto(`${BASE}/products/${productId}`);
    await page.getByRole('button', { name: 'Add to Cart' }).first().click();
    await page.getByText(/added to cart/).waitFor();
  });

  await step('checkout: add and select address', async () => {
    await page.goto(`${BASE}/checkout`);
    await page.getByRole('button', { name: 'Add New' }).click();
    await page.getByLabel(/^Address Line 1/).fill('42 Stadium Road');
    await page.getByLabel(/^City/).fill('Bengaluru');
    await page.getByLabel(/^State/).fill('KA');
    await page.getByLabel(/^Postal Code/).fill('560001');
    // Country is a list of supported countries (a free-text value used to reach the backend and fail with a 500)
    await page.getByRole('combobox', { name: /^Country/ }).click();
    await page.getByRole('option', { name: /^India/ }).click();
    await page.getByRole('button', { name: 'Save Address' }).click();
    await page.getByText('42 Stadium Road').waitFor();
  });

  await step('checkout: validate and place order', async () => {
    await page.getByRole('button', { name: 'Validate Checkout' }).click();
    await page.getByRole('button', { name: 'Continue to Payment' }).waitFor();
    await page.getByRole('button', { name: 'Continue to Payment' }).click();
    await page.waitForURL('**/payment?orderId=*');
    orderId = new URL(page.url()).searchParams.get('orderId');
    log('order', orderId);
  });

  await step('payment: summary and method', async () => {
    // exact: a substring match also resolves the checkout page's "Continue to Payment" button while the route is still changing, and that click never reaches the payment page
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await page.getByLabel('UPI').check();
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
  });

  if (scenario === 'happy') {
    await step('pay: authorize + capture -> success page with CONFIRMED order', async () => {
      await page.getByRole('button', { name: /^Pay INR/ }).click();
      await page.waitForURL('**/payment-success?paymentId=*', { timeout: 30000 });
      await page.getByText('Payment Successful!').waitFor();
      await page.getByTestId('order-status').filter({ hasText: 'CONFIRMED' }).waitFor();
    });

    await step('fake success URL without a captured payment is not shown as success', async () => {
      await page.goto(`${BASE}/payment-success?paymentId=00000000-0000-0000-0000-000000000000`);
      await page.getByText(/not found|could not load/i).waitFor();
    });

    await step('order detail: request refund', async () => {
      await page.goto(`${BASE}/orders/${orderId}`);
      await page.getByRole('button', { name: 'Request refund' }).click();
      await page.getByLabel('Reason').fill('Changed my mind');
      await page.getByRole('button', { name: 'Submit request' }).click();
      await page.getByText('REQUESTED').waitFor();
      await page.getByText('Your request is being reviewed.').waitFor();
    });
  }

  if (scenario === 'capture-failure') {
    await step('pay: declined capture -> failure page', async () => {
      await page.getByRole('button', { name: /^Pay INR/ }).click();
      await page.waitForURL('**/payment-failure?*', { timeout: 30000 });
    });
    await step('cancel unpaid order from order page', async () => {
      await page.goto(`${BASE}/orders/${orderId}`);
      await page.getByRole('button', { name: 'Pay now' }).waitFor();
      await page.getByRole('button', { name: 'Cancel Order' }).first().click();
      await page.getByRole('dialog').getByRole('button', { name: 'Cancel Order' }).click();
      await page.getByText(/has been cancelled/).waitFor();
    });
  }

  if (scenario === 'capture-timeout') {
    await step('pay: capture times out -> retry offered, no false success', async () => {
      await page.getByRole('button', { name: /^Pay INR/ }).click();
      await page.getByRole('button', { name: 'Retry confirmation' }).waitFor({ timeout: 30000 });
      await page.getByText(/retrying is safe/).waitFor();
      if (page.url().includes('payment-success')) throw new Error('showed success on timeout');
    });
  }

  // 5xx responses are bugs; expected 4xx (e.g. 401 before login) are not counted.
  // AI_DOWN=1: the AI service is stopped on purpose, so the dev proxy answers its /ai-api calls with 5xx. Only those (and the same number of the browser's own
  // "Failed to load resource" lines) are expected; any other 5xx, page error or console error still fails.
  let pool = problems;
  if (process.env.AI_DOWN === '1') {
    const ai = problems.filter((p) => /^HTTP 5\d\d .*\/ai-api\//.test(p));
    let budget = ai.length;
    pool = problems.filter((p) => {
      if (ai.includes(p)) return false;
      if (budget > 0 && /Failed to load resource.*status of 5\d\d/.test(p)) { budget--; return false; }
      return true;
    });
    log(`AI service is down by design: ${ai.length} proxied /ai-api 5xx ignored, shop requests checked`);
  }
  const unexpected = pool.filter((p) => !/status of 4\d\d/.test(p));
  if (unexpected.length) {
    log('PROBLEMS:', JSON.stringify(unexpected, null, 1));
    process.exitCode = 2;
  } else {
    log('no console errors, page errors or 5xx responses');
  }
} catch {
  log('problems so far:', JSON.stringify(problems, null, 1));
  process.exitCode = 1;
} finally {
  await browser.close();
}
