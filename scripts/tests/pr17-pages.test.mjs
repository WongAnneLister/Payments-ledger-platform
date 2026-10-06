import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { observationPresentation } from '../../src/main/resources/static/ui/core.mjs';

const id = 'pay_00000000000000000000000001';
const token = 'app_00000000-0000-0000-0000-000000000001.' + 'a'.repeat(43);
const resource = { id, paymentStatus: 'SUCCEEDED', resolutionStatus: 'KNOWN', financialStatus: 'POSTED', amountMinor: 100, capturedAmountMinor: 100, currency: 'USD' };
const response = (data, replay = false) => ({ ok: true, status: 200, headers: { get: name => replay && name === 'Idempotent-Replay' ? 'true' : null }, text: async () => JSON.stringify(data) });
const tick = async () => { for (let n = 0; n < 8; n++) await new Promise(resolve => setImmediate(resolve)); };
class Field {
  constructor(id) { this.id = id; this.value = ''; this.disabled = false; this.hidden = false; this.readOnly = false; this.textContent = ''; this.events = new Map(); }
  addEventListener(name, callback) { this.events.set(name, callback); }
  removeAttribute(name) { delete this[name]; }
  async dispatch(name) { this.events.get(name)?.({ preventDefault() {} }); await tick(); }
}
let runId = 0;
async function page(fetch, stored = {}) {
  const fields = new Map();
  const names = ['credential','newOrder','checkout','resultPage','result','notice','paymentId','order','create','query','cancel','orderKey','productRef','priceVersion','amountMinor','currency','quantity','successUrl','cancelUrl','operationKey'];
  names.forEach(name => fields.set(name, new Field(name)));
  const get = name => fields.get(name);
  for (const [name, value] of Object.entries({ productRef: 'demo', priceVersion: '1', amountMinor: '100', currency: 'USD', quantity: '1' })) get(name).value = value;
  const storage = new Map(Object.entries(stored));
  globalThis.document = { getElementById: get, querySelectorAll: () => ['newOrder','create','query','cancel'].map(get) };
  globalThis.window = { addEventListener() {} };
  globalThis.location = { protocol: 'https:', origin: 'https://merchant.test', reload() { throw new Error('unexpected redirect'); } };
  globalThis.sessionStorage = { getItem: key => storage.get(key) ?? null, setItem: (key,value) => storage.set(key,String(value)), removeItem: key => storage.delete(key) };
  globalThis.fetch = fetch;
  await import('../../src/main/resources/static/payment-test.js?runtime=' + (++runId));
  get('credential').value = token; get('paymentId').value = id;
  return { get, storage };
}
test('actual payment-test clears terminal confirmation after failed refresh', async () => {
  let count = 0;
  const { get } = await page(async () => { if (++count === 1) return response(resource); throw new TypeError('lost query'); });
  await get('query').dispatch('click'); assert.equal(get('newOrder').disabled, false);
  await get('query').dispatch('click'); assert.equal(get('newOrder').disabled, true);
  assert.equal(get('result').hidden, true); assert.match(get('notice').textContent, /原支付/); assert.doesNotMatch(get('notice').textContent, /确认成功/);
});
test('actual payment-test credential switch discards scoped draft and aborts stale read while controls remain usable', async () => {
  let count = 0, resolvePending, pendingSignal;
  const { get, storage } = await page(async (path, options) => {
    if (++count === 1) return response(resource);
    pendingSignal = options.signal; return new Promise(resolve => { resolvePending = resolve; });
  });
  await get('query').dispatch('click');
  storage.set('pendingTestOrder', '{"private":"old scoped draft"}');
  get('query').events.get('click')({ preventDefault() {} }); await tick();
  get('credential').value = token.replace(/a$/, 'b'); await get('credential').dispatch('input');
  assert.equal(pendingSignal.aborted, true); assert.equal(storage.has('testPaymentId'), false); assert.equal(storage.has('pendingTestOrder'), false);
  assert.equal(get('paymentId').value, ''); assert.equal(get('newOrder').disabled, true);
  assert.equal(get('query').disabled, false, 'new identity can still query an original resource');
  resolvePending(response(resource)); await tick(); assert.equal(get('result').hidden, true);
});
test('actual payment-test reuses original order body and key after lost create response', async () => {
  const calls = []; let post = 0;
  const { get, storage } = await page(async (path, options) => {
    if (path === '/v1/identity') return response({ organizationId: 'org', environmentId: 'env', applicationId: 'app' });
    calls.push(options); if (++post === 1) throw new TypeError('accepted but lost response');
    return response({ ...resource, financialStatus: 'PENDING_EVIDENCE' });
  });
  await get('order').dispatch('submit'); assert.equal(calls.length, 1);
  const saved = JSON.parse(storage.get('pendingTestOrder'));
  get('orderKey').value = 'changed-programmatically'; get('operationKey').value = 'replacement-key';
  await get('order').dispatch('submit'); assert.equal(calls.length, 2);
  assert.equal(calls[0].body, calls[1].body); assert.equal(calls[1].headers['Idempotency-Key'], saved.key);
  assert.equal(get('newOrder').disabled, true); assert.match(get('notice').textContent, /证据待齐/);
  assert.ok([...storage.values()].every(value => !value.includes(token)));
});
for (const outcome of ['UNKNOWN', 'GET_FAILED']) {
  test(`actual payment-test historical POST replay requires current GET before terminal success (${outcome})`, async () => {
    const calls = []; let post = 0;
    const { get, storage } = await page(async (path, options) => {
      calls.push({ path, method: options.method, body: options.body, key: options.headers['Idempotency-Key'] });
      if (path === '/v1/identity') return response({ organizationId: 'org', environmentId: 'env', applicationId: 'app' });
      if (path === '/v1/payments' && options.method === 'POST') {
        if (++post === 1) throw new TypeError('accepted but lost create response');
        return response(resource, true);
      }
      if (path === '/v1/payments/' + id && options.method === 'GET') {
        if (outcome === 'GET_FAILED') throw new TypeError('current query unavailable');
        return response({ ...resource, resolutionStatus: 'UNKNOWN' });
      }
      throw new Error('unexpected endpoint');
    });
    await get('order').dispatch('submit');
    const saved = storage.get('pendingTestOrder');
    await get('order').dispatch('submit');
    assert.equal(get('newOrder').disabled, true, 'historical success cannot enable replacement order');
    assert.doesNotMatch(get('notice').textContent, /付款与账务已确认|付款成功/);
    assert.equal(storage.get('pendingTestOrder'), saved);
    assert.equal(storage.get('testPaymentId'), id);
    assert.ok(calls.some(call => call.path === '/v1/payments/' + id && call.method === 'GET'), 'replay must query original payment');
    const posts = calls.filter(call => call.method === 'POST');
    assert.equal(posts.length, 2); assert.equal(posts[0].body, posts[1].body); assert.equal(posts[0].key, posts[1].key);
    if (outcome === 'UNKNOWN') assert.match(get('result').textContent, /UNKNOWN/);
    else { assert.equal(get('result').hidden, true); assert.match(get('notice').textContent, /原支付/); }
  });
}
test('observability cards preserve exact available values and explicit NO_DATA status', () => {
  const cards = observationPresentation({ observedAt: '2026-10-06T01:00:00Z', metrics: [
    { name: 'unknown_payment_count', status: 'AVAILABLE', value: '9223372036854775807', unit: 'count', sourceAt: '2026-10-06T00:00:00Z' },
    { name: 'reconciliation_residual', status: 'NO_DATA', value: null, unit: 'minor', sourceAt: null }
  ], reconciliationResiduals: [
    { currency: 'USD', residualMinor: '100', outcome: 'FAIL', dataCompleteness: 'COMPLETE' },
    { currency: 'JPY', residualMinor: '-200', outcome: 'INCOMPLETE', dataCompleteness: 'INCOMPLETE' }
  ], alerts: [] });
  assert.equal(cards.find(card => card.name === 'unknown_payment_count').value, '9223372036854775807');
  assert.equal(cards.find(card => card.name === 'reconciliation_residual').status, 'NO_DATA');
  assert.equal(cards.find(card => card.name === 'reconciliation_residual').value, null);
});

async function opsPage(fetch) {
  const html = await readFile(new URL('../../src/main/resources/static/ops-console.html', import.meta.url), 'utf8');
  const fields = new Map(); let doc;
  class OpsField extends Field {
    constructor(id, tagName = 'div') { super(id); this.tagName = tagName; this.children = []; this.dataset = {}; this.ownerDocument = doc; this.attributes = {}; }
    replaceChildren(...nodes) { this.children = nodes; this.textContent = ''; if (this.id === 'command-kind') this.value = ''; }
    append(...nodes) { this.children.push(...nodes); if (this.id === 'command-kind' && !this.value && nodes.length) this.value = nodes[0].value; }
    setAttribute(name, value) { this.attributes[name] = value; }
    querySelectorAll(tag) { return this.children.filter(node => node.tagName === tag); }
  }
  doc = { getElementById: id => fields.get(id), createElement: tag => new OpsField('', tag) };
  for (const match of html.matchAll(/id="([^"]+)"/g)) fields.set(match[1], new OpsField(match[1]));
  globalThis.document = doc; globalThis.window = { addEventListener() {} }; globalThis.location = { search: '' }; globalThis.fetch = fetch;
  await import('../../src/main/resources/static/ops-console.mjs?runtime=' + (++runId));
  const get = id => fields.get(id); get('credential').value = token.replace(/^app_/, 'ops_');
  return { get };
}
async function resultPage(fetch) {
  const html = await readFile(new URL('../../src/main/resources/static/payment-result.html', import.meta.url), 'utf8');
  const fields = new Map(); let doc;
  class ResultField extends Field {
    constructor(id) { super(id); this.children = []; this.ownerDocument = doc; this.attributes = {}; }
    replaceChildren(...nodes) { this.children = nodes; this.textContent = ''; }
    append(...nodes) { this.children.push(...nodes); }
    setAttribute(name, value) { this.attributes[name] = value; }
  }
  doc = { getElementById: id => fields.get(id), createElement: tag => new ResultField(tag) };
  for (const match of html.matchAll(/id="([^"]+)"/g)) fields.set(match[1], new ResultField(match[1]));
  globalThis.document = doc; globalThis.window = { addEventListener() {} }; globalThis.location = { search: '?success=true' };
  globalThis.sessionStorage = { getItem: () => id }; globalThis.fetch = fetch;
  await import('../../src/main/resources/static/payment-result.mjs?runtime=' + (++runId));
  const get = id => fields.get(id); get('credential').value = token;
  return { get };
}
test('actual result module clears old funded evidence immediately on same-ID refresh and rejects later stale completion', async () => {
  let calls = 0, resolveHeld;
  const { get } = await resultPage(async () => {
    if (++calls === 1) return response(resource);
    if (calls === 2) return new Promise(resolve => { resolveHeld = resolve; });
    return response({ ...resource, resolutionStatus: 'UNKNOWN' });
  });
  await get('result-query').dispatch('submit');
  assert.equal(get('status-title').textContent, '付款成功'); assert.match(get('summary').className, /good/);
  await get('result-query').dispatch('submit');
  try {
    assert.doesNotMatch(get('summary').className, /good/, 'held fresh query must clear old green confirmation');
    assert.notEqual(get('status-title').textContent, '付款成功');
    assert.equal(get('evidence').children.length, 0, 'old resource evidence is removed before fresh GET resolves');
    await get('result-query').dispatch('submit'); assert.match(get('status-title').textContent, /UNKNOWN/);
  } finally { resolveHeld(response(resource)); await tick(); }
  assert.match(get('status-title').textContent, /UNKNOWN/);
  get('credential').value = token.replace(/a$/, 'b'); await get('credential').dispatch('input');
  assert.doesNotMatch(get('summary').className, /good/); assert.equal(get('evidence').children.length, 0);
});
for (const revalidatedRole of ['OPS_OPERATOR', 'OPS_VIEWER']) {
  test(`actual ops pending bank intent survives same-credential identity validation (${revalidatedRole})`, async () => {
    const calls = []; let identityReads = 0, bankPosts = 0;
    const { get } = await opsPage(async (path, options) => {
      calls.push({ path, method: options.method, body: options.body, key: options.headers['Idempotency-Key'] });
      if (path === '/ops/identity') return response({ identityId: 'actor', organizationId: 'org', environmentId: 'env', role: ++identityReads === 1 ? 'OPS_OPERATOR' : revalidatedRole });
      if (path === '/ops/bank-statements/import') {
        if (++bankPosts === 1) throw new TypeError('accepted but lost bank response');
        return response({ statementId: '00000000-0000-0000-0000-000000000002', duplicate: false }, true);
      }
      if (path === '/ops/balances') return response({ items: [], asOf: '2026-10-06T00:00:00Z' });
      throw new Error('unexpected route ' + path);
    });
    await get('identity-form').dispatch('submit');
    get('command-kind').value = 'bank'; await get('command-kind').dispatch('change');
    const body = '{ "schemaVersion":"1", "fixtureId":"bank-original", "reason":"original evidence", "lines":[] }';
    get('command-body').value = body;
    await get('command-form').dispatch('submit'); assert.equal(bankPosts, 1);
    const originalKey = get('command-key').textContent;
    await get('identity-form').dispatch('submit');
    if (revalidatedRole === 'OPS_VIEWER') {
      assert.equal(get('command-panel').hidden, true);
      assert.equal(get('command-body').value, ''); assert.equal(get('command-key').textContent, '尚未建立请求');
      await get('command-retry').dispatch('click'); assert.equal(bankPosts, 1);
    } else {
      assert.equal(get('command-kind').value, 'bank', 'pending command kind must remain original');
      assert.equal(get('command-body').value, body, 'pending body display must match retained request');
      assert.equal(get('command-body').readOnly, true); assert.equal(get('command-key').textContent, originalKey);
      await get('command-retry').dispatch('click');
      assert.equal(bankPosts, 2); assert.ok(calls.some(call => call.path === '/ops/balances' && call.method === 'GET'));
      const posts = calls.filter(call => call.method === 'POST');
      assert.equal(posts[0].body, posts[1].body); assert.equal(posts[0].key, posts[1].key);
      assert.doesNotMatch(get('command-notice').textContent, /缺少资源 ID/);
    }
  });
}
