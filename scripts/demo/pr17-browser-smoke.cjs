/* Actual Chromium -> HTTP API -> disposable PostgreSQL smoke. No .env or live providers. */
const assert = require('node:assert/strict');
const { spawn, execFileSync } = require('node:child_process');
const { createHash, randomBytes, randomUUID } = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const root = path.resolve(__dirname, '../..');
const output = path.resolve(process.env.PR17_SMOKE_OUTPUT || path.join(root, 'docs/evidence/pr17/browser'));
const jar = path.resolve(process.env.PR17_APP_JAR || path.join(root, 'target/payments-ledger-platform-0.1.0-SNAPSHOT.jar'));
const id = n => `10000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const checks = [];
const passed = name => checks.push({ name, status: 'PASS' });
const container = 'pr17-browser-' + randomUUID();
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pr17-browser-'));
const appSecret = randomBytes(32).toString('base64url'), opsSecret = randomBytes(32).toString('base64url');
const appToken = `app_${id(41)}.${appSecret}`;
const opsToken = n => `ops_${id(n)}.${opsSecret}`;
let server, browser, started = false;
const sql = text => execFileSync('docker', ['exec', '-i', container, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'pr17', '-At'], { input: text, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim();

async function main() {
  assert(fs.existsSync(jar), 'Build the application jar before browser smoke');
  fs.mkdirSync(output, { recursive: true });
  fs.rmSync(path.join(output, 'result.json'), { force: true });
  docker('run', '--detach', '--name', container, '-e', 'POSTGRES_PASSWORD=pr17_disposable_only', '-e', 'POSTGRES_DB=pr17', '-p', '127.0.0.1::5432', 'postgres:16');
  started = true;
  for (let i = 0; ; i++) {
    // PostgreSQL's initializer starts a socket-only temporary server before creating POSTGRES_DB.
    // The permanent TCP listener is ready only after initialization has finished.
    try { docker('exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', 'pr17'); break; }
    catch { assert(i < 60, 'Disposable PostgreSQL did not start'); await pause(500); }
  }
  sql(fs.readFileSync(path.join(root, 'src/test/resources/db/pr02-runtime-role.sql'), 'utf8'));
  const dbPort = docker('port', container, '5432/tcp').split(':').at(-1);
  const logFile = path.join(temporary, 'api.log'), log = fs.openSync(logFile, 'w');
  // Construct a fresh environment. Existing provider credentials and project .env are never loaded.
  const env = {
    PATH: process.env.PATH, TMPDIR: temporary, LANG: 'en_US.UTF-8',
    DB_HOST: '127.0.0.1', DB_PORT: dbPort, DB_NAME: 'pr17', DB_APP_USER: 'payments_app', DB_APP_PASSWORD: 'pr02_test_only',
    DB_MIGRATION_USER: 'postgres', DB_MIGRATION_PASSWORD: 'pr17_disposable_only',
    LOCAL_FIXTURES_ENABLED: 'true', LOCAL_APP_SECRET: appSecret, LOCAL_OPS_SECRET: opsSecret,
    STRIPE_ENABLED: 'false', STRIPE_WEBHOOK_ENABLED: 'false', PAYMENT_MESSAGING_ENABLED: 'false',
    FINANCIAL_INGESTION_ENABLED: 'false', REVENUE_SHARE_ENABLED: 'false'
  };
  const java = process.env.PR17_JAVA || 'java';
  server = spawn(java, ['-jar', jar, '--spring.profiles.active=api,local,test', '--server.address=127.0.0.1', '--server.port=0', '--logging.structured.format.console=ecs',
    '--platform.provider.fake.enabled=true', `--platform.provider.fake.directory=${temporary}/provider`,
    '--platform.provider.fake.seed=17', `--platform.provider.fake.initial-time=${new Date().toISOString()}`, '--platform.provider.fake.retention=P1D'],
  { cwd: root, env, stdio: ['ignore', log, log] });
  fs.closeSync(log);
  let port;
  for (let i = 0; ; i++) {
    const match = fs.readFileSync(logFile, 'utf8').match(/Tomcat started on port (\d+)/);
    if (match) { port = match[1]; break; }
    assert(server.exitCode === null && i < 120, 'Isolated API did not start; inspect its temporary log locally');
    await pause(500);
  }
  const base = `http://127.0.0.1:${port}`;
  async function api(route, token, body, key) {
    const headers = { Authorization: `${token.startsWith('app_') ? 'Bearer' : 'OpsBearer'} ${token}` };
    if (body) { headers['Content-Type'] = 'application/json'; headers['Idempotency-Key'] = key; }
    const response = await fetch(base + route, { method: body ? 'POST' : 'GET', headers, body: body && JSON.stringify(body) });
    return { status: response.status, data: await response.json() };
  }
  assert.equal((await api('/ops/identity', opsToken(61))).data.role, 'OPS_VIEWER');
  for (const [actor, credential, role] of [[52, 62, 'OPS_OPERATOR'], [53, 63, 'OPS_REVIEWER'], [54, 64, 'PARTNER_VIEWER']]) {
    sql(`INSERT INTO platform.ops_identity(id,organization_id,subject,role,partner_id) VALUES ('${id(actor)}','${id(1)}','browser-${role}','${role}',${role === 'PARTNER_VIEWER' ? `'${id(71)}'` : 'NULL'});
      INSERT INTO platform.ops_credential(id,ops_identity_id,organization_id,environment_id,key_id,secret_hash,not_before,expires_at)
      SELECT '${id(credential)}','${id(actor)}',organization_id,environment_id,'browser',secret_hash,not_before,expires_at FROM platform.ops_credential WHERE id='${id(61)}';`);
  }
  sql(`INSERT INTO platform.ingestion_policy(organization_id,environment_id,provider_account_id,livemode,opening_mode,business_zone) VALUES ('${id(1)}','${id(11)}','${id(21)}',false,'ZERO','UTC');`);
  const malicious = '<img src=x onerror="window.pr17Xss=1">';
  const payments = [];
  for (const orderKey of [malicious, 'browser-payment-confirmed']) {
    const created = await api('/v1/payments', appToken, { orderKey, productRef: 'demo-product', priceVersion: 1, amountMinor: 10000, currency: 'USD', successUrl: 'https://merchant.test/payment-result.html', cancelUrl: 'https://merchant.test/payment-result.html' }, 'browser-' + randomUUID());
    assert.equal(created.status, 201); payments.push(created.data.id);
  }
  // Fixture setup uses admin solely in this newly created database. Browser pages never access SQL.
  // Leave hosted anchors absent in these intentionally unresolved local fixtures: /v1 GET normally
  // refreshes Fake's known checkout and would otherwise resolve the seeded UNKNOWN immediately.
  sql(`UPDATE platform.payment SET checkout_session_id=NULL,payment_intent_id=NULL,hosted_url=NULL,resolution_status='UNKNOWN',financial_status='PENDING_EVIDENCE',unresolved_since=now()-interval '1 hour',updated_at=now()-interval '1 hour' WHERE business_order_id=(SELECT id FROM platform.business_order WHERE order_key='${malicious}');
    UPDATE platform.payment SET checkout_session_id=NULL,payment_intent_id=NULL,hosted_url=NULL,payment_status='SUCCEEDED',captured_amount_minor=amount_minor,resolution_status='KNOWN',financial_status='PENDING_EVIDENCE' WHERE business_order_id=(SELECT id FROM platform.business_order WHERE order_key='browser-payment-confirmed');`);
  assert.equal((await api('/v1/payments/' + payments[0], appToken)).data.resolutionStatus, 'UNKNOWN');
  browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN' });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let unauthenticatedReads = 0;
  const beforeAuthentication = request => { if (request.url().startsWith(base + '/v1/payments/') && request.method() === 'GET') unauthenticatedReads++; };
  page.on('request', beforeAuthentication);
  await page.goto(base + `/payment-result.html?success=true&paymentId=${payments[0]}`);
  assert.match(await page.locator('#status-title').textContent(), /尚未查询|结果待确认/);
  assert.equal(await page.locator('#summary.good').count(), 0);
  assert.equal(await page.locator('#evidence').textContent(), '');
  assert.equal(unauthenticatedReads, 0);
  page.off('request', beforeAuthentication);
  passed('Return URL cannot assert success before authenticated GET');
  await page.locator('#credential').fill(appToken); await page.locator('#result-query button').click();
  await page.locator('#notice').filter({ hasText: '结果待确认。请继续查询原支付' }).waitFor();
  await page.getByText('结果待确认 · UNKNOWN', { exact: true }).waitFor();
  assert((await page.locator('#evidence').textContent()).includes('UNKNOWN'));
  assert.equal(await page.locator('#summary.good').count(), 0);
  assert.equal(await page.locator('button').count(), 1);
  assert.equal(await page.evaluate(() => Object.values(sessionStorage).some(v => v.includes('app_') || v.includes('ops_'))), false);
  await page.screenshot({ path: path.join(output, 'customer-unknown.png'), fullPage: true });
  passed('UNKNOWN query retains original payment without replacement payment action or stored credentials');
  let notificationQueries = 0;
  const countQuery = request => { if (request.url() === base + '/v1/payments/' + payments[0] && request.method() === 'GET') notificationQueries++; };
  page.on('request', countQuery);
  await page.evaluate(paymentId => {
    for (let i = 0; i < 2; i++) window.dispatchEvent(new CustomEvent('payment-notification', { detail: { paymentId, eventId: 'duplicate-fixture-event' } }));
  }, payments[0]);
  await page.locator('#notice').filter({ hasText: '结果待确认。请继续查询原支付' }).waitFor();
  assert.equal(notificationQueries, 1); page.off('request', countQuery);
  passed('Duplicate notifications re-query the original resource once');
  await page.locator('#payment-id').fill(payments[1]); await page.locator('#result-query button').click();
  await page.getByText('付款已确认，账务待齐', { exact: true }).waitFor();
  assert.equal(await page.locator('#summary.good').count(), 0);
  await page.screenshot({ path: path.join(output, 'customer-pending-evidence.png'), fullPage: true });
  passed('SUCCEEDED with missing financial evidence remains amber');
  let releaseRefresh;
  let refreshStarted;
  const startedRefresh = new Promise(resolve => { refreshStarted = resolve; });
  await page.route('**/v1/payments/' + payments[1], async route => {
    const held = new Promise(resolve => { releaseRefresh = resolve; });
    refreshStarted(); await held;
    await route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"FIXTURE_UNAVAILABLE"}' });
  });
  await page.locator('#result-query button').click(); await startedRefresh;
  assert.equal(await page.getByText('付款已确认，账务待齐', { exact: true }).count(), 0);
  assert.equal(await page.locator('#evidence').textContent(), '');
  passed('Same-payment refresh immediately removes prior confirmation while its new GET is held');
  releaseRefresh(); await page.getByText(/本次刷新失败/).waitFor();
  assert.equal(await page.getByText('付款已确认，账务待齐', { exact: true }).count(), 0);
  assert.equal(await page.locator('#evidence').textContent(), '');
  await page.unroute('**/v1/payments/' + payments[1]);
  passed('Failed refresh removes earlier confirmed result');
  await page.locator('#credential').fill('app_' + id(41) + '.' + 'z'.repeat(43));
  assert.equal(await page.locator('#evidence').textContent(), '');
  passed('Credential changes clear current result');
  await page.goto(base + '/ops-console.html');
  await page.locator('#credential').fill(opsToken(61)); await page.locator('#identity-form button').click();
  await page.getByText(/^OPS_VIEWER ·/).waitFor();
  assert.equal(await page.locator('#navigation button').count(), 13);
  assert.equal(await page.locator('#command-panel').isVisible(), false);
  await page.locator('#resource-id').fill(payments[0]); await page.locator('#query').click();
  await page.locator('#query-status').filter({ hasText: /^当前读取于/ }).waitFor();
  assert((await page.locator('#evidence').textContent()).includes(malicious));
  assert.equal(await page.evaluate(() => window.pr17Xss), undefined);
  assert.equal(await page.locator('#evidence img').count(), 0);
  await page.screenshot({ path: path.join(output, 'ops-payment-trace.png'), fullPage: true });
  passed('Viewer sees 13 read views and no actions; API-sourced hostile text remains inert');
  await page.locator('#credential').fill(opsToken(64));
  assert.equal(await page.locator('#evidence').textContent(), '');
  await page.locator('#identity-form button').click(); await page.getByText(/^PARTNER_VIEWER ·/).waitFor();
  assert.equal(await page.locator('#navigation button').count(), 1);
  assert.equal(await page.locator('#view-title').textContent(), '分润详情');
  assert.equal((await api('/ops/payments/' + payments[0], opsToken(64))).status, 403);
  passed('Partner navigation is own shares only and server denies payment detail');
  await page.locator('#credential').fill(opsToken(62)); await page.locator('#identity-form button').click();
  await page.getByText(/^OPS_OPERATOR ·/).waitFor();
  assert.equal(await page.locator('#command-kind option[value=approve]').count(), 0);
  const commandRequests = [];
  await page.route('**/ops/reconciliation-runs', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    commandRequests.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() });
    if (commandRequests.length === 1) { await route.fetch(); await route.abort('failed'); }
    else await route.continue();
  });
  const day = new Date(Date.now() - 2 * 86400000).toISOString().slice(0, 10);
  await page.locator('#command-body').fill(JSON.stringify({ runType: 'BALANCE_LEVEL', businessDate: day, currency: 'USD', reason: 'Browser fixture verification' }));
  await page.locator('#command-submit').click(); await page.getByText(/已保留原请求体与原请求键/).waitFor();
  assert.equal(await page.locator('#command-body').getAttribute('readonly'), '');
  const originalBody = await page.locator('#command-body').inputValue(), originalKey = await page.locator('#command-key').textContent();
  const revalidation = page.waitForResponse(response => response.url() === base + '/ops/identity');
  await page.locator('#identity-form button').click(); await revalidation;
  await page.getByText(/身份已验证，请选择资源并查询/).waitFor();
  assert.equal(await page.locator('#command-body').inputValue(), originalBody);
  assert.equal(await page.locator('#command-key').textContent(), originalKey);
  assert.equal(await page.locator('#command-body').getAttribute('readonly'), '');
  passed('Unchanged trusted identity verification preserves the original pending command');
  await page.locator('#command-retry').click(); await page.locator('#query-status').filter({ hasText: /^当前读取于/ }).waitFor();
  assert.equal(commandRequests.length, 2); assert.deepEqual(commandRequests[0], commandRequests[1]);
  assert((await page.locator('#command-notice').textContent()).includes('历史重放'));
  assert.equal(sql('SELECT count(*) FROM platform.reconciliation_run;'), '1');
  await page.screenshot({ path: path.join(output, 'ops-reconciliation-replay.png'), fullPage: true });
  passed('Lost command response retries exact bytes/key, creates one run and GETs current incomplete report');
  await page.unroute('**/ops/reconciliation-runs');
  const observation = await api('/ops/observability', opsToken(62));
  assert.equal(observation.status, 200, 'Scoped observation backend must exist; rendered placeholder cards are not evidence');
  assert.equal(observation.data.metrics.find(metric => metric.name === 'unknown_payment_count').value, '1');
  fs.writeFileSync(path.join(output, 'observability-sample.json'), JSON.stringify(observation.data, null, 2) + '\n');
  await page.locator('#navigation button[data-view=observability]').click(); await page.locator('#query').click();
  await page.locator('#query-status').filter({ hasText: /^当前读取于/ }).waitFor();
  assert.equal(await page.locator('#metrics article').count(), 11);
  assert((await page.locator('#metrics').textContent()).includes('unknown_payment_count'));
  await page.screenshot({ path: path.join(output, 'ops-observability.png'), fullPage: true });
  await page.screenshot({ path: path.join(output, 'ops-observability-viewport.png') });
  passed('Scoped observability is delivered by real API with 11 metric cards');
  await page.setViewportSize({ width: 390, height: 844 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
  await page.keyboard.press('Tab');
  assert(await page.evaluate(() => document.activeElement !== document.body));
  await page.screenshot({ path: path.join(output, 'ops-mobile.png'), fullPage: true });
  await page.locator('#metrics article').first().scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(output, 'ops-mobile-metrics-viewport.png') });
  passed('390px mobile layout has no horizontal overflow and supports keyboard focus');
  assert.deepEqual(errors, []); passed('No uncaught browser JavaScript errors');
  const correlations = [];
  const allowed = new Set(['event', 'requestId', 'businessOrderId', 'paymentId', 'operationId', 'providerRequestId', 'balanceTransactionId', 'financialEventId', 'journalId', 'runId']);
  for (const line of fs.readFileSync(logFile, 'utf8').split('\n')) {
    try {
      const envelope = JSON.parse(line), event = JSON.parse(envelope.message);
      if (!event.event?.startsWith('ops.') || !event.requestId) continue;
      assert(Object.keys(event).every(key => allowed.has(key)));
      assert(!JSON.stringify(event).includes(appSecret) && !JSON.stringify(event).includes(opsSecret));
      correlations.push(event);
    } catch (error) { if (error instanceof assert.AssertionError) throw error; }
  }
  assert(correlations.some(event => event.paymentId && event.businessOrderId), 'Successful real ops reads must emit known payment/order correlations');
  fs.writeFileSync(path.join(output, 'correlation-samples.json'), JSON.stringify(correlations.slice(0, 10), null, 2) + '\n');
  passed('Actual ECS output carries allowlisted known correlations without fixture credentials');
  fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify({ status: 'PASS', browser: browser.version(), playwright: require(path.join(path.dirname(require.resolve(process.env.PLAYWRIGHT_MODULE || 'playwright')), 'package.json')).version, artifactSha256: createHash('sha256').update(fs.readFileSync(jar)).digest('hex'), provenance: 'Actual isolated Chromium -> same-origin API (api,local,test) -> new PostgreSQL16 container; Fake provider only. Browser failed-response routes explicitly simulate transport loss. No real Stripe/bank action. Credentials generated in memory, no .env loaded.', checks, screenshots: fs.readdirSync(output).filter(name => name.endsWith('.png')), capturedAt: new Date().toISOString() }, null, 2) + '\n');
  console.log(`PASS ${checks.length} actual-browser checks; screenshots in ${path.relative(root, output)}`);
}

main().catch(error => {
  let message = error.name + ': ' + error.message;
  for (const value of [appSecret, opsSecret, appToken, ...[61, 62, 63, 64].map(opsToken)]) message = message.replaceAll(value, '[redacted]');
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, 'result.json'), JSON.stringify({ status: 'FAIL', completedChecks: checks, failure: message }, null, 2) + '\n');
  console.error(`FAIL after ${checks.length} checks: ${message}`); process.exitCode = 1;
}).finally(async () => {
  if (browser) await browser.close();
  if (server && server.exitCode === null) {
    server.kill('SIGTERM');
    await Promise.race([new Promise(resolve => server.once('exit', resolve)), pause(10000)]);
    if (server.exitCode === null) server.kill('SIGKILL');
  }
  if (started) docker('rm', '--force', container);
  fs.rmSync(temporary, { recursive: true, force: true });
});
