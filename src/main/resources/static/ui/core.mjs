/** Same-origin browser boundary. Credentials and command bodies remain memory-only. */
export function parseExactJson(source) {
  let output = '', index = 0;
  while (index < source.length) {
    const char = source[index];
    if (char === '"') {
      const start = index++;
      while (index < source.length) {
        if (source[index] === '\\') { index += 2; continue; }
        if (source[index++] === '"') break;
      }
      output += source.slice(start, index);
    } else if (char === '-' || /[0-9]/.test(char)) {
      const match = source.slice(index).match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      if (!match) throw new SyntaxError('Invalid JSON number');
      const token = match[0];
      output += /[.eE]/.test(token) || !Number.isSafeInteger(Number(token)) ? JSON.stringify(token) : token;
      index += token.length;
    } else { output += char; index++; }
  }
  return JSON.parse(output);
}
function integer(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('金额精度不安全');
  if (!/^-?\d+$/.test(String(value))) throw new Error('金额必须为整数 integer');
  return BigInt(value);
}
export function formatMinor(value, currency, exponent) {
  if (!/^[A-Z]{3}$/.test(currency) || !Number.isInteger(exponent) || exponent < 0 || exponent > 6) throw new Error('币种或小数位无效');
  const amount = integer(value), absolute = amount < 0n ? -amount : amount;
  const scale = 10n ** BigInt(exponent);
  return `${amount < 0n ? '-' : ''}${absolute / scale}${exponent ? '.' + String(absolute % scale).padStart(exponent, '0') : ''} ${currency}`;
}
export function paymentPresentation(data = {}) {
  const resolution = data.resolutionStatus, financial = data.financialStatus;
  let captured = false;
  try { captured = integer(data.capturedAmountMinor) === integer(data.amountMinor) && integer(data.amountMinor) > 0n; } catch (_) { /* Missing evidence is unconfirmed. */ }
  const conflict = resolution === 'CONFLICT' || financial === 'CONFLICT';
  const pendingAction = ['SUCCEEDED','CANCELED'].includes(data.paymentStatus) && data.nextAction?.type && data.nextAction.type !== 'NONE';
  const confirmed = resolution === 'KNOWN' && data.paymentStatus === 'SUCCEEDED' && captured && !conflict && !pendingAction;
  const funded = confirmed && financial === 'POSTED' && !data.truncatedSections?.length;
  const canceled = resolution === 'KNOWN' && data.paymentStatus === 'CANCELED' && !conflict && !pendingAction;
  let title = '请查询原支付', notice = '尚未取得当前状态。请查询原支付，保留原订单号与请求键。';
  if (conflict) { title = '证据冲突 · CONFLICT'; notice = '原支付存在证据冲突（CONFLICT）。请查询原支付并联系运营调查，勿创建替代支付。'; }
  else if (resolution !== 'KNOWN') { title = '结果待确认 · UNKNOWN'; notice = '结果待确认。请继续查询原支付，不要更换订单号或请求键重新付款。'; }
  else if (pendingAction) { title = '付款结果待核对'; notice = `原支付仍包含 ${data.nextAction.type} 动作，当前响应尚不满足完整确认。请继续查询原支付，勿另建支付。`; }
  else if (funded) { title = '付款成功'; notice = '付款与账务已确认，资金证据已入账（POSTED）。'; }
  else if (confirmed) { title = '付款已确认，账务待齐'; notice = data.truncatedSections?.length ? '付款业务已确认，但此页资金证据已截断。请继续查询原支付和完整证据。' : `付款业务已确认，资金账务证据待齐（${financial || 'PENDING_EVIDENCE'}）。请继续查询原支付。`; }
  else if (canceled) { title = '支付已取消'; notice = '原支付已确认取消。'; }
  else { title = `支付状态：${data.paymentStatus || '待查询'}`; notice = '请根据原支付的当前状态继续操作；结果以原支付查询为准。'; }
  return { confirmed, funded, canStartNew: funded || canceled, title, notice, tone: funded ? 'good' : canceled ? 'neutral' : 'pending' };
}
export function hostedUrl(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : null; } catch (_) { return null; }
}
export const reads = Object.freeze({
  payments: '/ops/payments/', refunds: '/ops/refunds/', disputes: '/ops/disputes/', payouts: '/ops/payouts/', shares: '/ops/revenue-shares/',
  journals: '/ops/ledger/journals', journal: '/ops/ledger/journals/', balances: '/ops/balances', runs: '/ops/reconciliation-runs/',
  exceptions: '/ops/exceptions', tasks: '/ops/tasks', adjustments: '/ops/adjustments/', observability: '/ops/observability'
});
export const commands = Object.freeze({ reconcile: '/ops/reconciliation-runs', bank: '/ops/bank-statements/import', exception: '/ops/exceptions/{id}/actions', retry: '/ops/tasks/{id}/retry', adjustment: '/ops/adjustments', approve: '/ops/adjustments/{id}/approve' });
export function commandReadPath(command, response, target) {
  if (command === 'bank') return reads.balances;
  if (command === 'exception') return reads.exceptions;
  if (command === 'retry') return reads.tasks;
  const id = command === 'reconcile' ? response?.runId : response?.adjustmentId || target;
  if (!resourceId(id)) return null;
  return command === 'reconcile' ? reads.runs + id : ['adjustment','approve'].includes(command) ? reads.adjustments + id : null;
}
export function resourceId(value) {
  return typeof value === 'string' && (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value) || /^(pay|ref|dis|po|jrnl|run|adj|exc|task|bor)_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(value));
}
export function drilldownUrl(view, id) {
  if (!Object.hasOwn(reads, view) || !resourceId(id)) return null;
  return `/ops-console.html?view=${encodeURIComponent(view)}&id=${encodeURIComponent(id)}`;
}
const generalRoles = new Set(['OPS_VIEWER', 'OPS_OPERATOR', 'OPS_REVIEWER', 'OPS_ADMIN']);
export function navigationFor(identity) {
  if (identity?.role === 'PARTNER_VIEWER' && identity.partnerId) return ['shares'];
  return generalRoles.has(identity?.role) ? Object.keys(reads) : [];
}
export function canCommand(identity, command, adjustment) {
  if (!Object.hasOwn(commands, command)) return false;
  if (command === 'approve') return Boolean(identity?.role === 'OPS_REVIEWER' && adjustment?.createdBy && adjustment.createdBy !== identity.identityId && adjustment.status === 'PENDING_APPROVAL');
  return ['OPS_OPERATOR', 'OPS_ADMIN'].includes(identity?.role);
}
export class ApiSession {
  constructor({ fetch: transport = globalThis.fetch.bind(globalThis), scheme = 'OpsBearer', onClear = () => {} } = {}) {
    this.fetch = transport; this.scheme = scheme; this.credential = ''; this.identity = null; this.epoch = 0;
    this.controllers = new Set(); this.generations = new Map(); this.channels = new Map(); this.clearListeners = new Set([onClear]);
  }
  onClear(callback) { this.clearListeners.add(callback); return () => this.clearListeners.delete(callback); }
  clear() {
    this.epoch++; this.identity = null;
    for (const controller of this.controllers) controller.abort();
    this.controllers.clear(); this.channels.clear(); this.generations.clear();
    for (const callback of this.clearListeners) callback();
  }
  setCredential(value) {
    const next = String(value).trim();
    if (next === this.credential) return;
    this.clear(); this.credential = next;
  }
  async request(path, { method = 'GET', body, key, controller = new AbortController() } = {}) {
    if (!/^\/(ops|v1)\//.test(path) || /[\r\n\\]/.test(path) || path.includes('://')) throw new Error('请求必须使用同源 API 路径');
    const prefix = this.scheme === 'Bearer' ? 'app_' : 'ops_';
    if (!new RegExp('^' + prefix + '[0-9a-f-]+\\.[A-Za-z0-9_-]+$', 'i').test(this.credential)) throw new Error(`请输入 ${prefix}… 项目凭据`);
    const epoch = this.epoch;
    const headers = { Authorization: `${this.scheme} ${this.credential}` };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    this.controllers.add(controller);
    try {
      const response = await this.fetch(path, { method, headers, body, cache: 'no-store', credentials: 'same-origin', signal: controller.signal });
      const data = parseExactJson(await response.text());
      if (epoch !== this.epoch || controller.signal.aborted) throw Object.assign(new Error('已丢弃旧身份或旧资源响应'), { name: 'AbortError' });
      if (!response.ok) throw Object.assign(new Error(`${data.code || 'REQUEST_FAILED'}：${data.message || '请求失败'}`), { status: response.status, data });
      return { data, replay: response.headers.get('Idempotent-Replay') === 'true', requestId: response.headers.get('X-Request-Id') };
    } finally { this.controllers.delete(controller); }
  }
  async latest(channel, path, apply) {
    this.channels.get(channel)?.abort();
    const controller = new AbortController(), epoch = this.epoch, generation = (this.generations.get(channel) || 0) + 1;
    this.generations.set(channel, generation); this.channels.set(channel, controller);
    try {
      const result = await this.request(path, { controller });
      if (epoch === this.epoch && this.generations.get(channel) === generation && !controller.signal.aborted) { apply(result.data, result); return result; }
      return null;
    } catch (error) {
      if (error.name === 'AbortError' || controller.signal.aborted || epoch !== this.epoch || this.generations.get(channel) !== generation) return null;
      throw error;
    } finally { if (this.channels.get(channel) === controller) this.channels.delete(channel); }
  }
}
export class CommandIntent {
  constructor(api, { key = () => 'ops-' + crypto.randomUUID() } = {}) {
    this.api = api; this.key = key; this.pending = null; this.inflight = null;
    this.unsubscribe = api.onClear(() => this.clear());
  }
  prepare(path, body, context = {}) {
    parseExactJson(body);
    if (this.pending) {
      if (this.pending.path !== path || this.pending.body !== body) throw new Error('存在未决原请求，请按原请求重试');
      return this.pending;
    }
    this.pending = Object.freeze({ path, body, key: this.key(), epoch: this.api.epoch, context: Object.freeze({ ...context }) });
    return this.pending;
  }
  clear() { this.pending = null; this.inflight = null; }
  async send(refresh = () => {}) {
    if (!this.pending || this.pending.epoch !== this.api.epoch) throw new Error('没有待发送的请求 intent');
    if (this.inflight) return this.inflight;
    const pending = this.pending;
    const sending = (async () => {
      const result = await this.api.request(pending.path, { method: 'POST', body: pending.body, key: pending.key });
      if (this.pending !== pending) return null;
      this.pending = null;
      await refresh(result);
      return result;
    })();
    this.inflight = sending;
    try { return await sending; } finally { if (this.inflight === sending) this.inflight = null; }
  }
}
export function createPaymentResultController({ api, paymentId, render, notice }) {
  const events = new Set(); let disposed = false, generation = 0, hasPresented = false;
  const empty = () => { hasPresented = true; render({}, { ...paymentPresentation(), confirmed: false, funded: false, canStartNew: false }); };
  const unsubscribe = api.onClear(() => { generation++; events.clear(); if (!disposed) { empty(); notice('身份已改变，请输入原支付所属凭据并查询原支付。'); } });
  const refresh = async () => {
    if (disposed) return;
    const current = ++generation;
    if (hasPresented) empty(); notice('正在查询原支付…');
    if (!/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(paymentId)) { notice('请填写有效的原支付 ID。'); return; }
    try {
      await api.latest('payment-result', '/v1/payments/' + paymentId, data => {
        if (!disposed && current === generation && data.id === paymentId) { const model = paymentPresentation(data); hasPresented = true; render(data, model); notice(model.notice); }
      });
    } catch (_) { if (!disposed && current === generation) { empty(); notice('本次刷新失败，当前结果尚未确认。请继续查询原支付；不要创建替代支付。'); } }
  };
  return { refresh, async notify(event) {
    if (disposed || event?.paymentId !== paymentId || typeof event.eventId !== 'string' || events.has(event.eventId)) return;
    if (events.size >= 1000) return;
    events.add(event.eventId); await refresh();
  }, dispose() { disposed = true; generation++; unsubscribe(); } };
}
const labels = {
  id: '资源 ID', paymentId: '支付 ID', businessOrderId: '业务订单', paymentStatus: '付款业务状态', resolutionStatus: '确认状态', financialStatus: '资金账务状态',
  provider: '托管支付对象', attempts: '支付尝试', providerAttempts: 'Provider 尝试', operations: '恢复任务与操作', balanceTransactions: '资金事实 · BT',
  journals: '封存账本 · Journal', entries: '账本分录 · Entry', bankEvidence: '模拟银行 fixture 证据', timeline: '证据时间线',
  truncatedSections: '截断部分（此页证据不完整）', dataCompleteness: '数据完整性', createdBy: '发起人', approvedBy: '复核人', createdAt: '创建时间',
  capturedAmountMinor: '已确认付款金额', amountMinor: '金额', currency: '币种', checks: '独立核对结果', items: '查询记录', nextCursor: '下一页游标',
  lines: '分润明细', proposedEntries: '拟议分录', evidenceRefs: '证据引用', recovery: '恢复进度', checkoutSessionId: 'Checkout', paymentIntentId: 'PI', chargeId: 'Charge'
};
const links = { paymentId: 'payments', refundId: 'refunds', disputeId: 'disputes', payoutId: 'payouts', journalId: 'journal', postedJournalId: 'journal', resolutionJournalId: 'journal', runId: 'runs', adjustmentId: 'adjustments' };
function element(doc, tag, text, className) {
  const node = doc.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.setAttribute('class', className);
  return node;
}
function scalar(doc, key, value, currency) {
  const node = element(doc, 'span', value === null || value === undefined ? '未提供 / UNAVAILABLE' : String(value));
  if (/Minor$/.test(key) && value !== null) node.textContent = `${value}${currency ? ' ' + currency : ''}（最小单位，精确值）`;
  const href = links[key] && drilldownUrl(links[key], value);
  if (href) { const anchor = element(doc, 'a', String(value)); anchor.setAttribute('href', href); node.replaceChildren(anchor); }
  if (key === 'hostedUrl') { const hosted = hostedUrl(value); if (hosted) { const anchor = element(doc, 'a', '托管 HTTPS 收银台'); anchor.setAttribute('href', hosted); anchor.setAttribute('rel', 'noreferrer'); node.replaceChildren(anchor); } }
  return node;
}
function objectView(doc, data, currency, depth = 0) {
  if (data === null || typeof data !== 'object') return scalar(doc, '', data, currency);
  if (Array.isArray(data)) {
    const list = element(doc, 'ol', undefined, 'records');
    if (!data.length) list.append(element(doc, 'li', '无返回记录；缺少记录不代表核对通过。'));
    for (const item of data) { const row = element(doc, 'li'); row.append(objectView(doc, item, currency, depth + 1)); list.append(row); }
    return list;
  }
  const group = element(doc, 'dl', undefined, depth ? 'facts compact' : 'facts');
  const ownCurrency = data.currency || currency;
  for (const [key, value] of Object.entries(data)) {
    const term = element(doc, 'dt', labels[key] ? `${labels[key]} · ${key}` : key);
    const definition = element(doc, 'dd');
    definition.append(typeof value === 'object' && value !== null ? objectView(doc, value, ownCurrency, depth + 1) : scalar(doc, key, value, ownCurrency));
    group.append(term, definition);
  }
  return group;
}
export function renderEvidence(root, data) {
  const doc = root.ownerDocument;
  root.replaceChildren();
  if (data?.truncatedSections?.length) root.append(element(doc, 'p', `证据已截断：${data.truncatedSections.join('、')}。请按关联 ID 查询更多证据，勿视为完整资金确认。`, 'callout pending'));
  root.append(objectView(doc, data, data?.currency));
}
export const metricNames = Object.freeze(['unknown_payment_count', 'unknown_payment_oldest_age', 'refund_reserved_age', 'webhook_lag', 'outbox_oldest_age', 'ledger_pending_evidence', 'unclassified_bt_count', 'trial_balance_failures', 'reconciliation_residual', 'payout_unmatched_age', 'share_cap_violations']);
export function observationPresentation(data) {
  return metricNames.map(name => {
    const metric = Array.isArray(data?.metrics) ? data.metrics.find(item => item.name === name) : data?.metrics?.[name];
    return metric && metric.status !== 'UNAVAILABLE' && metric.value !== null && metric.value !== undefined
      ? { ...metric, name, status: metric.status || 'OBSERVED' }
      : { name, value: null, status: metric?.status === 'NO_DATA' ? 'NO_DATA' : 'UNAVAILABLE', unit: metric?.unit || '未提供', sourceAt: metric?.sourceAt || null };
  });
}
