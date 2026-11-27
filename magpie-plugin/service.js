const el = (id) => document.getElementById(id);
const descriptions = {
  loading: ['正在检查', '正在读取本机连接状态。'],
  unknown: ['状态未知', '暂时无法读取连接状态，请刷新重试。'],
  disabled: ['未开启', '开启后台连接后，等待中继就绪即可生成二维码。'],
  unavailable: ['后台服务未就绪', '请稍候刷新，或关闭后台连接后重新开启。'],
  connecting: ['正在连接', '请检查本地中继是否在运行。Mac 会自动重连。'],
  online: ['已连通', '可以生成二维码，配对新设备。'],
};
let busy = false;
let expiry;
let previousDevices;
let pairingUntil = 0;
let pendingStatus;
let deviceToRevoke;

const issueLabel = '生成配对二维码';

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
  el('status').textContent = `Mac 中继：${label}`;
  el('status').dataset.status = status;
  el('detail').textContent = detail;
  el('start').hidden = status !== 'disabled';
  el('retry').hidden = !['connecting', 'unavailable', 'unknown'].includes(status);
  el('stop').hidden = !['online', 'connecting', 'unavailable'].includes(status);
  el('issue').disabled = status !== 'online';
  el('pair-usage-option').hidden = status !== 'online';
}

function renderDevices(devices, status) {
  const serviceAvailable = status === 'online' || status === 'connecting';
  const key = JSON.stringify([devices, serviceAvailable]);
  if (key === previousDevices) return;
  previousDevices = key;
  el('empty').hidden = devices.length > 0;
  el('empty').textContent = serviceAvailable ? '暂无已授权设备。' : '暂时无法读取授权列表。';
  el('devices').replaceChildren(...devices.map((device) => {
    const row = document.createElement('li');
    const name = document.createElement('div');
    name.className = 'device-name';
    const label = document.createElement('span');
    label.textContent = device.label;
    const connection = document.createElement('span');
    connection.className = 'device-status';
    connection.dataset.connected = String(device.connected === true);
    connection.textContent = device.connected === true ? '当前已连接'
      : device.connected === false ? '当前未连接' : '连接状态未知';
    name.append(label, connection);
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
    row.append(name, actions);
    return row;
  }));
}

function clearPairing() {
  clearTimeout(expiry);
  pairingUntil = 0;
  el('pairing').hidden = true;
  el('qr').removeAttribute('src');
  el('link').value = '';
  el('issue').textContent = issueLabel;
}

async function refresh() {
  if (busy || pendingStatus) return;
  const request = {};
  pendingStatus = request;
  try {
    const { status, devices, pairingExpiresAt } = await call('status');
    if (pendingStatus !== request) return;
    const relayStatus = Object.hasOwn(descriptions, status) ? status : 'unknown';
    showStatus(relayStatus);
    renderDevices(devices ?? [], relayStatus);
    if (pairingUntil && pairingExpiresAt !== pairingUntil && (status === 'online' || status === 'connecting')) {
      clearPairing();
    }
  } catch {
    if (pendingStatus !== request) return;
    showStatus('unknown');
    renderDevices([], 'unknown');
  } finally {
    if (pendingStatus === request) pendingStatus = undefined;
  }
}

async function perform(action, success) {
  if (busy) return;
  busy = true;
  // Invalidate polls started before this action, even if they finish after it.
  pendingStatus = undefined;
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

el('retry').addEventListener('click', refresh);
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
  el('issue').textContent = '重新生成配对二维码';
  expiry = setTimeout(() => {
    clearPairing();
    el('notice').textContent = '配对二维码已过期。';
  }, Math.max(0, expiresAt - Date.now()));
}, ''));
el('cancel').addEventListener('click', () => perform(async () => {
  await call('cancel');
  clearPairing();
}, '配对二维码已取消。'));
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
