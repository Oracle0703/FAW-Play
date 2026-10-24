"use strict";
(() => {
  // Capability stays in memory. It is never sent in a query, persisted, or logged.
  const token = new URLSearchParams(location.hash.slice(1)).get("token") || "";
  history.replaceState(null, "", location.pathname);
  const {activation: activationApi, ApiError} = AutunnApi;
  const form = document.getElementById("activation-form");
  const input = document.getElementById("code");
  const confirmed = document.getElementById("confirmed");
  const button = document.getElementById("submit");
  const status = document.getElementById("status");
  let terminal = false, busy = false, expiresAt = 0;
  const errors = {
    INVALID_ACTIVATION_CODE: "兑换码无效或已过期，请核对后重试。",
    DEVICE_LIMIT: "这个兑换码的设备额度已用完，请联系提供方迁移授权。",
    DEVICE_REVOKED: "这台设备的授权已被撤销，请联系提供方。",
    LICENSE_EXPIRED: "授权已到期，请联系提供方续费。",
    ACTIVATION_EXPIRED: "二维码已过期，请在车机上生成新二维码。",
    ACTIVATION_CANCELLED: "车机已取消本次激活，请重新扫码。",
    ACTIVATION_ATTEMPTS: "尝试次数已用完，请在车机上重新生成二维码。",
    RATE_LIMITED: "请求过于频繁，请稍后重试。"
  };
  function controls(disabled) { input.disabled = disabled; confirmed.disabled = disabled; button.disabled = disabled; }
  async function api(request) {
    try { return await request(); }
    catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 410 || error.code === "ACTIVATION_ATTEMPTS") terminal = true;
        error.message = errors[error.code] || "服务器暂未完成请求，请稍后重试。";
      }
      throw error;
    }
  }
  const connectionError = error => error instanceof TypeError || error.name === "AbortError" || error instanceof SyntaxError;
  function succeeded() {
    terminal = true;
    input.value = "";
    controls(true);
    status.textContent = "激活成功！请返回车机，App 会自动取得授权。";
  }
  async function load() {
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) { terminal = true; status.textContent = "请用手机扫描 App 上的设备激活二维码。"; return; }
    try {
      const reply = await api(() => activationApi.details(token));
      document.getElementById("device-name").textContent = reply.deviceName;
      document.getElementById("fingerprint").textContent = reply.deviceFingerprint;
      expiresAt = reply.expiresAt;
      if (reply.status === "activated") { succeeded(); return; }
      controls(false);
      status.textContent = "设备已核对，请输入兑换码并确认激活。";
    } catch (error) { status.textContent = connectionError(error) ? "无法连接服务器，请检查网络后重新扫码。" : error.message; }
  }
  form.addEventListener("submit", async event => {
    event.preventDefault();
    if (busy || terminal || !confirmed.checked) return;
    const code = input.value.trim();
    if (!code) { status.textContent = "请输入兑换码。"; return; }
    if (Date.now() / 1000 >= expiresAt) { terminal = true; controls(true); status.textContent = errors.ACTIVATION_EXPIRED; return; }
    busy = true; controls(true); status.textContent = "正在绑定这台车机…";
    try { await api(() => activationApi.approve(token, code)); succeeded(); }
    catch (error) { status.textContent = connectionError(error) ? "请求暂未完成，请重试；重复确认不会占用额外设备额度。" : error.message; }
    finally { busy = false; controls(terminal); }
  });
  load();
})();
