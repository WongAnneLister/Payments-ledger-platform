import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import {
  parseExactJson, formatMinor, paymentPresentation, hostedUrl, drilldownUrl,
  navigationFor, canCommand, renderEvidence, observationPresentation,
  reads, commands, commandReadPath, ApiSession, CommandIntent, createPaymentResultController
} from '../../src/main/resources/static/ui/core.mjs';

const payment = 'pay_00000000000000000000000001';
const base = { id: payment, paymentStatus: 'SUCCEEDED', resolutionStatus: 'KNOWN', financialStatus: 'POSTED', amountMinor: 100, capturedAmountMinor: 100, currency: 'USD' };
const identity = role => ({ identityId: 'actor-1', organizationId: 'org-1', environmentId: 'env-1', role, partnerId: role === 'PARTNER_VIEWER' ? 'partner-1' : null });
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const reply = (data, replay = false) => ({ ok: true, status: 200, headers: { get: name => name === 'Idempotent-Replay' && replay ? 'true' : null }, text: async () => JSON.stringify(data) });
const appToken = 'app_00000000-0000-0000-0000-000000000001.' + 'a'.repeat(43);
const opsToken = 'ops_00000000-0000-0000-0000-000000000001.' + 'a'.repeat(43);

test('JSON transport preserves exact signed int64 and decimal tokens before Number rounding', () => {
  const data = parseExactJson('{"amountMinor":9223372036854775807,"residual":0.1234567890123456789,"negative":-9223372036854775808,"count":2,"text":"9223372036854775807 \\\""}');
  assert.equal(data.amountMinor, '9223372036854775807');
  assert.equal(data.negative, '-9223372036854775808');
  assert.equal(data.residual, '0.1234567890123456789');
  assert.equal(data.count, 2);
  assert.equal(data.text, '9223372036854775807 "');
});
test('money uses exact integer division and keeps currency explicit', () => {
  assert.equal(formatMinor('9223372036854775807', 'USD', 2), '92233720368547758.07 USD');
  assert.equal(formatMinor('-125', 'JPY', 0), '-125 JPY');
  assert.equal(formatMinor('1', 'KWD', 3), '0.001 KWD');
  assert.throws(() => formatMinor(9007199254740992, 'USD', 2), /精度|integer|安全/);
  assert.throws(() => formatMinor('1.5', 'USD', 2), /integer|整数/);
});
test('UNKNOWN ignores apparent successful status and directs original-resource query', () => {
  const model = paymentPresentation({ ...base, resolutionStatus: 'UNKNOWN' });
  assert.equal(model.confirmed, false); assert.equal(model.canStartNew, false);
  assert.match(model.notice, /原支付/); assert.doesNotMatch(model.notice, /确认成功/);
});
test('CONFLICT in any evidence dimension never presents financial success', () => {
  for (const patch of [{ resolutionStatus: 'CONFLICT' }, { financialStatus: 'CONFLICT' }]) {
    const model = paymentPresentation({ ...base, ...patch });
    assert.equal(model.funded, false); assert.equal(model.canStartNew, false); assert.match(model.notice, /CONFLICT|冲突/);
  }
});
test('business confirmation and pending financial evidence are independent', () => {
  const model = paymentPresentation({ ...base, financialStatus: 'PENDING_EVIDENCE' });
  assert.equal(model.confirmed, true); assert.equal(model.funded, false);
  assert.match(model.notice, /业务|付款/); assert.match(model.notice, /资金|账务/); assert.match(model.notice, /PENDING_EVIDENCE|待/);
});
test('incomplete capture cannot be represented as fully funded', () => {
  const model = paymentPresentation({ ...base, capturedAmountMinor: 99 });
  assert.equal(model.confirmed, false); assert.equal(model.funded, false);
});
test('HTTPS hosted URLs exclude embedded credentials and executable protocols', () => {
  assert.equal(hostedUrl('https://checkout.stripe.com/c/test'), 'https://checkout.stripe.com/c/test');
  for (const value of ['javascript:alert(1)', 'http://checkout.test', 'https://user:secret@checkout.test', '//evil.test', '\\evil.test']) assert.equal(hostedUrl(value), null);
});
test('drilldowns allow only known same-origin resource views', () => {
  assert.equal(drilldownUrl('payments', payment), '/ops-console.html?view=payments&id=' + payment);
  assert.equal(drilldownUrl('payments', 'javascript:alert(1)'), null);
  assert.equal(drilldownUrl('https://evil.test', payment), null);
});
test('all twelve PR16 GET resource routes plus observability are exposed', () => {
  assert.deepEqual(reads, {
    payments: '/ops/payments/', refunds: '/ops/refunds/', disputes: '/ops/disputes/',
    payouts: '/ops/payouts/', shares: '/ops/revenue-shares/', journals: '/ops/ledger/journals',
    journal: '/ops/ledger/journals/', balances: '/ops/balances', runs: '/ops/reconciliation-runs/',
    exceptions: '/ops/exceptions', tasks: '/ops/tasks', adjustments: '/ops/adjustments/', observability: '/ops/observability'
  });
});
test('all six PR16 commands use delivered endpoints', () => {
  assert.deepEqual(commands, {
    reconcile: '/ops/reconciliation-runs', bank: '/ops/bank-statements/import',
    exception: '/ops/exceptions/{id}/actions', retry: '/ops/tasks/{id}/retry',
    adjustment: '/ops/adjustments', approve: '/ops/adjustments/{id}/approve'
  });
});
test('accepted commands refresh the corresponding authoritative GET even after navigation changes', () => {
  const target = '00000000-0000-0000-0000-000000000001';
  assert.equal(commandReadPath('reconcile', { runId: target }), '/ops/reconciliation-runs/' + target);
  assert.equal(commandReadPath('bank', {}), '/ops/balances');
  assert.equal(commandReadPath('exception', {}), '/ops/exceptions');
  assert.equal(commandReadPath('retry', {}), '/ops/tasks');
  assert.equal(commandReadPath('adjustment', { adjustmentId: target }), '/ops/adjustments/' + target);
  assert.equal(commandReadPath('approve', {}, target), '/ops/adjustments/' + target);
});
test('truncated payment evidence cannot produce a funded success presentation', () => {
  assert.equal(paymentPresentation({ ...base, truncatedSections: ['journals'] }).funded, false);
});
test('explicit waiting next action contradicts terminal success while NONE allows confirmed funding', () => {
  const waiting = paymentPresentation({ ...base, nextAction: { type: 'WAIT_FOR_CONFIRMATION' } });
  assert.equal(waiting.funded, false); assert.equal(waiting.canStartNew, false); assert.match(waiting.notice, /原支付/);
  assert.equal(paymentPresentation({ ...base, nextAction: { type: 'NONE' } }).funded, true);
});
test('trusted partner identity exposes only own-share navigation and no command', () => {
  assert.deepEqual(navigationFor(identity('PARTNER_VIEWER')), ['shares']);
  assert.deepEqual(navigationFor(null), []); assert.deepEqual(navigationFor(identity('FORGED_ADMIN')), []);
  for (const kind of Object.keys(commands)) assert.equal(canCommand(identity('PARTNER_VIEWER'), kind), false);
});
test('role matrix separates initiation, review and independent author', () => {
  assert.ok(navigationFor(identity('OPS_VIEWER')).includes('disputes'));
  assert.equal(canCommand(identity('OPS_OPERATOR'), 'reconcile'), true);
  assert.equal(canCommand(identity('OPS_ADMIN'), 'bank'), true);
  assert.equal(canCommand(identity('OPS_ADMIN'), 'approve', { createdBy: 'actor-2' }), false);
  assert.equal(canCommand(identity('OPS_REVIEWER'), 'adjustment'), false);
  assert.equal(canCommand(identity('OPS_REVIEWER'), 'approve', { createdBy: 'actor-2', status: 'PENDING_APPROVAL' }), true);
  assert.equal(canCommand(identity('OPS_REVIEWER'), 'approve', { createdBy: 'actor-1', status: 'PENDING_APPROVAL' }), false);
});
test('transport uses same-origin API paths and memory-only credential headers', async () => {
  const calls = []; const api = new ApiSession({ fetch: async (path, options) => { calls.push([path, options]); return reply(base); }, scheme: 'Bearer' });
  api.setCredential(appToken); const result = await api.request('/v1/payments/' + payment);
  assert.equal(calls.length, 1); assert.equal(calls[0][1].headers.Authorization, 'Bearer ' + appToken);
  assert.equal(calls[0][1].cache, 'no-store'); assert.equal(result.data.id, payment);
  await assert.rejects(api.request('https://evil.test/ops/payments/x'), /同源|path|路径/);
});
test('latest resource read rejects stale response even if transport ignores abort', async () => {
  const first = deferred(), second = deferred(), calls = []; const applied = [];
  const api = new ApiSession({ fetch: (path, options) => { calls.push(options); return calls.length === 1 ? first.promise : second.promise; }, scheme: 'Bearer' });
  api.setCredential(appToken);
  const old = api.latest('detail', '/v1/payments/' + payment, data => applied.push(data.marker));
  const current = api.latest('detail', '/v1/payments/' + payment, data => applied.push(data.marker));
  assert.equal(calls.length, 2); assert.equal(calls[0].signal.aborted, true);
  second.resolve(reply({ marker: 'current' })); await current;
  first.resolve(reply({ marker: 'obsolete' })); await old;
  assert.deepEqual(applied, ['current']);
});
test('credential switch aborts reads, clears trusted identity and ignores old results', async () => {
  const pending = deferred(), calls = [], applied = []; let cleared = 0;
  const api = new ApiSession({ fetch: (path, options) => { calls.push(options); return pending.promise; }, onClear: () => { cleared++; }, scheme: 'OpsBearer' });
  api.setCredential(opsToken); api.identity = identity('OPS_ADMIN');
  const reading = api.latest('detail', '/ops/tasks', data => applied.push(data));
  assert.equal(calls.length, 1);
  api.setCredential(opsToken.replace(/a$/, 'b'));
  assert.equal(calls[0].signal.aborted, true); assert.equal(api.identity, null); assert.ok(cleared >= 2);
  pending.resolve(reply({ items: ['foreign'] })); await reading; assert.deepEqual(applied, []);
});
test('uncertain POST retries exact bytes and key, then refreshes current GET after historical replay', async () => {
  const calls = [], refreshes = [];
  const api = new ApiSession({ fetch: async (path, options) => { calls.push([path, options]); if (calls.length === 1) throw new TypeError('lost response'); return reply({ adjustmentId: 'historical', status: 'PENDING_APPROVAL' }, true); }, scheme: 'OpsBearer' });
  api.setCredential(opsToken); const intent = new CommandIntent(api, { key: () => 'stable-test-key' });
  const body = '{ "reason": "人工核查", "amountMinor":9223372036854775807 }';
  assert.ok(intent.prepare('/ops/adjustments', body));
  await assert.rejects(intent.send(() => refreshes.push('current')), /lost response/);
  assert.equal(intent.pending.body, body); assert.equal(intent.pending.key, 'stable-test-key');
  await intent.send(() => refreshes.push('current'));
  assert.deepEqual(calls.map(([, o]) => o.body), [body, body]);
  assert.deepEqual(calls.map(([, o]) => o.headers['Idempotency-Key']), ['stable-test-key', 'stable-test-key']);
  assert.deepEqual(refreshes, ['current']); assert.equal(intent.pending, null);
});
test('pending intent blocks edited retry body and cannot cross credential identity', async () => {
  const api = new ApiSession({ fetch: async () => { throw new TypeError('uncertain'); }, scheme: 'OpsBearer' }); api.setCredential(opsToken);
  const intent = new CommandIntent(api, { key: () => 'intent-key' });
  assert.ok(intent.prepare('/ops/reconciliation-runs', '{"reason":"original"}'));
  await assert.rejects(intent.send(), /uncertain/);
  assert.throws(() => intent.prepare('/ops/reconciliation-runs', '{"reason":"changed"}'), /原|pending|未决/);
  api.setCredential(opsToken.replace(/a$/, 'b')); assert.equal(intent.pending, null);
  await assert.rejects(intent.send(), /intent|待|请求/);
});
test('result controller ignores URL success and duplicate notification payloads', async () => {
  const calls = [], shown = [];
  const api = new ApiSession({ fetch: async (path) => { calls.push(path); return reply({ ...base, resolutionStatus: 'UNKNOWN' }); }, scheme: 'Bearer' }); api.setCredential(appToken);
  const controller = createPaymentResultController({ api, paymentId: payment, search: '?success=true', render: (data, model) => shown.push(model), notice: () => {} });
  await controller.refresh(); assert.equal(shown.length, 1); assert.equal(shown[0].confirmed, false);
  await controller.notify({ eventId: 'same-event', paymentId: payment, paymentStatus: 'SUCCEEDED' });
  await controller.notify({ eventId: 'same-event', paymentId: payment, paymentStatus: 'SUCCEEDED' });
  assert.equal(calls.length, 2); assert.ok(calls.every(path => path === '/v1/payments/' + payment));
});
test('failed result refresh hides prior confirmation and directs original payment query', async () => {
  let attempt = 0; const notices = [], shown = [];
  const api = new ApiSession({ fetch: async () => { if (++attempt === 1) return reply(base); throw new TypeError('network'); }, scheme: 'Bearer' }); api.setCredential(appToken);
  const controller = createPaymentResultController({ api, paymentId: payment, render: (data, model) => shown.push(model), notice: message => notices.push(message) });
  await controller.refresh(); await controller.refresh();
  assert.ok(shown.length >= 2, 'failed refresh must publish an unconfirmed presentation');
  assert.equal(shown.at(-1).confirmed, false); assert.match(notices.at(-1), /原支付/);
});

class Element {
  constructor(tag, ownerDocument) { this.tagName = tag; this.ownerDocument = ownerDocument; this.children = []; this.attributes = {}; this._text = ''; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map(x => x.textContent).join(''); }
  set innerHTML(value) { throw new Error('untrusted HTML assignment'); }
  append(...items) { this.children.push(...items); }
  appendChild(item) { this.children.push(item); return item; }
  replaceChildren(...items) { this._text = ''; this.children = items; }
  setAttribute(name, value) { this.attributes[name] = value; }
}
test('evidence renderer keeps injected markup inert and surfaces truncation and evidence IDs', () => {
  const doc = { createElement: tag => new Element(tag, doc) }; const root = new Element('section', doc);
  const attack = '<img src=x onerror="globalThis.compromised=true">';
  renderEvidence(root, { id: payment, financialStatus: 'PENDING_EVIDENCE', provider: { chargeId: 'ch_original' }, timeline: [{ at: '2026-10-06T00:00:00Z', reason: attack }], journals: [{ journalId: 'jrnl_original', entries: [{ entryId: 'entry-original', amountMinor: '9223372036854775807', currency: 'USD' }] }], truncatedSections: ['providerAttempts'] });
  assert.ok(root.children.length > 0); assert.match(root.textContent, /PENDING_EVIDENCE/); assert.match(root.textContent, /providerAttempts/);
  assert.match(root.textContent, /ch_original|jrnl_original/); assert.ok(root.textContent.includes(attack));
  assert.equal(globalThis.compromised, undefined);
});
test('observation absence never invents zero or PASS', () => {
  const model = observationPresentation(null);
  assert.ok(model.length > 0); assert.ok(model.every(item => item.status === 'UNAVAILABLE' && item.value === null));
});
test('new pages are native labelled modules with live notices and responsive console', async () => {
  const read = name => readFile(new URL('../../src/main/resources/static/' + name, import.meta.url), 'utf8').catch(() => '');
  const result = await read('payment-result.html'), ops = await read('ops-console.html'), css = await read('ops-console.css');
  assert.match(result, /type="module"/); assert.match(result, /原支付/); assert.match(result, /aria-live="polite"/);
  assert.match(ops, /type="module"/); assert.match(ops, /<label/); assert.match(ops, /aria-live="polite"/);
  assert.match(css, /@media/);
});
test('existing payment test links to separate result page and masks pending funds', async () => {
  const html = await readFile(new URL('../../src/main/resources/static/payment-test.html', import.meta.url), 'utf8');
  const js = await readFile(new URL('../../src/main/resources/static/payment-test.js', import.meta.url), 'utf8');
  assert.match(html, /payment-result.html/); assert.match(js, /PENDING_EVIDENCE/); assert.match(js, /AbortController|ApiSession/);
  assert.doesNotMatch(js, /sessionStorage\.setItem\([^\n]*(credential|token)/i);
});
