import { ApiSession, createPaymentResultController, paymentPresentation, renderEvidence } from './ui/core.mjs';
const field = id => document.getElementById(id);
const notice = message => { field('notice').textContent = message; };
const api = new ApiSession({ scheme: 'Bearer' });
let controller, selectedId;
function render(data, model) {
  field('status-title').textContent = model.title;
  field('summary').className = 'callout ' + model.tone;
  if (Object.keys(data).length) renderEvidence(field('evidence'), data);
  else field('evidence').replaceChildren();
}
function selectPayment() {
  const id = field('payment-id').value.trim();
  if (controller && selectedId === id) return;
  controller?.dispose();
  selectedId = id;
  render({}, paymentPresentation());
  controller = createPaymentResultController({ api, paymentId: id, render, notice });
}
try {
  const id = new URLSearchParams(location.search).get('paymentId') || sessionStorage.getItem('testPaymentId');
  if (/^pay_[0-7][0-9A-HJKMNP-TV-Z]{25}$/.test(id || '')) field('payment-id').value = id;
} catch (_) { notice('会话存储不可用，请手动填写原支付 ID。'); }
selectPayment();
field('credential').addEventListener('input', () => api.setCredential(field('credential').value));
field('payment-id').addEventListener('input', () => { api.clear(); selectPayment(); });
field('result-query').addEventListener('submit', async event => {
  event.preventDefault();
  api.setCredential(field('credential').value);
  selectPayment();
  await controller.refresh();
});
// Notifications may trigger a re-query; only the authenticated GET supplies displayed truth.
window.addEventListener('payment-notification', event => controller.notify(event.detail));
window.addEventListener('pagehide', () => { controller.dispose(); api.clear(); });
