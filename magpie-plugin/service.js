const el = (id) => document.getElementById(id);
const descriptions = {
  loading: ['正在检查', '正在读取本机服务状态。'],
  disabled: ['未开启', '开启后，关闭 Magpie 页面或退出 Magpie 时仍可连接。'],
  unavailable: ['后台服务未就绪', '正在等待 macOS 的后台服务。也可以关闭后重新开启。'],
  connecting: ['未连接', 'Mac 正在等待本地中继响应，并将自动重试。'],
  online: ['已连接', '本地中继已响应。可以配对新设备。'],
};
let busy = false;
let expiry;
let previousDevices;
let pairingUntil = 0;
let deviceToRevoke;

async function call(action, id, allowUsage) {
  const response = await fetch('control', {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain;charset=UTF-8' },
    body: JSON.stringify({ token: document.body.dataset.token, action, id, allowUsage }),
    credentials: 'omit',
    cache: 'no-store',
  });
  if (!response.ok) throw new Error();
  const result = await response.json();
  if (result.error) throw new Error();
  return result;
}

function showStatus(status) {
  const [label, detail] = descriptions[status];
  el('status').textContent = label;
  el('status').dataset.status = status;
  el('detail').textContent = detail;
  el('start').hidden = status !== 'disabled';
  el('stop').hidden = status === 'disabled' || status === 'loading';
  el('issue').hidden = status !== 'online';
  el('pair-usage-option').hidden = status !== 'online';
}

function renderDevices(devices, status) {
  const key = JSON.stringify([devices, status === 'disabled', status === 'unavailable']);
  if (key === previousDevices) return;
  previousDevices = key;
  el('empty').hidden = devices.length > 0;
  el('empty').textContent = status === 'disabled' || status === 'unavailable'
    ? '后台服务启动后可查看设备。' : '暂无已授权设备。';
  el('devices').replaceChildren(...devices.map((device) => {
    const row = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = device.label;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = '撤销';
    button.setAttribute('aria-label', `撤销 ${device.label}`);
    button.addEventListener('click', () => {
      deviceToRevoke = device.id;
      el('confirm-label').textContent = `「${device.label}」将失去这台 Mac 的访问权限。`;
      el('confirm-revoke').showModal();
    });
    const actions = document.createElement('div');
    actions.className = 'actions';
    const usage = document.createElement('button');
    const allowed = device.allowUsage === true;
    usage.type = 'button';
    usage.className = 'device-usage';
    usage.textContent = allowed ? '用量已允许' : '用量未允许';
    usage.setAttribute('aria-label', `允许「${device.label}」查看用量`);
    usage.setAttribute('aria-pressed', String(allowed));
    usage.addEventListener('click', () => perform(
      () => call('usage.set', device.id, !allowed),
      allowed ? '已关闭该设备的用量访问。' : '已允许该设备查看用量。',
    ));
    actions.append(usage, button);
    row.append(label, actions);
    return row;
  }));
}

function clearPairing() {
  clearTimeout(expiry);
  pairingUntil = 0;
  el('pairing').hidden = true;
  el('qr').removeAttribute('src');
  el('link').value = '';
}

async function refresh() {
  if (busy) return;
  try {
    const { status, devices, pairingExpiresAt } = await call('status');
    if (busy) return;
    showStatus(status);
    renderDevices(devices, status);
    if (pairingUntil && pairingExpiresAt !== pairingUntil) clearPairing();
  } catch {
    el('notice').textContent = '无法读取本机连接状态，请重新打开插件页面。';
    showStatus('loading');
    clearPairing();
  }
}

async function perform(action, success) {
  if (busy) return;
  busy = true;
  el('notice').textContent = '';
  try {
    await action();
    el('notice').textContent = success;
  } catch {
    el('notice').textContent = '操作未完成，请检查后台服务和中继后重试。';
  } finally {
    busy = false;
    void refresh();
  }
}

el('start').addEventListener('click', () => perform(() => call('start'), '正在启动后台连接。'));
el('stop').addEventListener('click', () => perform(async () => {
  await call('stop');
  clearPairing();
}, '后台连接已关闭。'));
el('issue').addEventListener('click', () => perform(async () => {
  const allowUsage = el('pair-usage').checked;
  el('pair-usage').checked = false;
  const { link, qr, expiresAt } = await call('issue', undefined, allowUsage);
  clearPairing();
  pairingUntil = expiresAt;
  el('link').value = link;
  el('qr').src = qr;
  el('pairing').hidden = false;
  expiry = setTimeout(clearPairing, Math.max(0, expiresAt - Date.now()));
}, ''));
el('cancel').addEventListener('click', () => perform(async () => {
  await call('cancel');
  clearPairing();
}, '配对码已取消。'));
el('select-link').addEventListener('click', () => {
  el('link').focus();
  el('link').select();
  el('notice').textContent = '已选中链接，按 ⌘C 复制。';
});
el('keep-device').addEventListener('click', () => el('confirm-revoke').close());
el('revoke-device').addEventListener('click', () => {
  const id = deviceToRevoke;
  el('confirm-revoke').close();
  void perform(() => call('revoke', id), '设备权限已撤销。');
});
void refresh();
setInterval(refresh, 1500);
