import { ApiSession, CommandIntent, reads, commands, commandReadPath, resourceId, navigationFor, canCommand, parseExactJson, renderEvidence, observationPresentation } from './ui/core.mjs';
const field = id => document.getElementById(id);
const notice = message => { field('notice').textContent = message; };
const views = {
  payments: ['支付详情', '业务订单、Checkout / PI / Charge 尝试、恢复与资金证据。'],
  refunds: ['退款详情', '退款业务状态与账务状态独立显示，UNKNOWN 不代表失败。'],
  disputes: ['争议详情', 'Provider 状态、本地处理、退款阻断与损失证据。'],
  journals: ['账本浏览', '仅查询封存 Journal；通过 Journal ID 继续查询精确分录。'],
  journal: ['Journal 与 Entry', '借贷方向、账户、币种与精确金额；不合并不同币种。'],
  balances: ['余额与时点核对', '使用 currency / accountPurpose / asOf 筛选；无外部证据不代表 PASS。'],
  runs: ['对账运行详情', '独立数据完整性、核对结果、输入版本与残差。'],
  payouts: ['Payout 详情', 'Provider 转出与模拟银行 fixture 入账证据独立呈现。'],
  shares: ['分润详情', '按原支付 ID 查询。PARTNER_VIEWER 仅返回本人分润。'],
  exceptions: ['差错与调查', 'OPEN / WAITING_EVIDENCE / CONFLICT 及关联运行和证据。'],
  tasks: ['恢复任务', '任务状态、租约、计划时间与原业务资源。'],
  adjustments: ['调整草稿与复核', '查询草稿、证据和拟议分录。独立复核人才能批准。'],
  observability: ['观测与告警', '仅观察积压与证据完整性；告警不修改资金事实或核对结论。']
};
const commandLabels = { reconcile: '创建对账运行', bank: '导入模拟银行 fixture', exception: '差错动作', retry: '重试原恢复任务', adjustment: '创建调整草稿', approve: '独立审批 / 拒绝调整' };
const lists = new Set(['journals', 'balances', 'exceptions', 'tasks', 'observability']);
const filterKeys = {
  journals: ['limit','cursor','providerAccountId','eventKey','postedFrom','postedTo','businessOrderId','balanceTransactionId'],
  balances: ['currency','providerAccountId','accountPurpose','asOf'],
  exceptions: ['limit','cursor','providerAccountId','type','status'],
  tasks: ['limit','cursor','providerAccountId','taskType','status']
};
let view = null, selection = 0, current = null, currentTarget = null, nextCursor = null, definiteRejection = false;
function clearScoped() {
  selection++; view = null; current = null; currentTarget = null; nextCursor = null; definiteRejection = false;
  field('navigation').replaceChildren(); field('identity').textContent = '尚未验证运营身份';
  field('workspace').hidden = true; field('command-panel').hidden = true;
  for (const id of ['resource-id','filters','command-target','command-body']) { field(id).value = ''; field(id).readOnly = false; }
  field('command-kind').disabled = false; field('evidence').replaceChildren(); field('metrics').replaceChildren();
  field('command-history').hidden = true; field('command-response').textContent = ''; field('command-key').textContent = '尚未建立请求';
  field('command-notice').textContent = ''; field('query-status').textContent = ''; field('next-page').hidden = true;
  field('command-retry').hidden = true; field('command-retry').disabled = true; field('command-discard').hidden = true;
  notice('身份已改变。原身份查询与待发送请求已清空，请重新验证。');
}
const api = new ApiSession({ scheme: 'OpsBearer', onClear: clearScoped });
const intent = new CommandIntent(api);
function selectView(next, id = '') {
  if (!navigationFor(api.identity).includes(next)) return;
  selection++; view = next; current = null; currentTarget = null; nextCursor = null;
  field('workspace').hidden = false; field('resource-id').value = resourceId(id) ? id : ''; field('filters').value = '';
  field('view-title').textContent = views[next][0]; field('view-help').textContent = views[next][1];
  field('resource-label').hidden = lists.has(next); field('filter-label').hidden = !filterKeys[next];
  field('next-page').hidden = true; field('evidence').replaceChildren(); field('metrics').replaceChildren(); field('metrics').hidden = next !== 'observability';
  field('query-status').textContent = '尚未查询当前资源。';
  for (const button of field('navigation').querySelectorAll('button')) {
    if (button.dataset.view === next) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  }
  updateCommand();
  if (next === 'observability') renderMetrics(null);
}
function makeNavigation() {
  field('navigation').replaceChildren();
  for (const name of navigationFor(api.identity)) {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = views[name][0]; button.dataset.view = name;
    button.addEventListener('click', () => selectView(name)); field('navigation').append(button);
  }
}
function template(kind) {
  const date = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  const samples = {
    reconcile: { runType: 'BALANCE_LEVEL', businessDate: date, currency: 'USD', reason: '人工核查' },
    bank: { fixtureId: '', schemaVersion: '1', contentDigest: '', lines: [], reason: '导入独立模拟银行证据' },
    exception: { actionType: 'INVESTIGATE', evidenceRefs: [], reason: '人工调查' },
    retry: { reason: '人工重新安排原任务' },
    adjustment: { targetType: 'EXCEPTION', targetId: '', evidenceRefs: [], proposedEntries: [], reason: '基于独立证据的调整草稿' },
    approve: { decision: 'APPROVE', reason: '独立复核证据与拟议分录' }
  };
  return JSON.stringify(samples[kind], null, 2);
}
function configureCommands() {
  if (intent.pending) {
    // Re-validating an unchanged trusted identity must not rewrite the unresolved intent.
    const { kind, target } = intent.pending.context;
    field('command-panel').hidden = false;
    const option = document.createElement('option'); option.value = kind; option.textContent = commandLabels[kind];
    field('command-kind').replaceChildren(option); field('command-kind').value = kind; field('command-kind').disabled = true;
    field('command-body').value = intent.pending.body; field('command-target').value = target;
    lockIntent(); return;
  }
  const role = api.identity?.role;
  const allowed = role === 'OPS_REVIEWER' ? ['approve'] : ['OPS_OPERATOR','OPS_ADMIN'].includes(role) ? Object.keys(commands).filter(kind => kind !== 'approve') : [];
  field('command-panel').hidden = !allowed.length;
  field('command-kind').replaceChildren();
  for (const kind of allowed) { const option = document.createElement('option'); option.value = kind; option.textContent = commandLabels[kind]; field('command-kind').append(option); }
  if (allowed.length) { field('command-body').value = template(allowed[0]); updateCommand(); }
}
function updateCommand() {
  const kind = field('command-kind').value;
  const approvalTarget = intent.pending?.context.approval || (kind === 'approve' && view === 'adjustments' && field('command-target').value.trim() === currentTarget ? current : undefined);
  const allowed = canCommand(api.identity, kind, approvalTarget);
  field('command-submit').disabled = !allowed || !!intent.pending || !!intent.inflight;
  field('command-retry').hidden = !intent.pending;
  field('command-retry').disabled = !!intent.inflight || !allowed;
  field('command-discard').hidden = !intent.pending || !definiteRejection;
  field('command-help').textContent = kind === 'approve'
    ? '先在调整详情查询目标草稿，再填写相同目标 ID。只有独立 OPS_REVIEWER 可复核 PENDING_APPROVAL；服务端重新校验。'
    : kind === 'bank' ? '粘贴由独立 fixture 工具准备的完整导入内容与 contentDigest；银行来源明确为模拟证据。'
    : 'reason 必填。providerAccountId 在同身份有多个 Provider 时必填；组织与环境由运营身份确定。';
}
function renderMetrics(payload) {
  field('metrics').replaceChildren();
  for (const metric of observationPresentation(payload)) {
    const card = document.createElement('article'); card.className = 'metric';
    const heading = document.createElement('h3'); heading.textContent = metric.name;
    const value = document.createElement('strong'); value.textContent = metric.value === null ? '数据不可用' : String(metric.value);
    const description = document.createElement('p'); description.textContent = `${metric.status} · ${metric.unit || '未提供单位'} · 来源时间 ${metric.sourceAt || '未提供'}`;
    card.append(heading,value,description); field('metrics').append(card);
  }
}
function queryPath(cursor) {
  if (!navigationFor(api.identity).includes(view)) throw new Error('当前身份无此视图权限');
  if (!lists.has(view)) {
    const id = field('resource-id').value.trim(); if (!resourceId(id)) throw new Error('请输入有效资源 ID');
    return reads[view] + encodeURIComponent(id);
  }
  const params = new URLSearchParams();
  const source = field('filters').value.trim();
  const filters = source ? parseExactJson(source) : {};
  if (!filters || typeof filters !== 'object' || Array.isArray(filters)) throw new Error('筛选需为 JSON 对象');
  for (const [key,value] of Object.entries(filters)) {
    if (!filterKeys[view]?.includes(key) || !['string','number'].includes(typeof value)) throw new Error('不支持的筛选字段：' + key);
    params.set(key, String(value));
  }
  if (cursor) params.set('cursor', cursor);
  return reads[view] + (params.size ? '?' + params : '');
}
async function query(cursor) {
  const selected = selection, selectedView = view;
  current = null; currentTarget = null; nextCursor = null; field('evidence').replaceChildren(); field('next-page').hidden = true;
  field('query-status').textContent = '正在查询当前资源…'; updateCommand();
  if (view === 'observability') renderMetrics(null);
  try {
    const path = queryPath(cursor), target = field('resource-id').value.trim();
    await api.latest('ops-detail', path, (data, response) => {
      if (selected !== selection || selectedView !== view) return;
      current = data; currentTarget = target; nextCursor = typeof data.nextCursor === 'string' ? data.nextCursor : null;
      renderEvidence(field('evidence'), data);
      if (view === 'observability') renderMetrics(data);
      field('next-page').hidden = !nextCursor;
      field('query-status').textContent = `当前读取于 ${new Date().toISOString()} · requestId ${response.requestId || '未提供'}${data.truncatedSections?.length ? ' · 部分证据已截断' : ''}`;
      notice('已取得当前资源。UNKNOWN、证据缺失与核对 INCOMPLETE 不代表资金确认。'); updateCommand();
    });
  } catch (error) {
    if (selected !== selection || selectedView !== view) return;
    field('query-status').textContent = '本次查询失败，当前状态不可用。'; notice(error.message + '；请查询同一原资源。');
  }
}
function commandPath(kind) {
  if (!Object.hasOwn(commands, kind)) throw new Error('请选择允许动作');
  if (!commands[kind].includes('{id}')) return commands[kind];
  const id = field('command-target').value.trim(); if (!resourceId(id)) throw new Error('请输入动作目标 ID');
  return commands[kind].replace('{id}', encodeURIComponent(id));
}
function lockIntent() {
  field('command-key').textContent = intent.pending?.key || '尚未建立请求';
  for (const id of ['command-body','command-target']) field(id).readOnly = !!intent.pending;
  field('command-kind').disabled = !!intent.pending; updateCommand();
}
async function sendIntent() {
  const pending = intent.pending;
  if (!pending || !canCommand(api.identity, pending.context.kind, pending.context.approval)) {
    field('command-notice').textContent = '原动作已清空或当前受信任身份不允许重试。请重新查询原资源。'; return;
  }
  const selected = selection, epoch = api.epoch, { kind, target } = pending.context;
  field('command-notice').textContent = '正在提交原动作；响应丢失时保留原请求键。'; definiteRejection = false; updateCommand();
  try {
    await intent.send(async response => {
      if (epoch !== api.epoch) return;
      field('command-history').hidden = false; field('command-response').textContent = JSON.stringify(response.data, null, 2);
      field('command-notice').textContent = response.replay ? '收到原请求的历史重放响应，正在查询当前状态。' : '动作已接受，正在查询当前状态。';
      const refreshPath = commandReadPath(kind, response.data, target);
      if (!refreshPath) throw new Error('原动作响应缺少资源 ID，请手动查询同一原资源');
      if (selected === selection) {
        const nextView = { reconcile: 'runs', bank: 'balances', exception: 'exceptions', retry: 'tasks', adjustment: 'adjustments', approve: 'adjustments' }[kind];
        const nextId = response.data.runId || response.data.adjustmentId || target;
        selectView(nextView, nextId);
        if (response.data.adjustmentId) field('command-target').value = response.data.adjustmentId;
        await query();
      } else {
        await api.request(refreshPath);
        if (epoch === api.epoch) notice('已按原动作重新查询当前状态。当前视图已改变；历史响应保留在动作记录中。');
      }
    });
  } catch (error) {
    if (epoch !== api.epoch) return;
    definiteRejection = [400,401,403,404,422].includes(error.status);
    field('command-notice').textContent = `${error.message}。${intent.pending ? '已保留原请求体与原请求键，请按原请求重试；勿另建动作。' : '动作响应已确认，但当前状态查询不可用，请继续查询原资源。'}`;
  } finally { if (epoch === api.epoch) { lockIntent(); updateCommand(); } }
}
field('credential').addEventListener('input', () => api.setCredential(field('credential').value));
field('identity-form').addEventListener('submit', async event => {
  event.preventDefault(); api.setCredential(field('credential').value); notice('正在验证受信任运营身份…');
  try {
    await api.latest('identity', '/ops/identity', data => {
      // A credential can retain its text while its trusted server role/scope changes.
      if (api.identity && ['identityId','organizationId','environmentId','role','partnerId'].some(key => api.identity[key] !== data[key])) api.clear();
      api.identity = data;
      if (!navigationFor(data).length) throw new Error('未识别的运营角色');
      field('identity').textContent = `${data.role} · 组织 ${data.organizationId} · 环境 ${data.environmentId} · 身份 ${data.identityId}`;
      makeNavigation(); configureCommands();
      const params = new URLSearchParams(location.search), requested = params.get('view');
      selectView(navigationFor(data).includes(requested) ? requested : navigationFor(data)[0], params.get('id'));
      notice(data.role === 'PARTNER_VIEWER' ? '仅可查询本人的分润明细。' : '身份已验证，请选择资源并查询。');
    });
  } catch (error) { notice(error.message); }
});
field('query-form').addEventListener('submit', event => { event.preventDefault(); query(); });
field('next-page').addEventListener('click', () => query(nextCursor));
field('resource-id').addEventListener('input', () => { selection++; current = null; currentTarget = null; field('evidence').replaceChildren(); field('query-status').textContent = '资源已改变，请查询当前 ID。'; updateCommand(); });
field('filters').addEventListener('input', () => { selection++; current = null; nextCursor = null; field('next-page').hidden = true; field('evidence').replaceChildren(); });
field('command-kind').addEventListener('change', () => { if (!intent.pending) field('command-body').value = template(field('command-kind').value); updateCommand(); });
field('command-target').addEventListener('input', updateCommand);
field('command-form').addEventListener('submit', event => {
  event.preventDefault(); const kind = field('command-kind').value;
  const approval = kind === 'approve' && view === 'adjustments' && field('command-target').value.trim() === currentTarget ? current : undefined;
  if (!canCommand(api.identity, kind, approval)) { field('command-notice').textContent = '当前身份或作者关系不允许此动作，请先查询目标调整。'; return; }
  try {
    const approvalSnapshot = approval ? Object.freeze({ createdBy: approval.createdBy, status: approval.status }) : undefined;
    intent.prepare(commandPath(kind), field('command-body').value, { kind, target: field('command-target').value.trim(), approval: approvalSnapshot });
    lockIntent(); sendIntent();
  }
  catch (error) { field('command-notice').textContent = error.message; }
});
field('command-retry').addEventListener('click', sendIntent);
field('command-discard').addEventListener('click', () => { if (!definiteRejection || intent.inflight) return; intent.clear(); definiteRejection = false; lockIntent(); field('command-notice').textContent = '已释放明确拒绝的草稿；请按服务端错误修正内容。'; });
window.addEventListener('pagehide', () => api.clear());
