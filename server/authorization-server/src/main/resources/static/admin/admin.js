"use strict";
(() => {
  const byId = id => document.getElementById(id);
  const {admin: adminApi, login: loginApi, ApiError} = AutunnApi;
  const SESSION_KEY = "faw-play-admin-refresh-v1";
  const loginButton = byId("login"), logoutButton = byId("logout"), controls = byId("controls");
  const status = byId("status"), loginPanel = byId("login-panel"), loginStatus = byId("login-status");
  let listOffset = 0, selectedId = null, listRequest = 0, detailRequest = 0;
  let diagnosticOffset = 0, diagnosticRequest = 0, diagnosticDetailRequest = 0, diagnosticId = null, diagnosticReport = null;
  let latestCodes = [];
  let codeRequest = 0, selectedCode = null, searchedCode = "", deletingCode = false;
  let updateApps = [], updateRequest = 0, uploadRequest = null, uploading = false, deleting = false;
  let updateSettingsRequest = 0, updateHeaderEnabled = null, updateSettingsBusy = false;
  let tokens = null, tokenDeadline = 0, generation = 0, loginTimer = null, refreshing = null, creating = false;
  const messages = {LOGIN_UNAVAILABLE: "登录服务暂不可用，请稍后重试。", LOGIN_EXPIRED: "登录已过期，请重新登录。",
    LOGIN_DENIED: "管理员登录已取消。", UNAUTHORIZED: "登录已过期，请重新登录。",
    FORBIDDEN: "这个账号没有 license-admin 管理员权限。", RATE_LIMITED: "请求过于频繁，请稍后重试。",
    INVALID_REQUEST: "请检查输入：设备额度不能少于当前在用设备，累计次数不能少于已用次数；限期授权需填写到期时间。", NOT_FOUND: "授权或设备已不存在，请刷新列表。",
    CODE_NOT_FOUND: "未找到这个卡密，请核对是否粘贴了完整卡密。", CODE_SHARED_LICENSE: "这是旧账号模式的已兑换卡密，请通过授权详情管理对应账号授权。",
    INVALID_APK: "APK 校验失败。请选择签名完整的 Release APK，最低 Android 9，不支持调试包或拆分包。",
    DIAGNOSTIC_NOT_FOUND: "报告已过期或被容量清理，请刷新列表。",
    UPDATE_PACKAGE_MISMATCH: "APK 包名与所选应用不一致。", UPDATE_SIGNER_MISMATCH: "APK 签名与服务器信任的原发布证书不一致。",
    UPDATE_DOWNGRADE: "版本号低于已发布版本，请增加 versionCode 后重新打包。", UPDATE_VERSION_CONFLICT: "相同 versionCode 已发布了另一个 APK，请增加版本号。",
    UPDATE_NOT_CONFIGURED: "服务器尚未配置此应用的发布证书，请配置 UPDATE_SIGNING_CERTIFICATES。",
    UPDATE_STORAGE: "更新目录不可写，请检查 NAS 映射是否可写及容器 UID 10001 的权限。",
    UPDATE_UNAVAILABLE: "已有发布文件校验失败，请先检查服务器更新目录。", UPDATE_BUSY: "另一个发布正在进行，请稍后刷新状态并重试。",
    UPDATE_SETTINGS_UNAVAILABLE: "更新请求头设置暂不可用，请检查服务器存储并刷新后重试。",
    UPLOAD_RESPONSE: "服务器返回异常，请检查上传大小和超时设置，并刷新发布状态。",
    UPLOAD_CONNECTION: "上传连接中断或超时，请刷新发布状态，确认是否已发布。",
    UPLOAD_ABORTED: "上传已停止。若文件已传完，服务器可能继续发布，请刷新发布状态确认。",
    UPDATE_TOO_LARGE: "APK 超过 512 MiB，或反向代理的上传限制过小。"};

  function show(element, text, error = false) { element.textContent = text; element.classList.toggle("error", error); }
  async function api(request, fallback = "请求未完成，请稍后重试。") {
    try { return await request(); }
    catch (error) {
      if (error instanceof ApiError) error.message = messages[error.code] || fallback;
      if (error instanceof TypeError || error.name === "AbortError" || error instanceof SyntaxError)
        throw new Error("连接暂未完成，请检查网络后重试。");
      throw error;
    }
  }
  function accept(reply) {
    tokens = reply; tokenDeadline = performance.now() + reply.expiresIn * 1000;
    try { sessionStorage.setItem(SESSION_KEY, reply.refreshToken); } catch (_) { /* Login still works when storage is disabled. */ }
  }
  function signedOut(message = "请先登录管理员账号。", error = false, clearSession = true) {
    diagnosticRequest++; diagnosticDetailRequest++; diagnosticId = null; diagnosticReport = null; diagnosticOffset = 0;
    byId("diagnostic-controls").disabled = true; byId("reload-diagnostics").disabled = true;
    byId("diagnostic-previous").disabled = true; byId("diagnostic-next").disabled = true;
    byId("diagnostic-list").replaceChildren(); byId("diagnostic-metadata").replaceChildren();
    byId("diagnostic-text").textContent = ""; byId("diagnostic-detail").hidden = true;
    byId("diagnostic-device").value = ""; byId("diagnostic-version").value = "";
    byId("diagnostic-page").textContent = ""; show(byId("diagnostic-status"), message, error); show(byId("diagnostic-detail-status"), "");
    if (clearSession) { try { sessionStorage.removeItem(SESSION_KEY); } catch (_) {} }
    if (uploadRequest) uploadRequest.abort();
    updateRequest++; updateApps = []; byId("update-app").replaceChildren();
    updateSettingsRequest++; updateHeaderEnabled = null; updateSettingsBusy = false; renderUpdateSettings();
    show(byId("update-settings-status"), message, error);
    byId("upload-controls").disabled = true; byId("reload-updates").disabled = true;
    byId("update-apk").value = ""; byId("release-notes").value = ""; byId("publish-confirm").checked = false;
    byId("upload-progress-panel").hidden = true; byId("published-release").hidden = true;
    byId("current-release").replaceChildren(node("p", "请先登录管理员账号。")); show(byId("upload-status"), message, error);
    byId("delete-confirm").hidden = true;
    codeRequest++; selectedCode = null; searchedCode = "";
    byId("code-search-panel").hidden = true; byId("code-search-controls").disabled = true;
    byId("code-search-input").value = ""; byId("code-search-result").replaceChildren();
    byId("code-search-result").hidden = true; byId("code-delete-confirm").hidden = true;
    show(byId("code-search-status"), "");
    generation++; listRequest++; detailRequest++; selectedId = null; listOffset = 0;
    byId("licenses-panel").hidden = true; byId("detail-panel").hidden = true;
    byId("license-list").replaceChildren(); byId("device-list").replaceChildren(); byId("edit-controls").disabled = true;
    clearTimeout(loginTimer); loginTimer = null; tokens = null; refreshing = null;
    loginPanel.hidden = true; loginButton.disabled = false; loginButton.hidden = false; logoutButton.hidden = true;
    byId("account").textContent = "未登录"; controls.disabled = true;
    latestCodes = []; byId("activation-code").value = ""; byId("result").hidden = true; show(status, message, error);
    byId("verification").removeAttribute("href"); byId("user-code").textContent = "";
  }
  async function access() {
    if (!tokens) throw new Error("请先登录管理员账号。");
    if (performance.now() >= tokenDeadline - 15000) {
      if (!refreshing) {
        const current = generation;
        refreshing = api(() => loginApi.refresh(tokens.refreshToken)).then(reply => {
          if (generation !== current) throw new Error("登录已结束。");
          accept(reply);
        }).finally(() => { if (generation === current) refreshing = null; });
      }
      await refreshing;
    }
    if (!tokens) throw new Error("登录已结束。");
    return tokens.accessToken;
  }
  async function authorized(request, ...args) {
    const bearer = await access();
    return api(() => request(bearer, ...args));
  }
  function failed(error) {
    if (["UNAUTHORIZED", "LOGIN_EXPIRED", "FORBIDDEN"].includes(error.code)) signedOut(error.message, true);
    else show(status, error.message, true);
  }

  function signedIn(account) {
    byId("account").textContent = account.username; loginPanel.hidden = true;
    loginButton.hidden = true; logoutButton.hidden = false; controls.disabled = false;
    byId("verification").removeAttribute("href"); byId("user-code").textContent = "";
    show(status, "已登录。"); byId("licenses-panel").hidden = false;
    byId("code-search-panel").hidden = false; byId("code-search-controls").disabled = false;
    loadLicenses(); loadUpdates(); loadUpdateSettings();
    byId("diagnostic-controls").disabled = false; byId("reload-diagnostics").disabled = false;
    show(byId("diagnostic-status"), "打开此页即可查看设备上传的报告。");
    if (window.location?.hash === "#diagnostics") loadDiagnostics();
  }
  async function restoreSession() {
    let refreshToken;
    try { refreshToken = sessionStorage.getItem(SESSION_KEY); } catch (_) { return; }
    if (!refreshToken) return;
    if (refreshToken.length > 16384) { signedOut(); return; }
    const current = ++generation; loginButton.disabled = true; show(status, "正在恢复管理员登录…");
    try {
      const reply = await api(() => loginApi.refresh(refreshToken));
      if (current !== generation) return;
      accept(reply);
      const account = await authorized(adminApi.me);
      if (current === generation) signedIn(account);
    } catch (error) {
      if (current === generation) signedOut(error.message, true,
        ["UNAUTHORIZED", "LOGIN_EXPIRED", "LOGIN_DENIED", "FORBIDDEN"].includes(error.code));
    }
  }
  loginButton.addEventListener("click", async () => {
    const current = ++generation; loginButton.disabled = true; show(status, "正在连接登录服务…");
    try {
      const grant = await api(loginApi.start);
      if (generation !== current) return;
      byId("user-code").textContent = grant.userCode;
      byId("verification").href = grant.verificationUri;
      loginPanel.hidden = false; show(loginStatus, "等待登录确认…"); show(status, "");
      const deadline = performance.now() + grant.expiresIn * 1000;
      let interval = grant.interval * 1000;
      const poll = async () => {
        if (generation !== current) return;
        if (performance.now() >= deadline) { signedOut("登录验证码已过期，请重新登录。", true); return; }
        try {
          const reply = await api(() => loginApi.poll(grant.deviceCode));
          if (generation !== current) return;
          if (reply.status === "ready") {
            accept(reply);
            const account = await authorized(adminApi.me);
            if (generation !== current) return;
            signedIn(account); return;
          }
          if (reply.status === "slow_down") interval += 5000;
          loginTimer = setTimeout(poll, interval);
        } catch (error) { if (generation === current) signedOut(error.message, true); }
      };
      loginTimer = setTimeout(poll, interval);
    } catch (error) { if (generation === current) signedOut(error.message, true); }
  });
  byId("cancel-login").addEventListener("click", () => signedOut("已取消登录。"));
  logoutButton.addEventListener("click", () => signedOut("已退出管理页面。"));

  byId("generate-form").addEventListener("submit", async event => {
    event.preventDefault(); if (creating || !tokens) return;
    const count = Number(byId("code-count").value);
    const deviceLimit = Number(byId("devices").value), days = Number(byId("days").value), activationLimit = Number(byId("activations").value), permanent = byId("duration").value === "permanent";
    if (!Number.isInteger(count) || count < 1 || count > 1000 || !Number.isInteger(deviceLimit) || deviceLimit < 1 || deviceLimit > 100 || (!permanent && (!Number.isInteger(days) || days < 1 || days > 36500)) || !Number.isInteger(activationLimit) || activationLimit < 1 || activationLimit > 100000) {
      show(status, "请核对生成数量（1–1000）、设备数量、累计激活次数和有效天数。", true); return;
    }
    const current = generation; creating = true; controls.disabled = true; logoutButton.disabled = true;
    show(status, "正在生成 " + count + " 个兑换码…");
    try {
      const clock = await authorized(adminApi.me);
      const grant = {deviceLimit, activationLimit, permanent, note: byId("code-note").value, ...(permanent ? {} : {validUntil: clock.serverTime + days * 86400})};
      const result = count === 1 ? await authorized(adminApi.createCode, grant)
        : await authorized(adminApi.createCodes, count, grant);
      if (generation !== current) return;
      latestCodes = count === 1 ? [result] : result.codes;
      const reply = latestCodes[0];
      byId("activation-code").value = latestCodes.map(row => row.activationCode).join("\n");
      byId("activation-code").rows = count === 1 ? 1 : Math.min(8, count);
      byId("copy").textContent = count === 1 ? "复制兑换码" : "复制全部兑换码";
      byId("result-count").textContent = count + " 个";
      byId("result-devices").textContent = String(reply.deviceLimit) + " 台";
      byId("result-activations").textContent = reply.activationLimit + " 次";
      byId("result-expiry").textContent = expiryText(reply.validUntil);
      byId("result").hidden = false; show(byId("copy-status"), ""); show(status, count === 1 ? "兑换码已生成。" : "已生成 " + count + " 个独立兑换码，请复制或导出保存。");
    } catch (error) { if (generation === current) failed(error); }
    finally { creating = false; controls.disabled = !tokens; logoutButton.disabled = false; }
  });
  byId("copy").addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(byId("activation-code").value); show(byId("copy-status"), "已复制。"); }
    catch (_) { byId("activation-code").focus(); byId("activation-code").select(); show(byId("copy-status"), "请复制已选中的兑换码。", true); }
  });
  function csvCell(value) {
    let text = String(value ?? "");
    // Notes may be pasted into spreadsheets: export text, never executable formulas.
    if (/^\s*[=+\-@]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g, '""') + '"';
  }
  byId("export-codes").addEventListener("click", () => {
    if (!tokens || !latestCodes.length) return;
    const rows = [["序号", "授权码", "每码设备数量", "每码累计激活次数", "授权期限", "到期时间", "备注"],
      ...latestCodes.map((row, i) => [i + 1, row.activationCode, row.deviceLimit, row.activationLimit,
        row.permanent ? "永久" : "限期", row.validUntil == null ? "" : new Date(row.validUntil * 1000).toISOString(), row.note])];
    const data = "\ufeff" + rows.map(row => row.map(csvCell).join(",")).join("\r\n") + "\r\n";
    const url = URL.createObjectURL(new Blob([data], {type: "text/csv;charset=utf-8"}));
    const link = document.createElement("a"); link.href = url; link.download = "Autunn-Play-授权码-" + latestCodes.length + "个.csv"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000); show(byId("copy-status"), "CSV 已导出，请妥善保存。退出或刷新页面后无法再次查看明文兑换码。");
  });
  const expiryText = seconds => seconds == null ? "永久授权" : new Intl.DateTimeFormat("zh-CN", {dateStyle: "medium", timeStyle: "short"}).format(new Date(seconds * 1000));
  function node(tag, text, className) {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
  }
  function limitText(limit) { return limit == null ? "不限（旧授权）" : String(limit); }
  const codeStates = {unused: "未激活", active: "已激活", expired: "已过期", invalidated: "关联授权已作废", deleted: "已删除"};
  function renderCode(reply) {
    selectedCode = reply;
    const target = byId("code-search-result"), license = reply.license, e = license?.authorization;
    target.replaceChildren(node("h3", "卡密详情"), node("p", codeStates[reply.status] || "状态未知", "code-state " + reply.status));
    const stats = node("dl");
    const active = license ? license.devices.filter(d => d.revokedAt == null).length : 0;
    for (const [name, value] of [["授权期限", expiryText(e ? e.validUntil : reply.validUntil)],
      ["在用设备 / 额度", (reply.status === "deleted" || reply.status === "invalidated" ? 0 : active) + " / " + (e ? e.deviceLimit : reply.deviceLimit)],
      ["累计激活 / 上限", (license?.activationsUsed || 0) + " / " + limitText(e ? e.activationLimit : reply.activationLimit)],
      ["生成时间", expiryText(reply.createdAt)], ["首次激活", reply.redeemedAt == null ? "尚未激活" : expiryText(reply.redeemedAt)]]) {
      const item = node("div"); item.append(node("dt", name), node("dd", value)); stats.append(item);
    }
    target.append(stats, node("p", "卡密备注：" + (reply.note || "未设置")));
    if (e) {
      target.append(node("p", "授权备注：" + (e.note || "未设置")), node("h3", "关联设备"));
      for (const d of license.devices) {
        const row = node("div", undefined, "device-row");
        row.append(node("strong", d.name), node("span", "设备识别码 " + d.keyHash.slice(0, 16).toUpperCase(), "identifier"),
          node("span", "激活时间：" + expiryText(d.enrolledAt) + " · " +
            (reply.status === "deleted" || reply.status === "invalidated" ? "授权已作废" : d.revokedAt == null ? "已绑定" : "已撤销"), "row-meta"),
          node("p", "备注：" + (d.note || "未设置")));
        target.append(row);
      }
      if (!license.devices.length) target.append(node("p", "暂无关联设备。"));
    }
    const actions = node("div", undefined, "code-card-actions");
    if (e && !["deleted", "invalidated"].includes(reply.status)) {
      const view = node("button", "查看并管理授权设备"); view.type = "button"; view.disabled = deletingCode;
      view.addEventListener("click", () => loadDetail(e.id)); actions.append(view);
    }
    if (reply.canDelete) {
      const remove = node("button", "删除此卡密", "danger"); remove.type = "button"; remove.disabled = deletingCode;
      remove.addEventListener("click", () => { if (selectedCode?.codeHash === reply.codeHash && !deletingCode) byId("code-delete-confirm").hidden = false; });
      actions.append(remove);
    } else if (reply.status !== "deleted") target.append(node("p", messages.CODE_SHARED_LICENSE, "field-note"));
    if (reply.deletedAt != null) target.append(node("p", "删除时间：" + expiryText(reply.deletedAt), "field-note"));
    target.append(actions); target.hidden = false; byId("code-delete-confirm").hidden = true;
  }
  async function searchCode() {
    if (!tokens || deletingCode) return;
    const value = byId("code-search-input").value.trim();
    if (!value || value.length > 200) { show(byId("code-search-status"), "请粘贴完整卡密。", true); return; }
    const current = generation, request = ++codeRequest;
    selectedCode = null; searchedCode = value; byId("code-delete-confirm").hidden = true;
    byId("code-search-result").hidden = true; byId("code-search-result").replaceChildren();
    byId("code-search-controls").disabled = true; show(byId("code-search-status"), "正在查找卡密…");
    try {
      const reply = await authorized(adminApi.searchCode, value);
      if (current !== generation || request !== codeRequest) return;
      renderCode(reply); show(byId("code-search-status"), "已找到卡密。");
    } catch (error) {
      if (current === generation && request === codeRequest) { show(byId("code-search-status"), error.message, true); failed(error); }
    } finally { if (current === generation && request === codeRequest) byId("code-search-controls").disabled = !tokens; }
  }
  byId("code-search-form").addEventListener("submit", event => { event.preventDefault(); return searchCode(); });
  byId("code-search-input").addEventListener("input", () => {
    if (deletingCode) return;
    codeRequest++; selectedCode = null; searchedCode = "";
    byId("code-search-result").hidden = true; byId("code-delete-confirm").hidden = true; show(byId("code-search-status"), "");
    byId("code-search-controls").disabled = !tokens;
  });
  byId("cancel-code-delete").addEventListener("click", () => { if (!deletingCode) byId("code-delete-confirm").hidden = true; });
  byId("confirm-code-delete").addEventListener("click", async () => {
    if (!tokens || !selectedCode?.canDelete || deletingCode) return;
    const current = generation, request = ++codeRequest, code = selectedCode, value = searchedCode;
    deletingCode = true; byId("code-search-controls").disabled = true;
    byId("confirm-code-delete").disabled = true; byId("cancel-code-delete").disabled = true;
    show(byId("code-search-status"), "正在删除卡密…");
    try {
      const reply = await authorized(adminApi.deleteCode, code.codeHash);
      if (generation !== current || request !== codeRequest) return;
      renderCode(reply); show(byId("code-search-status"), "卡密已删除，关联设备授权已作废；历史记录保留供查询。");
      if (selectedId && selectedId === code.license?.authorization.id) {
        selectedId = null; detailRequest++; byId("detail-panel").hidden = true;
      }
      latestCodes = latestCodes.filter(row => row.activationCode.trim() !== value);
      byId("activation-code").value = latestCodes.map(row => row.activationCode).join("\n");
      byId("result-count").textContent = latestCodes.length + " 个";
      byId("result").hidden = latestCodes.length === 0;
      listOffset = 0; await loadLicenses();
    } catch (error) {
      if (generation === current && request === codeRequest) { show(byId("code-search-status"), error.message, true); failed(error); }
    } finally {
      deletingCode = false; byId("confirm-code-delete").disabled = false; byId("cancel-code-delete").disabled = false;
      if (generation === current && request === codeRequest) byId("code-search-controls").disabled = !tokens;
    }
  });
  function durationChanged() { byId("days").disabled = byId("duration").value === "permanent"; }
  byId("duration").addEventListener("change", durationChanged);
  byId("edit-duration").addEventListener("change", () => {
    byId("edit-expiry").disabled = byId("edit-duration").value === "permanent";
  });
  async function loadLicenses() {
    const current = generation, request = ++listRequest;
    show(byId("list-status"), "正在读取授权…");
    try {
      const [rows, countReply] = await Promise.all([authorized(adminApi.licenses, listOffset), authorized(adminApi.licenseCount)]);
      if (current !== generation || request !== listRequest) return;
      const list = byId("license-list"); list.replaceChildren();
      for (const row of rows) {
        const e = row.authorization, button = node("button", undefined, "license-row"); button.type = "button";
        button.append(node("strong", e.note || "未设置备注"), node("span", "授权 " + e.id, "identifier"),
          node("span", expiryText(e.validUntil) + " · 在用设备 " + row.activeDevices + "/" + e.deviceLimit + " · 累计激活 " + row.activationsUsed + "/" + limitText(e.activationLimit), "row-meta"));
        button.addEventListener("click", () => loadDetail(e.id)); list.append(button);
      }
      const total = countReply.count, page = Math.floor(listOffset / 50) + 1, pages = Math.max(1, Math.ceil(total / 50));
      const first = total === 0 ? 0 : listOffset + 1, last = Math.min(listOffset + rows.length, total);
      show(byId("list-status"), total ? "共 " + total + " 条已兑换授权" : "暂无已兑换授权。用户首次激活后会出现在这里。");
      byId("page-label").textContent = "共 " + total + " 条 · " + first + "–" + last + " · 第 " + page + "/" + pages + " 页";
      byId("previous-page").disabled = listOffset === 0; byId("next-page").disabled = listOffset + rows.length >= total;
    } catch (error) { if (current === generation && request === listRequest) { show(byId("list-status"), error.message, true); failed(error); } }
  }
  function renderDetail(reply) {
    const e = reply.authorization; selectedId = e.id;
    byId("delete-confirm").hidden = true; byId("delete-license").disabled = deleting;
    byId("detail-panel").hidden = false; byId("license-id").textContent = "授权 " + e.id;
    byId("edit-note").value = e.note; byId("edit-devices").value = e.deviceLimit;
    byId("edit-activations").value = e.activationLimit == null ? "" : e.activationLimit;
    byId("edit-duration").value = reply.permanent ? "permanent" : "limited";
    byId("edit-expiry").disabled = reply.permanent;
    if (e.validUntil != null) {
      const date = new Date(e.validUntil * 1000);
      byId("edit-expiry").value = new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    } else byId("edit-expiry").value = "";
    byId("usage").textContent = "已用激活次数：" + reply.activationsUsed + "；剩余：" + (e.activationLimit == null ? "不限" : Math.max(0, e.activationLimit - reply.activationsUsed)) + "。重复激活同一设备不扣次数，撤销不返还。";
    const reset = byId("reset-activations"); reset.onclick = async () => { if (!confirm("确认重置累计激活次数？")) return; try { await authorized(adminApi.resetActivations, e.id); show(byId("detail-status"), "激活额度已重置。请刷新详情。"); await loadDetail(e.id); } catch (error) { show(byId("detail-status"), error.message, true); } };
    byId("edit-controls").disabled = deleting;
    const list = byId("device-list"); list.replaceChildren();
    if (!reply.devices.length) list.append(node("p", "暂无设备。"));
    for (const d of reply.devices) {
      const row = node("div", undefined, "device-row");
      row.append(node("strong", d.name), node("span", "设备识别码 " + d.keyHash.slice(0, 16).toUpperCase(), "identifier"),
        node("span", "激活时间：" + expiryText(d.enrolledAt) + " · " + (d.revokedAt == null ? "已绑定" : "已撤销"), "row-meta"));
      const actions = node("div", undefined, "device-actions"), input = node("input");
      input.value = d.note; input.maxLength = 500; input.placeholder = "设备备注"; input.setAttribute("aria-label", d.name + "的备注");
      const save = node("button", "保存备注"); save.type = "button";
      save.addEventListener("click", () => deviceAction(e.id, d.id, "note", {note: input.value}, save));
      const toggle = node("button", d.revokedAt == null ? "撤销设备" : "恢复设备"); toggle.type = "button";
      let revokeConfirmed = false;
      toggle.addEventListener("click", () => {
        if (d.revokedAt == null && !revokeConfirmed) {
          revokeConfirmed = true; toggle.textContent = "确认撤销这台设备";
          show(byId("detail-status"), "请再次点击确认撤销。下次联网验证会停用授权功能；当前版本离线票据最长有效 30 天；旧版本已签发票据可能按原期限继续有效（最长 90 天），累计次数不返还。");
          return;
        }
        deviceAction(e.id, d.id, d.revokedAt == null ? "revoke" : "restore", {}, toggle);
      });
      actions.append(input, save, toggle); row.append(actions); list.append(row);
    }
  }
  async function loadDetail(id) {
    const current = generation, request = ++detailRequest; selectedId = id;
    byId("delete-confirm").hidden = true; byId("delete-license").disabled = true;
    byId("detail-panel").hidden = false; byId("edit-controls").disabled = true; byId("device-list").replaceChildren();
    show(byId("detail-status"), "正在读取设备…");
    try {
      const reply = await authorized(adminApi.license, id);
      if (current !== generation || request !== detailRequest || selectedId !== id) return;
      renderDetail(reply); show(byId("detail-status"), "");
    } catch (error) { if (current === generation && request === detailRequest) { show(byId("detail-status"), error.message, true); failed(error); } }
  }
  async function deviceAction(id, deviceId, action, body, button) {
    const current = generation; button.disabled = true;
    try {
      await authorized(adminApi.device, id, deviceId, action, body);
      if (current !== generation) return;
      if (selectedId === id) await loadDetail(id); await loadLicenses();
    } catch (error) { if (current === generation) { show(byId("detail-status"), error.message, true); failed(error); } }
    finally { button.disabled = false; }
  }
  byId("edit-license").addEventListener("submit", async event => {
    event.preventDefault(); if (!selectedId || !tokens || deleting) return;
    const current = generation, id = selectedId, permanent = byId("edit-duration").value === "permanent";
    const deviceLimit = Number(byId("edit-devices").value), rawLimit = byId("edit-activations").value;
    const body = {deviceLimit, permanent, note: byId("edit-note").value};
    if (rawLimit !== "") body.activationLimit = Number(rawLimit);
    if (!permanent) {
      body.validUntil = Math.floor(new Date(byId("edit-expiry").value).getTime() / 1000);
      if (!Number.isSafeInteger(body.validUntil) || body.validUntil < 1) { show(byId("detail-status"), "请填写有效的到期时间。", true); return; }
    }
    byId("edit-controls").disabled = true; show(byId("detail-status"), "正在保存…");
    try {
      const reply = await authorized(adminApi.updateLicense, id, body);
      if (current !== generation || selectedId !== id) return;
      renderDetail(reply); show(byId("detail-status"), "已保存。"); await loadLicenses();
    } catch (error) { if (current === generation && selectedId === id) { show(byId("detail-status"), error.message, true); failed(error); } }
    finally { if (current === generation && selectedId === id) byId("edit-controls").disabled = !tokens; }
  });
  byId("reload-licenses").addEventListener("click", loadLicenses);
  byId("toggle-licenses").addEventListener("click", () => {
    const content = byId("licenses-content"), expanded = content.hidden;
    content.hidden = !expanded;
    byId("toggle-licenses").textContent = expanded ? "收起列表" : "展开列表";
    byId("toggle-licenses").setAttribute("aria-expanded", String(expanded));
  });
  byId("previous-page").addEventListener("click", () => { listOffset = Math.max(0, listOffset - 50); loadLicenses(); });
  byId("next-page").addEventListener("click", () => { listOffset += 50; loadLicenses(); });
  byId("close-detail").addEventListener("click", () => { selectedId = null; detailRequest++; byId("detail-panel").hidden = true; });
  function closeDiagnostic() {
    diagnosticId = null; diagnosticReport = null; diagnosticDetailRequest++;
    byId("diagnostic-detail").hidden = true; byId("diagnostic-text").textContent = "";
    byId("diagnostic-metadata").replaceChildren(); show(byId("diagnostic-detail-status"), "");
  }
  async function loadDiagnostics() {
    if (!tokens) return;
    const current = generation, request = ++diagnosticRequest;
    const device = byId("diagnostic-device").value.trim(), version = byId("diagnostic-version").value.trim();
    if (!/^[0-9a-f]{0,64}$/i.test(device)) { show(byId("diagnostic-status"), "设备编号只能包含 0–9、A–F。", true); return; }
    show(byId("diagnostic-status"), "正在读取报告…");
    byId("diagnostic-next").disabled = true; byId("diagnostic-previous").disabled = true;
    try {
      const rows = await authorized(adminApi.diagnostics, diagnosticOffset, device, version);
      if (current !== generation || request !== diagnosticRequest) return;
      const list = byId("diagnostic-list"); list.replaceChildren();
      for (const row of rows) {
        const item = node("button", "", "license-row"); item.type = "button";
        item.append(node("strong", row.deviceNote || row.manufacturer + " " + row.model),
          node("span", row.deviceFingerprint + " · " + row.versionName + " (" + row.versionCode + ")", "row-meta"),
          node("span", "固件 " + row.firmware + " · " + new Date(row.receivedAt * 1000).toLocaleString("zh-CN"), "row-meta"),
          node("span", "报告 " + row.id + " · " + Math.ceil(row.sizeBytes / 1024) + " KiB" + (row.truncated ? " · 部分历史省略" : ""), "row-meta"));
        item.addEventListener("click", () => openDiagnostic(row.id)); list.append(item);
      }
      show(byId("diagnostic-status"), rows.length ? "点击报告查看详情。" : "暂无符合条件的报告。请让用户在新版 App 的「诊断」中上传。");
      byId("diagnostic-page").textContent = "第 " + (diagnosticOffset / 50 + 1) + " 页";
      byId("diagnostic-previous").disabled = diagnosticOffset === 0; byId("diagnostic-next").disabled = rows.length < 50;
    } catch (error) { if (current === generation && request === diagnosticRequest) { show(byId("diagnostic-status"), error.message, true); failed(error); } }
  }
  async function openDiagnostic(id) {
    if (!tokens) return;
    closeDiagnostic(); diagnosticId = id;
    const current = generation, request = ++diagnosticDetailRequest;
    byId("diagnostic-detail").hidden = false; show(byId("diagnostic-detail-status"), "正在读取报告内容…");
    try {
      const detail = await authorized(adminApi.diagnostic, id);
      if (current !== generation || request !== diagnosticDetailRequest || diagnosticId !== id) return;
      diagnosticReport = detail; const row = detail.summary;
      byId("diagnostic-metadata").replaceChildren(
        node("p", "报告编号：" + row.id, "identifier"),
        node("p", "设备：" + row.deviceFingerprint + (row.deviceNote ? " · " + row.deviceNote : "")),
        node("p", row.manufacturer + " " + row.model + " · Android " + row.androidRelease + " / API " + row.sdk),
        node("p", "版本：" + row.versionName + " (" + row.versionCode + ") · 固件：" + row.firmware),
        node("p", "上传于 " + new Date(row.receivedAt * 1000).toLocaleString("zh-CN") + " · " + Math.ceil(row.sizeBytes / 1024) + " KiB"),
        node("p", "SHA-256：" + detail.reportSha256, "identifier"));
      // Device reports are untrusted text, including angle brackets and HTML-like log messages.
      byId("diagnostic-text").textContent = detail.report;
      show(byId("diagnostic-detail-status"), row.truncated ? "报告保留了最新日志，完整历史可让用户保存到本地。" : "");
    } catch (error) { if (current === generation && request === diagnosticDetailRequest) { show(byId("diagnostic-detail-status"), error.message, true); failed(error); } }
  }
  byId("diagnostic-search-form").addEventListener("submit", event => { event.preventDefault(); diagnosticOffset = 0; closeDiagnostic(); loadDiagnostics(); });
  byId("reload-diagnostics").addEventListener("click", loadDiagnostics);
  byId("diagnostic-previous").addEventListener("click", () => { diagnosticOffset = Math.max(0, diagnosticOffset - 50); loadDiagnostics(); });
  byId("diagnostic-next").addEventListener("click", () => { diagnosticOffset += 50; loadDiagnostics(); });
  byId("diagnostic-close").addEventListener("click", closeDiagnostic);
  byId("diagnostic-copy").addEventListener("click", async () => {
    if (!tokens || !diagnosticReport) return;
    const current = generation, id = diagnosticId;
    try { await navigator.clipboard.writeText(diagnosticReport.report); if (current === generation && diagnosticId === id) show(byId("diagnostic-detail-status"), "报告已复制。"); }
    catch (_) { if (current === generation && diagnosticId === id) show(byId("diagnostic-detail-status"), "复制未完成，请下载 TXT。", true); }
  });
  byId("diagnostic-download").addEventListener("click", () => {
    if (!tokens || !diagnosticReport) return;
    const url = URL.createObjectURL(new Blob([diagnosticReport.report], {type: "text/plain;charset=utf-8"}));
    const link = document.createElement("a"); link.href = url; link.download = "Autunn-Play-diagnostic-" + diagnosticId + ".txt"; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });
  function viewChanged() {
    const updates = window.location?.hash === "#updates";
    const diagnostics = window.location?.hash === "#diagnostics";
    byId("licenses-view").hidden = updates || diagnostics; byId("updates-view").hidden = !updates;
    byId("diagnostics-view").hidden = !diagnostics;
    byId("page-title").textContent = updates ? "应用更新" : diagnostics ? "诊断报告" : "授权管理";
    for (const [id, current] of [["updates-nav", updates], ["licenses-nav", !updates && !diagnostics], ["diagnostics-nav", diagnostics]]) {
      if (current) byId(id).setAttribute("aria-current", "page"); else byId(id).removeAttribute("aria-current");
    }
    if (diagnostics && tokens) loadDiagnostics();
  }
  window.addEventListener("hashchange", viewChanged); viewChanged();
  byId("delete-license").addEventListener("click", () => { if (!deleting) byId("delete-confirm").hidden = false; });
  byId("cancel-delete").addEventListener("click", () => { if (!deleting) byId("delete-confirm").hidden = true; });
  byId("confirm-delete").addEventListener("click", async () => {
    if (!tokens || !selectedId || deleting) return;
    const current = generation, id = selectedId; deleting = true;
    byId("confirm-delete").disabled = true; byId("delete-license").disabled = true; byId("edit-controls").disabled = true;
    show(byId("detail-status"), "正在删除授权…");
    try {
      await authorized(adminApi.deleteLicense, id);
      if (generation !== current) return;
      if (selectedId === id) { selectedId = null; detailRequest++; byId("detail-panel").hidden = true; }
      show(status, "授权已删除，旧兑换码和设备续期已停用。"); listOffset = 0; await loadLicenses();
    } catch (error) { if (generation === current) { show(byId("detail-status"), error.message, true); failed(error); } }
    finally {
      deleting = false; byId("confirm-delete").disabled = false; byId("delete-license").disabled = false;
      if (generation === current && selectedId) byId("edit-controls").disabled = !tokens;
    }
  });
  const sizeText = bytes => (bytes / (1024 * 1024)).toFixed(2) + " MiB";
  function releaseCard(target, release, heading) {
    target.replaceChildren(node("h3", heading));
    if (!release) { target.append(node("p", "尚未发布版本。")); return; }
    target.append(node("p", release.versionName + " · versionCode " + release.versionCode),
      node("p", release.packageName + " · 最低 Android SDK " + release.minSdk + " · " + sizeText(release.sizeBytes), "row-meta"),
      node("p", "SHA-256：" + release.sha256, "identifier"), node("p", release.releaseNotes || "未填写更新说明", "release-notes"));
  }
  function selectedUpdate() {
    const app = updateApps.find(a => a.packageName === byId("update-app").value);
    releaseCard(byId("current-release"), app?.current, "当前发布版本");
    byId("upload-submit").disabled = !app?.uploadEnabled || uploading;
    if (app && !app.uploadEnabled) show(byId("upload-status"), messages.UPDATE_NOT_CONFIGURED, true);
    else if (!uploading) show(byId("upload-status"), "选择 APK 并确认后即可发布。");
  }
  async function loadUpdates() {
    if (!tokens || uploading) return;
    const current = generation, request = ++updateRequest; byId("reload-updates").disabled = true;
    try {
      const apps = await authorized(adminApi.updates);
      if (generation !== current || request !== updateRequest) return;
      const selected = byId("update-app").value; updateApps = apps;
      byId("update-app").replaceChildren();
      for (const app of apps) { const option = node("option", app.packageName); option.value = app.packageName; byId("update-app").append(option); }
      byId("update-app").value = apps.some(a => a.packageName === selected) ? selected : apps[0]?.packageName || "";
      byId("upload-controls").disabled = !apps.length; selectedUpdate();
    } catch (error) { if (current === generation && request === updateRequest) { show(byId("upload-status"), error.message, true); failed(error); } }
    finally { if (current === generation && request === updateRequest) byId("reload-updates").disabled = !tokens; }
  }
  function renderUpdateSettings() {
    const input = byId("update-header-validation");
    input.checked = updateHeaderEnabled === true; input.indeterminate = updateHeaderEnabled === null;
    input.disabled = !tokens || updateHeaderEnabled === null || updateSettingsBusy;
    byId("update-header-state").textContent = updateHeaderEnabled === null ? "（状态待确认）" : updateHeaderEnabled ? "（已开启）" : "（已关闭）";
  }
  function headerSetting(reply) {
    if (typeof reply?.headerValidationEnabled !== "boolean") throw new Error("服务器返回的更新请求头设置异常，请刷新后重试。");
    return reply.headerValidationEnabled;
  }
  async function loadUpdateSettings() {
    if (!tokens || updateSettingsBusy) return;
    const current = generation, request = ++updateSettingsRequest;
    updateHeaderEnabled = null; updateSettingsBusy = true; renderUpdateSettings();
    show(byId("update-settings-status"), "正在读取请求头验证设置…");
    try {
      const bearer = await access(); if (current !== generation || request !== updateSettingsRequest) return;
      const reply = await api(() => adminApi.updateSettings(bearer));
      if (current !== generation || request !== updateSettingsRequest) return;
      updateHeaderEnabled = headerSetting(reply); show(byId("update-settings-status"), "已读取服务端设置，可点击切换。");
    } catch (error) {
      if (current === generation && request === updateSettingsRequest) {
        show(byId("update-settings-status"), "当前状态尚未确认，请刷新后重试。" + error.message, true); failed(error);
      }
    } finally {
      if (current === generation && request === updateSettingsRequest) { updateSettingsBusy = false; renderUpdateSettings(); }
    }
  }
  byId("update-header-validation").addEventListener("change", async () => {
    if (!tokens || updateSettingsBusy || updateHeaderEnabled === null) { renderUpdateSettings(); return; }
    const current = generation, request = ++updateSettingsRequest, enabled = byId("update-header-validation").checked;
    let confirmAfterFailure = false;
    updateSettingsBusy = true; byId("update-header-validation").disabled = true;
    show(byId("update-settings-status"), enabled ? "正在开启请求头验证…" : "正在关闭请求头验证…");
    try {
      const bearer = await access(); if (current !== generation || request !== updateSettingsRequest) return;
      const reply = await api(() => adminApi.saveUpdateSettings(bearer, {headerValidationEnabled: enabled}));
      if (current !== generation || request !== updateSettingsRequest) return;
      updateHeaderEnabled = headerSetting(reply);
      show(byId("update-settings-status"), updateHeaderEnabled ? "已保存，请求头验证已开启。" : "已保存，请求头验证已关闭。");
    } catch (error) {
      if (current === generation && request === updateSettingsRequest) {
        updateHeaderEnabled = null; renderUpdateSettings();
        show(byId("update-settings-status"), "保存结果尚未确认，正在重新读取服务端设置…", true);
        failed(error); confirmAfterFailure = current === generation && !!tokens;
      }
    } finally {
      if (current === generation && request === updateSettingsRequest) {
        updateSettingsBusy = false; renderUpdateSettings();
        if (confirmAfterFailure) await loadUpdateSettings();
      }
    }
  });
  byId("reload-updates").addEventListener("click", () => { loadUpdates(); loadUpdateSettings(); });
  byId("update-app").addEventListener("change", selectedUpdate);
  byId("update-apk").addEventListener("change", () => {
    const file = byId("update-apk").files[0]; byId("publish-confirm").checked = false;
    show(byId("file-summary"), file ? file.name + " · " + sizeText(file.size) : "支持单个完整 Release APK，最大 512 MiB。", file && file.size > 512 * 1024 * 1024);
  });
  byId("upload-form").addEventListener("submit", async event => {
    event.preventDefault(); if (!tokens || uploading) return;
    const file = byId("update-apk").files[0], app = updateApps.find(a => a.packageName === byId("update-app").value);
    if (!file || !/\.apk$/i.test(file.name) || file.size < 1 || file.size > 512 * 1024 * 1024 || !app?.uploadEnabled || !byId("publish-confirm").checked) {
      show(byId("upload-status"), "请选择不超过 512 MiB 的 APK，并勾选发布确认。", true); return;
    }
    const current = generation; uploading = true; byId("upload-controls").disabled = true; byId("reload-updates").disabled = true;
    byId("upload-progress-panel").hidden = false; byId("upload-progress").value = 0; byId("published-release").hidden = true;
    show(byId("upload-status"), "正在准备上传…");
    let completed;
    try {
      const bearer = await access(); if (current !== generation) return;
      const data = new FormData(); data.append("apk", file); data.append("releaseNotes", byId("release-notes").value);
      uploadRequest = adminApi.publishUpdate(bearer, app.packageName, data, event => {
        if (current !== generation || !event.lengthComputable) return;
        const percent = Math.min(100, Math.round(event.loaded / event.total * 100));
        byId("upload-progress").value = percent;
        show(byId("upload-status"), percent === 100 ? "上传完成，服务器正在校验并发布，请稍候…" : "正在上传 " + percent + "%…");
      });
      completed = await api(() => uploadRequest.done, "发布未完成，请刷新发布状态后再重试。");
      if (current !== generation) return;
      releaseCard(byId("published-release"), completed, "发布成功"); byId("published-release").hidden = false;
      byId("publish-confirm").checked = false; byId("update-apk").value = "";
    } catch (error) { if (current === generation) { show(byId("upload-status"), error.message, true); failed(error); } }
    finally {
      uploading = false; uploadRequest = null;
      if (current === generation) {
        byId("upload-progress-panel").hidden = true; byId("upload-controls").disabled = !tokens; byId("reload-updates").disabled = !tokens;
        if (completed) { await loadUpdates(); if (current === generation) show(byId("upload-status"), "新版已发布，用户可在 App 的「检查更新」中获取。"); }
      }
    }
  });
  byId("cancel-upload").addEventListener("click", () => { if (uploadRequest) uploadRequest.abort(); });
  window.addEventListener("pagehide", () => signedOut("", false, false));
  renderUpdateSettings();
  restoreSession();
})();
