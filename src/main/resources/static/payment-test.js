import { ApiSession, paymentPresentation, hostedUrl } from './ui/core.mjs';
const field = id => document.getElementById(id);
const draftKey = 'pendingTestOrder';
const draftFields = ['orderKey','productRef','priceVersion','amountMinor','currency','quantity','successUrl','cancelUrl'];
let terminalKnown = false, draft = null, boundCredential = '', generation = 0;
const cancelKeys = new Map();
const notice = message => { field('notice').textContent = message; };
const lockDraft = () => { draftFields.concat('operationKey').forEach(id => { field(id).readOnly = true; }); field('create').textContent = '获取原支付（同一请求键）'; };
const unlockDraft = () => { draftFields.concat('operationKey').forEach(id => { field(id).readOnly = false; }); field('create').textContent = '创建测试支付'; };
function hideResult() {
  terminalKnown = false; field('newOrder').disabled = true; field('checkout').hidden = true; field('checkout').removeAttribute('href');
  field('resultPage').hidden = true; field('resultPage').removeAttribute('href'); field('result').hidden = true; field('result').textContent = '';
}
const api = new ApiSession({ scheme: 'Bearer', onClear: () => {
  generation++; hideResult();
  for (const id of ['create','query','cancel']) field(id).disabled = false;
} });
function clearScopedDraft() {
  draft = null; boundCredential = ''; cancelKeys.clear(); unlockDraft();
  for (const id of draftFields.concat('operationKey','paymentId')) field(id).value = '';
  try { sessionStorage.removeItem(draftKey); sessionStorage.removeItem('testPaymentId'); } catch (_) { /* Optional storage. */ }
  notice('应用身份已改变。原身份的订单与查询已清空，请查询当前身份原支付，勿盲目创建替代支付。');
}
field('credential').addEventListener('input', () => {
  const next = field('credential').value.trim();
  if (boundCredential && next !== boundCredential) clearScopedDraft();
  api.setCredential(next);
});
function paymentId() {
  const id = field('paymentId').value.trim();
  if (!/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(id)) throw new Error('请先填写有效的原支付 ID。');
  return id;
}
function number(id) {
  const value = Number(field(id).value);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error('金额、数量和版本必须是浏览器可安全表示的正整数。');
  return value;
}
function remember(id) {
  if (!/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(id)) throw new Error('响应未提供有效原支付 ID');
  field('paymentId').value = id;
  // Only the original resource ID is stored.
  try { sessionStorage.setItem('testPaymentId', id); } catch (_) { /* Optional storage. */ }
  field('resultPage').href = '/payment-result.html?paymentId=' + encodeURIComponent(id); field('resultPage').hidden = false;
}
function render(data) {
  remember(data.id);
  const model = paymentPresentation(data); terminalKnown = model.canStartNew;
  field('result').hidden = false; field('result').textContent = JSON.stringify(data, null, 2);
  field('checkout').hidden = true; field('checkout').removeAttribute('href');
  // SUCCEEDED + PENDING_EVIDENCE is business confirmation; it is never overall funding success.
  notice(data.recovery?.status === 'ESCALATED' ? '原支付需要人工调查，请联系运营并继续查询原支付，勿换键重付。' : model.notice);
  if (data.resolutionStatus === 'KNOWN' && data.financialStatus !== 'CONFLICT' && !['SUCCEEDED','CANCELED'].includes(data.paymentStatus) && data.nextAction?.type === 'REDIRECT_TO_HOSTED_CHECKOUT') {
    const url = hostedUrl(data.nextAction.hostedUrl);
    if (url) { field('checkout').href = url; field('checkout').hidden = false; }
  }
}
async function request(path, method = 'GET', body, key) {
  try { return await api.request(path, { method, body: body === undefined ? undefined : JSON.stringify(body), key }); }
  catch (error) {
    const detail = error.data?.details?.find(item => item.field === 'paymentId');
    if (detail && /^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(detail.issue)) remember(detail.issue);
    if (error.data?.code === 'PROVIDER_UNKNOWN_RESULT') error.message = '原支付意图已保存，结果待确认。请查询原支付；不要换键重试。';
    error.definitePreIntent = !detail && method === 'POST' && path === '/v1/payments' && [400,422].includes(error.status) && ['VALIDATION_ERROR','IDEMPOTENCY_KEY_REQUIRED','AMOUNT_MISMATCH','CURRENCY_NOT_ENABLED'].includes(error.data?.code);
    throw error;
  }
}
async function currentPaymentAfterCommand(response) {
  if (!response.replay) return response.data;
  // A replay is the historical command response, not the original payment's current state.
  const id = response.data.id;
  remember(id);
  notice('收到原请求的历史重放响应，正在查询原支付的当前状态…');
  let current;
  await api.latest('customer-payment', '/v1/payments/' + id, data => { if (data.id === id) current = data; });
  if (!current) throw new Error('当前原支付查询未确认，请继续查询原支付');
  return current;
}
async function run(action) {
  api.setCredential(field('credential').value);
  const current = ++generation, epoch = api.epoch;
  hideResult(); const buttons = [...document.querySelectorAll('button')]; buttons.forEach(button => { button.disabled = true; });
  notice('正在查询或处理原支付…');
  try { const data = await action(); if (current === generation && epoch === api.epoch) { boundCredential = api.credential; render(data); } }
  catch (error) {
    if (current !== generation || epoch !== api.epoch) return;
    terminalKnown = false;
    if (error.definitePreIntent) { draft = null; try { sessionStorage.removeItem(draftKey); } catch (_) {} unlockDraft(); }
    notice(error instanceof TypeError ? '网络结果不确定。请保留原订单和请求键，并继续查询原支付；勿另建支付。' : error.message + ' 请查询原支付。');
  } finally { if (current === generation && epoch === api.epoch) buttons.forEach(button => { button.disabled = button.id === 'newOrder' ? !terminalKnown : false; }); }
}
field('order').addEventListener('submit', event => {
  event.preventDefault();
  run(async () => {
    const owner = (await request('/v1/identity')).data;
    const scope = { organizationId: owner.organizationId, environmentId: owner.environmentId, applicationId: owner.applicationId };
    boundCredential = api.credential;
    if (draft) {
      if (!draft.owner || Object.keys(scope).some(key => draft.owner[key] !== scope[key])) throw new Error('保存的原订单身份无法确认或不匹配；请先查询原支付，勿自动重放创建');
    } else {
      const body = { orderKey: field('orderKey').value, productRef: field('productRef').value, priceVersion: number('priceVersion'), amountMinor: number('amountMinor'), currency: field('currency').value, quantity: number('quantity'), successUrl: field('successUrl').value, cancelUrl: field('cancelUrl').value };
      draft = { body, key: field('operationKey').value, owner: scope };
      try { sessionStorage.setItem(draftKey, JSON.stringify(draft)); }
      catch (_) { draft = null; throw new Error('无法保存原订单与请求键，本次尚未发送。请允许会话存储后重试。'); }
    }
    lockDraft();
    return currentPaymentAfterCommand(await request('/v1/payments', 'POST', draft.body, draft.key));
  });
});
field('newOrder').addEventListener('click', () => {
  if (!terminalKnown) return;
  sessionStorage.removeItem(draftKey); sessionStorage.removeItem('testPaymentId'); location.reload();
});
field('query').addEventListener('click', () => run(async () => {
  const id = paymentId(); let data;
  await api.latest('customer-payment', '/v1/payments/' + id, result => { if (result.id === id) data = result; });
  if (!data) throw new Error('旧查询已丢弃，请查询当前原支付'); return data;
}));
field('paymentId').addEventListener('input', () => { api.clear(); hideResult(); notice('支付 ID 已改变，请查询当前原支付。'); });
field('cancel').addEventListener('click', () => run(async () => {
  const id = paymentId(); if (!cancelKeys.has(id)) cancelKeys.set(id, 'cancel-' + crypto.randomUUID());
  return currentPaymentAfterCommand(await request('/v1/payments/' + id + '/cancel', 'POST', { reason: 'CUSTOMER_ABANDONED' }, cancelKeys.get(id)));
}));
try {
  draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null');
  if (draft) {
    if (!draft.body || typeof draft.key !== 'string' || !draftFields.every(id => ['string','number'].includes(typeof draft.body[id]))) throw new Error('Invalid saved draft');
    draftFields.forEach(id => { field(id).value = draft.body[id]; }); field('operationKey').value = draft.key; lockDraft();
    notice('已恢复原订单与请求键。输入原应用凭据后确认身份并获取原支付。');
  } else {
    const runId = crypto.randomUUID(); field('orderKey').value = 'test-' + runId; field('operationKey').value = 'create-' + runId;
    if (location.protocol === 'https:') { field('successUrl').value = location.origin + '/payment-result.html'; field('cancelUrl').value = field('successUrl').value; }
  }
  const id = sessionStorage.getItem('testPaymentId');
  if (/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(id || '')) { remember(id); notice('已保留原支付 ID，请输入原应用凭据并查询实际状态。'); }
} catch (_) { field('create').disabled = true; notice('无法恢复原订单记录。请先查询原支付，避免重复付款。'); }
window.addEventListener('pagehide', () => api.clear());
