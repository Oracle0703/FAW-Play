'use strict';
const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const apiSource = fs.readFileSync(path.join(__dirname, '../../site/assets/api.js'), 'utf8');
const activationSource = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/activate/activate.js'), 'utf8');
const siteSource = fs.readFileSync(path.join(__dirname, '../../site/assets/site.js'), 'utf8');

function fixture(respond, additions = {}) {
  const requests = [], timers = new Map(), cleared = [];
  let timerId = 0;
  const context = vm.createContext({AbortController, Error, TypeError, SyntaxError, URLSearchParams,
    setTimeout(callback, delay) { const id = ++timerId; timers.set(id, {callback, delay}); return id; },
    clearTimeout(id) { cleared.push(id); timers.delete(id); },
    fetch: async (url, options) => { requests.push({url, options}); return respond(url, options); }, ...additions});
  vm.runInContext(apiSource, context);
  return {api: context.AutunnApi, context, requests, timers, cleared};
}
const reply = (body, status = 200) => new Response(JSON.stringify(body), {status});

test('admin and activation credentials stay in headers or JSON bodies with redirects disabled', async () => {
  const page = fixture(() => reply({}));
  await page.api.admin.searchCode('access-secret', 'FAW-private-code');
  await page.api.activation.approve('browser-secret', 'FAW-private-code');
  await page.api.login.refresh('refresh-secret');
  for (const {url, options} of page.requests) {
    assert.ok(!/secret|private/.test(url));
    assert.equal(options.method, 'POST');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.redirect, 'error');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.headers['Content-Type'], 'application/json');
  }
  assert.equal(page.requests[0].options.headers.Authorization, 'Bearer access-secret');
  assert.equal(page.requests[1].options.headers.Authorization, undefined);
  assert.equal(JSON.parse(page.requests[1].options.body).browserToken, 'browser-secret');
  assert.equal(JSON.parse(page.requests[2].options.body).refreshToken, 'refresh-secret');
  assert.equal(page.timers.size, 0);
});

test('encoded identifiers cannot change admin path segments and update operations retain their verbs', async () => {
  const page = fixture(() => reply({}));
  await page.api.admin.updateLicense('token', 'license/one', {deviceLimit: 2});
  await page.api.admin.device('token', 'license/one', 'device/two', 'note', {note: '备注'});
  await page.api.admin.deleteLicense('token', 'license/one');
  assert.equal(page.requests[0].url, '/v1/admin/licenses/license%2Fone');
  assert.equal(page.requests[1].url, '/v1/admin/licenses/license%2Fone/devices/device%2Ftwo/note');
  assert.deepEqual(page.requests.map(r => r.options.method), ['PUT', 'PUT', 'DELETE']);
  assert.equal(page.requests[2].options.body, undefined);
});

test('update header settings use authenticated GET and PUT with an exact boolean JSON setting', async () => {
  const page = fixture(() => reply({headerValidationEnabled: true, headerName: 'Autunn'}));
  assert.equal((await page.api.admin.updateSettings('admin-access')).headerValidationEnabled, true);
  assert.equal((await page.api.admin.saveUpdateSettings('admin-access', {headerValidationEnabled: false})).headerValidationEnabled, true);
  assert.deepEqual(page.requests.map(r => r.url), ['/v1/admin/updates/settings', '/v1/admin/updates/settings']);
  assert.deepEqual(page.requests.map(r => r.options.method), ['GET', 'PUT']);
  assert.equal(page.requests[0].options.body, undefined);
  assert.deepEqual(JSON.parse(page.requests[1].options.body), {headerValidationEnabled: false});
  for (const {options} of page.requests) {
    assert.equal(options.headers.Authorization, 'Bearer admin-access');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.redirect, 'error');
  }
});

test('missing public downloads stay optional while activation retains terminal HTTP status', async () => {
  const page = fixture(url => url.startsWith('/v1/activation/') ? reply({error: 'ACTIVATION_EXPIRED'}, 410) : reply({}, 404));
  assert.equal(await page.api.downloads.catalog(), null);
  await assert.rejects(page.api.activation.details('browser-secret'), error => error.code === 'ACTIVATION_EXPIRED' && error.status === 410);
  assert.equal(page.requests[0].options.credentials, 'same-origin');
  assert.equal(page.timers.size, 0);
});

test('download metadata limits reject oversized and malformed responses and release timers', async () => {
  let oversized = true;
  const page = fixture(() => new Response(oversized ? 'x'.repeat(131073) : 'not-json'));
  await assert.rejects(page.api.downloads.catalog(), error => error.code === 'RESPONSE_TOO_LARGE');
  oversized = false;
  await assert.rejects(page.api.downloads.catalog(), {name: 'SyntaxError'});
  assert.equal(page.timers.size, 0);
});

test('website never calls protected updates or exposes catalog APK links while firmware remains downloadable', async () => {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {textContent: '', disabled: false, attributes: {}, events: {},
      setAttribute(name, value) { this.attributes[name] = value; },
      removeAttribute(name) { delete this.attributes[name]; if (name === 'href') delete this.href; },
      addEventListener(name, callback) { this.events[name] = callback; },
      querySelector() { return element(id + '-span'); }, showModal() {}, close() {}});
    return elements.get(id);
  };
  const details = Object.assign(element('details-app'), {dataset: {openDetails: 'app'}});
  const catalog = {schema: 1, app: {packageName: 'com.shihab.diplay', versionCode: 1000, versionName: 'test', minSdk: 28,
    sizeBytes: 123, sha256: 'ab'.repeat(32), releaseNotes: 'notes', downloadUrl: './downloads/public.apk'},
    firmware: {versionName: '3222', model: 'hs5y22', sizeBytes: 456, sha256: 'cd'.repeat(32),
      releaseNotes: 'firmware notes', downloadUrl: './downloads/firmware.zip'}};
  const page = fixture(url => { assert.equal(url, './downloads.json'); return reply(catalog); }, {
    URL, location: {origin: 'https://example.test'}, document: {baseURI: 'https://example.test/', getElementById: element,
      querySelector: element, querySelectorAll: selector => selector === '[data-open-details]' ? [details] : []}});
  vm.runInContext(siteSource, page.context);
  for (let i = 0; i < 30; i++) await Promise.resolve();
  assert.equal(page.requests.length, 1);
  assert.equal(page.api.downloads.update, undefined);
  assert.equal(page.api.downloads.artifact, undefined);
  assert.equal(element('app-download').href, undefined);
  assert.equal(element('app-download').attributes['aria-disabled'], 'true');
  assert.equal(element('firmware-download').href, 'https://example.test/downloads/firmware.zip');
  details.events.click();
  assert.equal(element('dialog-download').href, undefined);
  assert.match(element('dialog-notes').textContent, /车机内激活授权码/);
});

test('activation timeout aborts the underlying request and is cleared after failure', async () => {
  const page = fixture((url, {signal}) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), {name: 'AbortError'})));
  }));
  const pending = page.api.activation.details('browser-secret');
  const timer = [...page.timers.values()][0];
  assert.equal(timer.delay, 15000);
  timer.callback();
  await assert.rejects(pending, {name: 'AbortError'});
  assert.equal(page.requests[0].options.signal.aborted, true);
  assert.equal(page.timers.size, 0);
});

test('APK upload preserves multipart data and progress, and its handle cancels the request', async () => {
  let xhr;
  class Upload {
    constructor() { xhr = this; this.headers = {}; this.upload = {}; }
    open(method, url) { this.method = method; this.url = url; }
    setRequestHeader(name, value) { this.headers[name] = value; }
    send(body) { this.body = body; }
    abort() { this.onabort(); }
  }
  const page = fixture(() => { throw new Error('Unexpected fetch'); }, {XMLHttpRequest: Upload});
  const progress = [], body = new FormData();
  body.append('releaseNotes', '发布说明');
  const upload = page.api.admin.publishUpdate('private-token', 'package/id', body, event => progress.push(event.loaded));
  assert.equal(xhr.method, 'POST');
  assert.equal(xhr.url, '/v1/admin/updates/package%2Fid/stable');
  assert.equal(xhr.headers.Authorization, 'Bearer private-token');
  assert.equal(xhr.headers['Content-Type'], undefined);
  assert.equal(xhr.timeout, 900000);
  assert.equal(xhr.body, body);
  xhr.upload.onprogress({loaded: 10, total: 20, lengthComputable: true});
  assert.deepEqual(progress, [10]);
  upload.abort();
  await assert.rejects(upload.done, error => error.code === 'UPLOAD_ABORTED');
});

function activationFixture(approveReply) {
  const elements = new Map(), token = 'a'.repeat(43), historyPaths = [];
  const getElementById = id => {
    if (!elements.has(id)) elements.set(id, {value: '', disabled: true, checked: false, textContent: '', events: {},
      addEventListener(name, action) { this.events[name] = action; }});
    return elements.get(id);
  };
  const page = fixture(url => url.endsWith('/details')
    ? reply({deviceName: 'HS5', deviceFingerprint: 'AABB', status: 'pending', expiresAt: Math.floor(Date.now() / 1000) + 300})
    : approveReply(), {document: {getElementById}, location: {hash: '#token=' + token, pathname: '/activate/'},
    history: {replaceState(state, title, pathname) { historyPaths.push(pathname); }}});
  vm.runInContext(activationSource, page.context);
  return {...page, element: getElementById, historyPaths,
    settle: async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); },
    submit: () => getElementById('activation-form').events.submit({preventDefault() {}})};
}

test('activation strips the URL token, clears successful codes, and prevents repeat submissions', async () => {
  const page = activationFixture(() => reply({status: 'activated'}));
  await page.settle();
  assert.deepEqual(page.historyPaths, ['/activate/']);
  page.element('code').value = ' FAW-private-code ';
  page.element('confirmed').checked = true;
  await page.submit();
  assert.equal(page.element('code').value, '');
  assert.equal(page.element('submit').disabled, true);
  await page.submit();
  assert.equal(page.requests.length, 2);
  assert.equal(JSON.parse(page.requests[1].options.body).activationCode, 'FAW-private-code');
});

test('expired activation permanently locks controls, while an invalid code permits retry', async () => {
  for (const [code, status, terminal] of [['ACTIVATION_EXPIRED', 410, true], ['INVALID_ACTIVATION_CODE', 400, false], ['ACTIVATION_ATTEMPTS', 400, true]]) {
    const page = activationFixture(() => reply({error: code}, status));
    await page.settle();
    page.element('code').value = 'FAW-code';
    page.element('confirmed').checked = true;
    await page.submit();
    assert.equal(page.element('submit').disabled, terminal);
    await page.submit();
    assert.equal(page.requests.length, terminal ? 2 : 3);
  }
});
