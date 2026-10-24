"use strict";
(() => {
  const downloadsApi = AutunnApi.downloads;
  const appId = downloadsApi.appId;
  const packages = { app: null, firmware: null };
  const appUpdateHelp = "请在车机内激活授权码后检查更新。首次安装请联系提供方获取安装包。";
  const byId = id => document.getElementById(id);
  let requestGeneration = 0;
  let toastTimer;
  const sizeLabel = bytes => bytes >= 1e9 ? `${(bytes / 1e9).toFixed(2)} GB` : `${(bytes / 1e6).toFixed(2)} MB`;
  const validText = (text, maximum) => typeof text === "string" && text.length > 0 && text.length <= maximum;

  function validatePackage(value, kind) {
    if (!value || !validText(value.versionName, 100) || !Number.isSafeInteger(value.sizeBytes) || value.sizeBytes <= 0
      || !/^[a-f0-9]{64}$/.test(value.sha256) || typeof value.releaseNotes !== "string" || value.releaseNotes.length > 20000) {
      throw new Error("Invalid package metadata");
    }
    if (kind === "app" && (value.packageName !== appId || !Number.isSafeInteger(value.versionCode) || value.versionCode <= 0
      || !Number.isSafeInteger(value.minSdk) || value.minSdk < 28 || value.minSdk > 100 || value.sizeBytes > 536870912)) {
      throw new Error("Invalid app metadata");
    }
    if (kind === "firmware" && !validText(value.model, 100)) throw new Error("Invalid firmware model");
    const file = { ...value, downloadUrl: null };
    if (kind === "firmware" && value.downloadUrl != null) {
      // Only explicitly staged, same-origin firmware can become a public download.
      if (!validText(value.downloadUrl, 512)) throw new Error("Invalid download URL");
      const url = new URL(value.downloadUrl, document.baseURI);
      const folder = new URL("./downloads/", document.baseURI).pathname;
      const name = url.pathname.slice(folder.length);
      if (url.origin !== location.origin || !url.pathname.startsWith(folder) || !/^[A-Za-z0-9_.-]+$/.test(name)
        || !name.endsWith(".zip") || url.search || url.hash || url.username || url.password) throw new Error("Invalid download URL");
      file.downloadUrl = url.href;
    }
    return file;
  }

  function setDownload(link, file) {
    const enabled = Boolean(file && file.downloadUrl);
    link.setAttribute("aria-disabled", String(!enabled));
    if (enabled) {
      link.href = file.downloadUrl;
      link.setAttribute("download", file.fileName || "");
      link.removeAttribute("tabindex");
    } else { link.removeAttribute("href"); link.removeAttribute("download"); link.setAttribute("tabindex", "-1"); }
  }

  function render(kind) {
    const file = packages[kind];
    byId(`${kind}-version`).textContent = file ? file.versionName : (kind === "app" ? "车机内查看" : "暂无已发布版本");
    byId(`${kind}-size`).textContent = file ? sizeLabel(file.sizeBytes) : "—";
    setDownload(byId(`${kind}-download`), file);
    byId(`${kind}-download`).querySelector("span").textContent = file && file.downloadUrl
      ? "下载完整升级包" : (kind === "app" ? "请在车机内授权后更新" : "下载暂未开放");
    if (kind === "app") {
      byId("app-tag").textContent = "授权后更新";
      const android = { 28: "9.0", 29: "10", 30: "11", 31: "12", 32: "12L", 33: "13", 34: "14", 35: "15", 36: "16" };
      byId("app-system").textContent = file ? (android[file.minSdk] ? `Android ${android[file.minSdk]}+` : `Android API ${file.minSdk}+`) : "Android 9.0+";
    } else { byId("firmware-model").textContent = file ? file.model : "—"; }
    document.querySelector(`[data-copy-hash="${kind}"]`).disabled = !file;
  }

  async function loadDownloads() {
    const generation = ++requestGeneration;
    const refresh = byId("refresh-downloads");
    refresh.disabled = true;
    byId("download-status").textContent = "正在获取下载信息…";
    const [catalogResult] = await Promise.allSettled([downloadsApi.catalog()]);
    if (generation !== requestGeneration) return;
    packages.app = null;
    packages.firmware = null;
    let metadataError = false;
    if (catalogResult.status === "fulfilled" && catalogResult.value) {
      const catalog = catalogResult.value;
      if (catalog.schema === 1) {
        for (const kind of ["app", "firmware"]) {
          if (catalog[kind]) {
            try { packages[kind] = validatePackage(catalog[kind], kind); } catch (_) { metadataError = true; }
          }
        }
      } else { metadataError = true; }
    } else if (catalogResult.status === "rejected") { metadataError = true; }
    render("app"); render("firmware");
    byId("download-status").textContent = appUpdateHelp + (packages.firmware?.downloadUrl ? " 车机固件可下载。"
      : metadataError ? " 暂时无法获取固件信息，请稍后刷新。" : " 固件下载暂未开放。");
    refresh.disabled = false;
  }

  function filter(kind) {
    document.querySelectorAll("[data-filter]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.filter === kind)));
    document.querySelectorAll("[data-download-kind]").forEach(card => { card.hidden = kind !== "all" && card.dataset.downloadKind !== kind; });
  }
  function openDetails(kind) {
    const file = packages[kind];
    byId("dialog-kind").textContent = kind === "app" ? "APP / RELEASE NOTES" : "FIRMWARE / PACKAGE INFO";
    byId("dialog-title").textContent = kind === "app" ? "Autunn Play 更新说明" : "HS5 升级包说明";
    byId("dialog-meta").textContent = file ? `${file.versionName} · ${sizeLabel(file.sizeBytes)}` : "暂无已发布版本";
    byId("dialog-notes").textContent = kind === "app" ? appUpdateHelp + (file?.releaseNotes ? "\n\n" + file.releaseNotes : "")
      : file?.releaseNotes || "下载信息暂未开放，请稍后刷新。";
    byId("dialog-hash").textContent = file?.sha256 || "—";
    setDownload(byId("dialog-download"), file);
    byId("dialog-download").textContent = kind === "app" ? "请在车机内授权后更新" : file?.downloadUrl ? "下载文件 ↓" : "下载暂未开放";
    byId("package-dialog").showModal();
  }
  function toast(message) {
    byId("toast").textContent = message;
    byId("toast").hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { byId("toast").hidden = true; }, 3500);
  }
  document.querySelectorAll("[data-filter]").forEach(button => button.addEventListener("click", () => filter(button.dataset.filter)));
  document.querySelectorAll("[data-filter-link]").forEach(link => link.addEventListener("click", () => filter(link.dataset.filterLink)));
  document.querySelectorAll("[data-open-details]").forEach(button => button.addEventListener("click", () => openDetails(button.dataset.openDetails)));
  document.querySelectorAll("[data-copy-hash]").forEach(button => button.addEventListener("click", async () => {
    const kind = button.dataset.copyHash;
    if (!packages[kind]) return;
    try { await navigator.clipboard.writeText(packages[kind].sha256); toast("SHA-256 已复制"); }
    catch (_) { openDetails(kind); toast("请在说明中选择校验值，手动复制"); }
  }));
  byId("refresh-downloads").addEventListener("click", loadDownloads);
  byId("dialog-close").addEventListener("click", () => byId("package-dialog").close());
  byId("package-dialog").addEventListener("click", event => {
    if (event.target === byId("package-dialog")) {
      const bounds = event.target.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) event.target.close();
    }
  });
  loadDownloads();
})();
