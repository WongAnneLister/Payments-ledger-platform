'use strict';
(() => {
  const field = id => document.getElementById(id);
  let terminalKnown = false;
  const draftKey = 'pendingTestOrder';
  const draftFields = ['orderKey','productRef','priceVersion','amountMinor','currency','quantity','successUrl','cancelUrl'];
  const unlockDraft = () => { draftFields.concat('operationKey').forEach(id => { field(id).readOnly = false; }); field('create').textContent = '创建测试支付'; };
  const lockDraft = () => { draftFields.concat('operationKey').forEach(id => { field(id).readOnly = true; }); field('create').textContent = '获取原支付（同一请求键）'; };
  const notice = message => { field('notice').textContent = message; };
  const paymentId = () => {
    const id = field('paymentId').value.trim();
    if (!/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(id)) throw new Error('请先填写有效的支付 ID。');
    return id;
  };
  const number = id => {
    const value = Number(field(id).value);
    if (!Number.isSafeInteger(value) || value < 1) throw new Error('金额、数量和版本必须是浏览器可安全表示的正整数。');
    return value;
  };
  async function request(path, method = 'GET', body, key) {
    const token = field('credential').value.trim();
    if (!/^app_[0-9a-f-]+\.[A-Za-z0-9_-]+$/i.test(token)) throw new Error('请输入项目应用凭据（app_…），不要使用 Stripe 密钥。');
    const headers = { Authorization: `Bearer ${token}` };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (key) headers['Idempotency-Key'] = key;
    const response = await fetch(path, { method, headers, cache: 'no-store', body: body === undefined ? undefined : JSON.stringify(body) });
    const data = await response.json();
    if (!response.ok) {
      const detail = (data.details || []).find(item => item.field === 'paymentId');
      if (detail && /^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(detail.issue)) remember(detail.issue);
      if (data.code === 'PROVIDER_UNKNOWN_RESULT') throw new Error('支付意图已保存，但结果待确认。请查询原支付；不要换键重试。');
      const failure = new Error(`${data.code || 'REQUEST_FAILED'}：${data.message || '请求失败，请查询现有支付。'}`);
      failure.definitePreIntent = !detail && method === 'POST' && path === '/v1/payments' && [400,422].includes(response.status) && ['VALIDATION_ERROR','IDEMPOTENCY_KEY_REQUIRED','AMOUNT_MISMATCH','CURRENCY_NOT_ENABLED'].includes(data.code);
      throw failure;
    }
    return data;
  }
  function remember(id) {
    field('paymentId').value = id;
    try { sessionStorage.setItem('testPaymentId', id); } catch (_) { /* Storage is optional; no credential is retained. */ }
  }
  function render(data) {
    remember(data.id);
    terminalKnown = data.resolutionStatus === 'KNOWN' && ['SUCCEEDED','CANCELED'].includes(data.paymentStatus);
    field('result').hidden = false;
    field('result').textContent = JSON.stringify(data, null, 2);
    const link = field('checkout'); link.hidden = true; link.removeAttribute('href');
    if (data.recovery && data.recovery.status === 'ESCALATED') notice('此支付需要人工调查。请保留支付 ID 并联系运营人员，不要换键重付。');
    else if (data.resolutionStatus !== 'KNOWN') notice('结果待确认，后台将按恢复计划继续处理。请保留此支付，不要重复创建或付款。');
    else if (data.paymentStatus === 'SUCCEEDED') notice('付款已确认成功。账务状态：' + data.financialStatus);
    else if (data.paymentStatus === 'CANCELED') notice('此支付已确认取消。');
    else {
      notice('当前状态：' + data.paymentStatus);
      if (data.nextAction && data.nextAction.type === 'REDIRECT_TO_HOSTED_CHECKOUT') {
        const url = new URL(data.nextAction.hostedUrl);
        if (url.protocol === 'https:' && !url.username && !url.password) { link.href = url.href; link.hidden = false; }
      }
    }
  }
  async function run(action) {
    const buttons = [...document.querySelectorAll('button')]; buttons.forEach(b => { b.disabled = true; });
    field('checkout').hidden = true; notice('正在处理，请稍候…');
    try { render(await action()); } catch (error) {
      if (error.definitePreIntent) { try { sessionStorage.removeItem(draftKey); } catch (_) { } unlockDraft(); }
      notice(error instanceof TypeError ? '网络结果不确定。保留原订单号和请求键，并查询原支付。' : error.message); }
    finally { buttons.forEach(b => { b.disabled = b.id === 'newOrder' ? !terminalKnown : false; }); }
  }
  field('order').addEventListener('submit', event => {
    event.preventDefault();
    run(() => {
      const body = {
        orderKey: field('orderKey').value, productRef: field('productRef').value, priceVersion: number('priceVersion'),
        amountMinor: number('amountMinor'), currency: field('currency').value, quantity: number('quantity'),
        successUrl: field('successUrl').value, cancelUrl: field('cancelUrl').value
      };
      const key = field('operationKey').value;
      // Persist recovery identity BEFORE sending. Never persist application credentials.
      try { sessionStorage.setItem(draftKey, JSON.stringify({ body, key })); }
      catch (_) { throw new Error('浏览器无法保存原订单与请求键，本次尚未发送。请允许当前标签页的会话存储后重试。'); }
      lockDraft();
      return request('/v1/payments', 'POST', body, key);
    });
  });
  field('newOrder').addEventListener('click', () => {
    if (!terminalKnown) return;
    sessionStorage.removeItem(draftKey); sessionStorage.removeItem('testPaymentId'); location.reload();
  });
  field('query').addEventListener('click', () => run(() => request('/v1/payments/' + paymentId())));
  const cancelKeys = new Map();
  field('cancel').addEventListener('click', () => run(() => {
    const id = paymentId(); if (!cancelKeys.has(id)) cancelKeys.set(id, 'cancel-' + crypto.randomUUID());
    return request('/v1/payments/' + id + '/cancel', 'POST', { reason: 'CUSTOMER_ABANDONED' }, cancelKeys.get(id));
  }));
  try {
    const draft = JSON.parse(sessionStorage.getItem(draftKey) || 'null');
    if (draft) {
      if (!draft.body || typeof draft.key !== 'string' || !draftFields.every(id => ['string','number'].includes(typeof draft.body[id]))) throw new Error('Invalid saved draft');
      draftFields.forEach(id => { field(id).value = draft.body[id]; }); field('operationKey').value = draft.key; lockDraft();
      notice('已恢复原订单与请求键。输入应用凭据后可获取原支付；不会创建第二个订单。');
    } else {
      const runId = crypto.randomUUID(); field('orderKey').value = 'test-' + runId; field('operationKey').value = 'create-' + runId;
      if (location.protocol === 'https:') { field('successUrl').value = location.origin + '/payment-test.html'; field('cancelUrl').value = field('successUrl').value; }
    }
    const id = sessionStorage.getItem('testPaymentId');
    if (id) { field('paymentId').value = id; notice('已保留原订单与支付 ID。输入应用凭据后，查询这笔支付的实际状态。'); }
  } catch (_) { field('create').disabled = true; notice('无法恢复本标签页的订单记录。请先查询已有支付，避免重复付款。'); }
})();
