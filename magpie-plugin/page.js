(async () => {
  await magpie.ready;
  try {
    const options = await magpie.options.get();
    location.replace(magpie.service || `http://127.0.0.1:${Number(options?.port) || 18647}/`);
  } catch {
    document.querySelector('#message').textContent = '无法打开连接手机，请重新打开插件页面。';
  }
})();
