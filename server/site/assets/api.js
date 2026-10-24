"use strict";
(() => {
  const appId = "com.shihab.diplay";
  const licensePath = id => "/v1/admin/licenses/" + encodeURIComponent(id);

  class ApiError extends Error {
    constructor(code, status = 0) {
      super("Request failed");
      this.code = code;
      this.status = status;
    }
  }

  async function json(path, {method = "GET", body, bearer, timeout = 20000, credentials = "omit", allowMissing = false, maxCharacters} = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    const headers = {Accept: "application/json"};
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (bearer) headers.Authorization = "Bearer " + bearer;
    try {
      const response = await fetch(path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        credentials, cache: "no-store", redirect: "error", signal: controller.signal});
      if (allowMissing && response.status === 404) return null;
      let reply;
      if (maxCharacters !== undefined) {
        const text = await response.text();
        if (text.length > maxCharacters) throw new ApiError("RESPONSE_TOO_LARGE", response.status);
        reply = JSON.parse(text);
      } else reply = await response.json();
      if (!response.ok) throw new ApiError(reply?.error, response.status);
      return reply;
    } finally { clearTimeout(timer); }
  }

  const post = (path, body, options) => json(path, {...options, method: "POST", body});
  const downloadJson = path => json(path, {timeout: 8000, credentials: "same-origin", allowMissing: true, maxCharacters: 131072});

  function publishUpdate(bearer, packageName, body, onProgress) {
    const xhr = new XMLHttpRequest();
    const done = new Promise((resolve, reject) => {
      xhr.open("POST", "/v1/admin/updates/" + encodeURIComponent(packageName) + "/stable");
      xhr.timeout = 15 * 60 * 1000;
      xhr.setRequestHeader("Authorization", "Bearer " + bearer);
      xhr.setRequestHeader("Accept", "application/json");
      xhr.upload.onprogress = onProgress;
      xhr.onload = () => {
        let reply;
        try { reply = JSON.parse(xhr.responseText); }
        catch (_) { reject(new ApiError("UPLOAD_RESPONSE", xhr.status)); return; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(reply);
        else reject(new ApiError(reply?.error, xhr.status));
      };
      xhr.onerror = xhr.ontimeout = () => reject(new ApiError("UPLOAD_CONNECTION"));
      xhr.onabort = () => reject(new ApiError("UPLOAD_ABORTED"));
      xhr.send(body);
    });
    return {done, abort: () => xhr.abort()};
  }

  globalThis.AutunnApi = Object.freeze({
    ApiError,
    login: {
      start: () => post("/v1/admin-login/start", {}),
      poll: deviceCode => post("/v1/admin-login/poll", {deviceCode}),
      refresh: refreshToken => post("/v1/admin-login/refresh", {refreshToken})
    },
    admin: {
      me: bearer => json("/v1/admin/me", {bearer}),
      createCode: (bearer, grant) => post("/v1/admin/activation-codes", grant, {bearer}),
      createCodes: (bearer, count, grant) => post("/v1/admin/activation-codes/batch", {count, grant}, {bearer}),
      searchCode: (bearer, activationCode) => post("/v1/admin/activation-codes/search", {activationCode}, {bearer}),
      deleteCode: (bearer, codeHash) => json("/v1/admin/activation-codes/" + encodeURIComponent(codeHash), {bearer, method: "DELETE"}),
      licenses: (bearer, offset) => json("/v1/admin/license-summaries?offset=" + offset + "&limit=50", {bearer}),
      licenseCount: bearer => json("/v1/admin/license-count", {bearer}),
      license: (bearer, id) => json(licensePath(id), {bearer}),
      updateLicense: (bearer, id, body) => json(licensePath(id), {bearer, method: "PUT", body}),
      deleteLicense: (bearer, id) => json(licensePath(id), {bearer, method: "DELETE"}),
      resetActivations: (bearer, id) => json(licensePath(id) + "/reset-activations", {bearer, method: "POST"}),
      device: (bearer, id, deviceId, action, body) => json(licensePath(id) + "/devices/" + encodeURIComponent(deviceId) + "/" + action,
        {bearer, method: action === "note" ? "PUT" : "POST", body}),
      diagnostics: (bearer, offset, device, version) => json("/v1/admin/diagnostics?offset=" + offset + "&limit=50&device=" + encodeURIComponent(device) + "&version=" + encodeURIComponent(version), {bearer}),
      diagnostic: (bearer, id) => json("/v1/admin/diagnostics/" + encodeURIComponent(id), {bearer}),
      updates: bearer => json("/v1/admin/updates", {bearer}),
      updateSettings: bearer => json("/v1/admin/updates/settings", {bearer}),
      saveUpdateSettings: (bearer, body) => json("/v1/admin/updates/settings", {bearer, method: "PUT", body}),
      publishUpdate
    },
    activation: {
      details: browserToken => post("/v1/activation/details", {browserToken}, {timeout: 15000}),
      approve: (browserToken, activationCode) => post("/v1/activation/approve", {browserToken, activationCode}, {timeout: 15000})
    },
    downloads: {
      appId,
      catalog: () => downloadJson("./downloads.json")
    }
  });
})();
