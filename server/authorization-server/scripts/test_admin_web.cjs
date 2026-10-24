'use strict';
const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../site/assets/api.js'), 'utf8') + '\n'
  + fs.readFileSync(path.join(__dirname, '../src/main/resources/static/admin/admin.js'), 'utf8');
const key = 'faw-play-admin-refresh-v1';
function fixture(storage = new Map(), refreshError, extraReply, settingsReply) {
  const elements = new Map(), windowEvents = {}, requests = [];
  let now = 1000, refreshCount = 0;
  function element(id) {
    if (!elements.has(id)) elements.set(id, {id, hidden:false, disabled:false, textContent:'', value:'', events:{}, children:[],
      classList:{toggle(){}}, addEventListener(name, action){this.events[name] = action;},
      replaceChildren(...children){this.children=children;}, removeAttribute(){}, append(...children){this.children.push(...children);}, setAttribute(){}, focus(){}, select(){}});
    return elements.get(id);
  }
  element('controls').disabled = true;
  const context = {document:{getElementById:element, createElement:tag=>element('created-' + tag + Math.random())},
    window:{location:{hash:""}, addEventListener:(name, action)=>windowEvents[name]=action},
    sessionStorage:{getItem:k=>storage.get(k)||null, setItem:(k,v)=>storage.set(k,v), removeItem:k=>storage.delete(k)},
    performance:{now:()=>now}, setTimeout:()=>1, clearTimeout(){}, AbortController, Intl, Date, console,
    fetch:async (url, options) => {
      requests.push({url, method:options.method, body:options.body && JSON.parse(options.body), headers:options.headers});
      let reply;
      if (url==='/v1/admin-login/refresh') reply = refreshError ? {error:refreshError} : {accessToken:'local-test-access-' + ++refreshCount, refreshToken:'rotated-test-refresh', expiresIn:300};
      else if (url==='/v1/admin/me') reply = {username:'test-admin', serverTime:1800000000};
      else if (url.startsWith('/v1/admin/license-summaries')) reply = [];
      else if (url==='/v1/admin/updates') reply = [];
      else if (url==='/v1/admin/updates/settings') reply = settingsReply ? await settingsReply(options) : {headerValidationEnabled:false,headerName:'Autunn'};
      else if (extraReply) reply = await extraReply(url, options);
      else throw new Error('Unexpected test request: ' + url);
      return {ok:!reply.error, status:reply.error==='UNAUTHORIZED'?401:reply.error==='FORBIDDEN'?403:reply.error?503:200, json:async()=>reply};
    }};
  vm.runInNewContext(source, context);
  const settle = async()=>{for(let i=0;i<60;i++)await Promise.resolve();};
  return {element, requests, windowEvents, settle, storage, window:context.window, advance:milliseconds=>now+=milliseconds};
}
test('refresh restores login through server verification and rotates the tab session token', async()=>{
  const storage = new Map([[key,'original-test-refresh']]);
  let page = fixture(storage); await page.settle();
  assert.equal(page.element('account').textContent,'test-admin'); assert.equal(page.element('controls').disabled,false);
  assert.equal(storage.get(key),'rotated-test-refresh');
  page.windowEvents.pagehide(); assert.equal(storage.has(key),true);
  page = fixture(storage); await page.settle();
  assert.equal(page.requests[0].url,'/v1/admin-login/refresh'); assert.equal(page.requests[0].body.refreshToken,'rotated-test-refresh');
  assert.equal(page.element('account').textContent,'test-admin');
});
test('explicit logout clears retained session, displayed code and device data before reload', async()=>{
  const storage=new Map([[key,'local-test-refresh']]); const page=fixture(storage); await page.settle();
  page.element('activation-code').value='LOCAL-TEST-CODE'; page.element('logout').events.click();
  assert.equal(storage.has(key),false); assert.equal(page.element('controls').disabled,true);
  assert.equal(page.element('activation-code').value,''); assert.equal(page.element('licenses-panel').hidden,true);
  const reloaded=fixture(storage); await reloaded.settle(); assert.equal(reloaded.requests.length,0);
});
test('expired refresh token never unlocks administration and is discarded', async()=>{
  const page=fixture(new Map([[key,'expired-test-refresh']]),'LOGIN_EXPIRED'); await page.settle();
  assert.equal(page.storage.has(key),false); assert.equal(page.element('controls').disabled,true);
  assert.equal(page.requests.length,1); assert.equal(page.element('account').textContent,'未登录');
});
test('temporary identity failure keeps the tab token for a retry while leaving controls locked', async()=>{
  const page=fixture(new Map([[key,'local-test-refresh']]),'LOGIN_UNAVAILABLE'); await page.settle();
  assert.equal(page.storage.has(key),true); assert.equal(page.element('controls').disabled,true);
  assert.equal(page.requests.length,1);
});
const exampleCode = {codeHash:'a'.repeat(64),status:'unused',deviceLimit:1,activationLimit:1,validUntil:null,
  createdAt:1800000000,redeemedAt:null,deletedAt:null,note:'测试订单',redemptionMode:'device',license:null,canDelete:true};
function descendants(element) { return [element,...element.children.flatMap(descendants)]; }
test('code search sends trimmed plaintext only in POST body and safely renders notes', async()=>{
  const note='<img src=x onerror=alert(1)>';
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async url=>{
    assert.equal(url,'/v1/admin/activation-codes/search'); return {...exampleCode,note};
  }); await page.settle();
  page.element('code-search-input').value='  FAW-local-search-code  ';
  await page.element('code-search-form').events.submit({preventDefault(){}});
  const request=page.requests.find(r=>r.url.includes('/activation-codes'));
  assert.equal(request.method,'POST'); assert.deepEqual(request.body,{activationCode:'FAW-local-search-code'});
  assert.equal(page.element('code-search-result').hidden,false);
  assert.ok(descendants(page.element('code-search-result')).some(n=>n.textContent==='卡密备注：'+note));
  assert.ok(page.requests.every(r=>!r.url.includes('FAW-local-search-code')));
});
test('deletion needs inline confirmation, uses the fingerprint and displays retained history', async()=>{
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async (url,options)=>{
    if (options.method==='POST') return exampleCode;
    assert.equal(url,'/v1/admin/activation-codes/'+exampleCode.codeHash);
    assert.equal(options.method,'DELETE');
    return {...exampleCode,status:'deleted',deletedAt:1800000100,canDelete:false};
  }); await page.settle();
  page.element('code-search-input').value='FAW-local-search-code';
  await page.element('code-search-form').events.submit({preventDefault(){}});
  const button=descendants(page.element('code-search-result')).find(n=>n.textContent==='删除此卡密');
  assert.ok(button); button.events.click();
  assert.equal(page.element('code-delete-confirm').hidden,false);
  assert.equal(page.requests.filter(r=>r.method==='DELETE').length,0);
  await page.element('confirm-code-delete').events.click();
  assert.equal(page.requests.filter(r=>r.method==='DELETE').length,1);
  assert.equal(page.element('code-delete-confirm').hidden,true);
  assert.ok(descendants(page.element('code-search-result')).some(n=>n.textContent==='已删除'));
  assert.ok(!descendants(page.element('code-search-result')).some(n=>n.textContent==='删除此卡密'));
});
test('unknown search clears the previous delete target', async()=>{
  let count=0;
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async ()=>++count===1?exampleCode:{error:'CODE_NOT_FOUND'});
  await page.settle(); page.element('code-search-input').value='FAW-known';
  await page.element('code-search-form').events.submit({preventDefault(){}});
  page.element('code-search-input').value='FAW-missing';
  await page.element('code-search-form').events.submit({preventDefault(){}});
  assert.equal(page.element('code-search-result').hidden,true);
  assert.equal(page.element('code-delete-confirm').hidden,true);
  await page.element('confirm-code-delete').events.click();
  assert.equal(page.requests.filter(r=>r.method==='DELETE').length,0);
  assert.match(page.element('code-search-status').textContent,/未找到/);
});
test('late search response cannot restore code or device details after logout', async()=>{
  let respond;
  const response=new Promise(resolve=>respond=resolve);
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async ()=>response);
  await page.settle(); page.element('code-search-input').value='FAW-late';
  const search=page.element('code-search-form').events.submit({preventDefault(){}});
  await page.settle(); page.element('logout').events.click(); respond(exampleCode); await search;
  assert.equal(page.element('code-search-input').value,'');
  assert.equal(page.element('code-search-result').hidden,true);
  assert.equal(page.element('code-search-result').children.length,0);
  assert.equal(page.element('code-search-controls').disabled,true);
});

const diagnosticSummary={id:'00000000-0000-0000-0000-000000000001',deviceFingerprint:'AABBCCDDEEFF0011',deviceNote:'客户 A',
  manufacturer:'FAW',model:'HS5',firmware:'3222',androidRelease:'9',sdk:28,versionName:'0.2.31-FAW-LOG1',versionCode:105,receivedAt:1800000000,sizeBytes:100,truncated:false};
test('diagnostics load only when selected and filters are encoded in the request', async()=>{
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async url=>{assert.match(url,/^\/v1\/admin\/diagnostics\?/);return [diagnosticSummary];});
  await page.settle(); assert.equal(page.requests.some(r=>r.url.startsWith('/v1/admin/diagnostics')),false);
  page.window.location.hash='#diagnostics'; page.windowEvents.hashchange(); await page.settle();
  assert.equal(page.element('diagnostics-view').hidden,false); assert.equal(page.element('licenses-view').hidden,true);
  page.element('diagnostic-device').value='AABB'; page.element('diagnostic-version').value='105';
  page.element('diagnostic-search-form').events.submit({preventDefault(){}}); await page.settle();
  assert.match(page.requests.at(-1).url,/device=AABB&version=105$/);
  assert.ok(descendants(page.element('diagnostic-list')).some(e=>e.textContent==='客户 A'));
});
test('diagnostic content is displayed as text and explicit logout clears it', async()=>{
  const report='<img src=x onerror=alert(1)> <script>danger()</script>';
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async url=>url.includes('?')?[diagnosticSummary]:{summary:diagnosticSummary,report,reportSha256:'a'.repeat(64)});
  await page.settle(); page.window.location.hash='#diagnostics'; page.windowEvents.hashchange(); await page.settle();
  page.element('diagnostic-list').children[0].events.click(); await page.settle();
  assert.equal(page.element('diagnostic-text').textContent,report); assert.equal(page.element('diagnostic-text').innerHTML,undefined);
  page.element('logout').events.click();
  assert.equal(page.element('diagnostic-text').textContent,''); assert.equal(page.element('diagnostic-detail').hidden,true);
  assert.equal(page.element('diagnostic-list').children.length,0); assert.equal(page.element('diagnostic-controls').disabled,true);
});
test('late diagnostic detail cannot reappear after logout', async()=>{
  let release; const page=fixture(new Map([[key,'local-test-refresh']]),undefined,async url=>url.includes('?')?[diagnosticSummary]:new Promise(resolve=>release=resolve));
  await page.settle(); page.window.location.hash='#diagnostics'; page.windowEvents.hashchange(); await page.settle();
  page.element('diagnostic-list').children[0].events.click(); await page.settle();
  page.element('logout').events.click(); release({summary:diagnosticSummary,report:'private diagnostic',reportSha256:'a'.repeat(64)}); await page.settle();
  assert.equal(page.element('diagnostic-text').textContent,''); assert.equal(page.element('diagnostic-metadata').children.length,0);
});
test('invalid device filter is rejected without sending a diagnostic request', async()=>{
  const page=fixture(new Map([[key,'local-test-refresh']])); await page.settle();
  page.element('diagnostic-device').value='%'; page.element('diagnostic-search-form').events.submit({preventDefault(){}}); await page.settle();
  assert.equal(page.requests.some(r=>r.url.startsWith('/v1/admin/diagnostics')),false); assert.match(page.element('diagnostic-status').textContent,/设备编号/);
});

test('update header validation loads the server state and never presents a failed initial read as disabled validation', async()=>{
  for (const enabled of [false,true]) {
    const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async()=>({headerValidationEnabled:enabled,headerName:'Autunn'}));
    assert.equal(page.element('update-header-validation').disabled,true);
    assert.equal(page.element('update-header-validation').indeterminate,true);
    await page.settle();
    assert.equal(page.element('update-header-validation').checked,enabled);
    assert.equal(page.element('update-header-validation').disabled,false);
    assert.equal(page.element('update-header-validation').indeterminate,false);
    assert.equal(page.element('update-header-state').textContent,enabled?'（已开启）':'（已关闭）');
    assert.equal(page.requests.find(r=>r.url==='/v1/admin/updates/settings').headers.Authorization,'Bearer local-test-access-1');
  }
  for (const reply of [{error:'UPDATE_SETTINGS_UNAVAILABLE'},{headerValidationEnabled:'false'}]) {
    const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async()=>reply);await page.settle();
    assert.equal(page.element('update-header-validation').disabled,true);
    assert.equal(page.element('update-header-validation').indeterminate,true);
    assert.equal(page.element('update-header-state').textContent,'（状态待确认）');
    assert.match(page.element('update-settings-status').textContent,/不可用|设置异常/);
  }
});

test('clicking the update header checkbox immediately saves with the renewed admin token and uses canonical server state', async()=>{
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>({headerValidationEnabled:options.method==='PUT',headerName:'Autunn'}));
  await page.settle();page.advance(290000);
  page.element('update-header-validation').checked=true;
  const save=page.element('update-header-validation').events.change();
  assert.equal(page.element('update-header-validation').disabled,true);
  await save;
  const writes=page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='PUT');
  assert.equal(writes.length,1);assert.deepEqual(writes[0].body,{headerValidationEnabled:true});
  assert.equal(writes[0].headers.Authorization,'Bearer local-test-access-2');
  assert.equal(page.requests.filter(r=>r.url==='/v1/admin-login/refresh').length,2);
  assert.equal(page.element('update-header-validation').checked,true);
  assert.equal(page.element('update-header-validation').disabled,false);
  assert.match(page.element('update-settings-status').textContent,/已保存.*已开启/);

  const canonical=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async()=>({headerValidationEnabled:false}));
  await canonical.settle();canonical.element('update-header-validation').checked=true;
  await canonical.element('update-header-validation').events.change();
  assert.equal(canonical.element('update-header-validation').checked,false);
  assert.match(canonical.element('update-settings-status').textContent,/已保存.*已关闭/);
});

test('a failed update setting save rereads the server state and suppresses overlapping writes', async()=>{
  for (const enabled of [false,true]) {
    let finish;
    const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>options.method==='GET'?{headerValidationEnabled:enabled}:new Promise(resolve=>finish=resolve));
    await page.settle();page.element('update-header-validation').checked=!enabled;
    const save=page.element('update-header-validation').events.change();await page.settle();
    await page.element('update-header-validation').events.change();
    assert.equal(page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='PUT').length,1);
    assert.equal(page.element('update-header-validation').disabled,true);
    finish({error:'UPDATE_SETTINGS_UNAVAILABLE'});await save;
    assert.equal(page.element('update-header-validation').checked,enabled);
    assert.equal(page.element('update-header-validation').disabled,false);
    assert.match(page.element('update-settings-status').textContent,/已读取服务端设置/);
    assert.equal(page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='GET').length,2);
  }
});

test('a committed setting with a lost PUT response is reconciled from the actual server state', async()=>{
  let serverEnabled=false;
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>{
    if(options.method==='PUT'){serverEnabled=JSON.parse(options.body).headerValidationEnabled;throw new Error('Response lost after commit');}
    return {headerValidationEnabled:serverEnabled};
  });
  await page.settle();page.element('update-header-validation').checked=true;
  await page.element('update-header-validation').events.change();
  assert.equal(serverEnabled,true);
  assert.equal(page.element('update-header-validation').checked,true);
  assert.equal(page.element('update-header-validation').indeterminate,false);
  assert.equal(page.element('update-header-validation').disabled,false);
  assert.equal(page.element('update-header-state').textContent,'（已开启）');
  assert.equal(page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='GET').length,2);
});

test('failed reconciliation or refresh leaves the current setting unknown and locked', async()=>{
  for(const operation of ['save','refresh']){
    let reads=0;
    const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>{
      if(options.method==='PUT')throw new Error('Response lost after commit');
      return ++reads===1?{headerValidationEnabled:true}:{error:'UPDATE_SETTINGS_UNAVAILABLE'};
    });
    await page.settle();
    assert.equal(page.element('update-header-validation').checked,true);
    if(operation==='save'){
      page.element('update-header-validation').checked=false;
      await page.element('update-header-validation').events.change();
    }else{page.element('reload-updates').events.click();await page.settle();}
    assert.equal(reads,2);
    assert.equal(page.element('update-header-validation').indeterminate,true);
    assert.equal(page.element('update-header-validation').disabled,true);
    assert.equal(page.element('update-header-state').textContent,'（状态待确认）');
    assert.match(page.element('update-settings-status').textContent,/状态尚未确认/);
  }
});

test('logout discards a pending reconciliation read without restoring its canonical response', async()=>{
  let reads=0,finish;
  const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>{
    if(options.method==='PUT')throw new Error('Response lost after commit');
    return ++reads===1?{headerValidationEnabled:false}:new Promise(resolve=>finish=resolve);
  });
  await page.settle();page.element('update-header-validation').checked=true;
  const save=page.element('update-header-validation').events.change();await page.settle();
  assert.equal(reads,2);page.element('logout').events.click();finish({headerValidationEnabled:true});await save;
  assert.equal(page.element('update-header-validation').checked,false);
  assert.equal(page.element('update-header-validation').indeterminate,true);
  assert.equal(page.element('update-header-validation').disabled,true);
  assert.equal(page.element('update-header-state').textContent,'（状态待确认）');
  assert.match(page.element('update-settings-status').textContent,/已退出/);
});

test('late update setting reads and writes cannot restore controls or state after logout', async()=>{
  for (const operation of ['GET','PUT']) {
    let finish;
    const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>options.method===operation?new Promise(resolve=>finish=resolve):{headerValidationEnabled:false});
    await page.settle();let save;
    if (operation==='PUT') {page.element('update-header-validation').checked=true;save=page.element('update-header-validation').events.change();await page.settle();}
    page.element('logout').events.click();finish({headerValidationEnabled:true});if(save)await save;else await page.settle();
    assert.equal(page.element('update-header-validation').checked,false);
    assert.equal(page.element('update-header-validation').indeterminate,true);
    assert.equal(page.element('update-header-validation').disabled,true);
    assert.equal(page.element('update-header-state').textContent,'（状态待确认）');
    assert.match(page.element('update-settings-status').textContent,/已退出/);
    assert.equal(page.storage.has(key),false);
    assert.equal(page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='GET').length,1);
  }
});

test('unauthorized or forbidden settings requests end the admin session and lock the switch', async()=>{
  for(const code of ['UNAUTHORIZED','FORBIDDEN']) {
    for(const operation of ['GET','PUT']) {
      const page=fixture(new Map([[key,'local-test-refresh']]),undefined,undefined,async options=>options.method===operation?{error:code}:{headerValidationEnabled:false});
      await page.settle();
      if(operation==='PUT'){page.element('update-header-validation').checked=true;await page.element('update-header-validation').events.change();}
      assert.equal(page.element('account').textContent,'未登录');
      assert.equal(page.element('update-header-validation').disabled,true);
      assert.equal(page.element('update-header-validation').indeterminate,true);
      assert.equal(page.storage.has(key),false);
      assert.match(page.element('update-settings-status').textContent,/登录已过期|没有.*管理员权限/);
      assert.equal(page.requests.filter(r=>r.url==='/v1/admin/updates/settings'&&r.method==='GET').length,1);
    }
  }
});
