#!/usr/bin/env python3
"""Build the offline searchable HTML and readable module reference from interfaces.json."""
from pathlib import Path
import json,html,collections
out=Path(__file__).resolve().parents[1]
p=json.loads((out/'catalog/interfaces.json').read_text()); cs={c['name']:c for c in p['classes']}
lines=['# Qisi模块与完整合约','', '自动生成日期：2026-10-15。数据来自 [interfaces.json](catalog/interfaces.json)。', '', '**以下为声明参考，默认仅静态确认。** 模块编号来自管理器queryBinder实参；事务号来自对应Stub的TRANSACTION常量。未逐项确认车型支持、参数语义、完整Parcel格式、权限或实车执行。空白事务号不补猜。', '', '## 模块总表','', '| 编号 | SDK管理器 | Binder合约 |', '| ---: | --- | --- |']
for m in sorted(p['modules'],key=lambda m:m['module']):
 lines.append(f"| {m['module']} | {m['manager'].split('.')[-1]} | {m['interface'].split('.')[-1]} |")
lines+=['','## 各模块全部声明方法','', '签名由DEX类型表恢复，参数名/泛型通常不可得。返回void仍可能需要同步reply的异常头；具体以Proxy为准。模块实现中默认返回错误的同名方法也保留在表中。','']
for m in sorted(p['modules'],key=lambda m:m['module']):
 c=cs[m['interface']]
 lines += [f"### {m['module']} · {c['name'].split('.')[-1]}",'',f"SDK管理器：`{m['manager']}`。查询证据：`{m['origin']}` LF行{m['line']}。",'',f"descriptor：`{c['name']}`。",'', '| 事务 | 完整签名 |','| ---: | --- |']
 for a in sorted(c['methods'],key=lambda a:(a.get('transaction',9999),a['name'])): lines.append(f"| {a.get('transaction','')} | `{a['signature']}` |")
 lines.append('')
lines+=['## 仪表数据模型的实例字段','', '字段顺序来自元数据，不是Parcel/JSON顺序。请对照主手册和writeToParcel证据。','']
for name in ['QisiNaviInfo','QisiFullMapInfo','QisiHUDNaviInfo','QisiMusicInfo','QisiCallInfo']:
 c=cs.get('ltd.qisi.adapterproxy.data.'+name)
 if not c:continue
 lines += ['### '+name,'','| 字段 | 类型 |','| --- | --- |']
 lines += [f"| `{f['name']}` | `{f['type']}` |" for f in c['fields'] if not f['static']]
 lines.append('')
lines+=['## 41组CarPlay静态整数常量','', '每组保持所属类型，不使用ordinal，不跨接口混用。完整Constants及其他常量见CSV/JSON。','']
for c in p['classes']:
 if not c['descriptor'].startswith('Lltd/qisi/adapterproxy/phonelink/QISICarPlayType$') or not c['accessFlags']&0x200:continue
 lines += ['### '+c['name'].split('$')[-1],'','| 名称 | 值 |','| --- | ---: |']
 lines += [f"| `{f['name']}` | {f['value']} |" for f in c['fields'] if 'value' in f and f['type']=='int']
 lines.append('')
lines += ['## 其他合约和回调','', '全部接口、回调、SDK方法、生成辅助类和内部实现声明已在 [离线检索目录](index.html)、[methods.csv](catalog/methods.csv) 和 [binder-transactions.csv](catalog/binder-transactions.csv) 交付。HIDL缺少本次静态常量关联的transaction，不套用Java Binder例子。','']
(out/'02-模块与完整合约.md').write_text('\n'.join(lines))
# Lean self-contained data; the detailed raw catalog remains a separate file.
def tags(name):
 a=[]
 rules={'空调 温度 风量 循环 除霜':'ATC|CarAC','座椅 加热 通风':'SCCU|Seat','车辆 车速 挡位 燃油 胎压 门':'VehicleState|CarStateChanged','仪表 导航 地图':'Instrument|Cluster|Navi|CarConnect','抬头 HUD 导航':'HUD','音频 音量 焦点 麦克风 混音':'Audio|FM1388','蓝牙':'Bluetooth','无线 热点 网络 天线':'Wifi|NetWork|SmartAntenna','方向盘 硬按键 焦点':'HardKey|SystemServiceInterface|ViewFocus','主题':'ClusterUpdate','车型 配置 能力':'EOL|Eol|Config','电源 休眠 状态':'Power|SystemStatus','USB 投屏':'Project|LinkMutex|Carlink','底层 RPC':'Rpc|DeviceAdapter'}
 import re
 for label,pat in rules.items():
  if re.search(pat,name,re.I):a.append(label)
 return ' '.join(a)
data=[]
module_of={m['interface']:m['module'] for m in p['modules']}
for c in p['classes']:
 members=[]
 for m in c['methods']:
  members.append(['方法',m['signature'],m.get('transaction',''),'',m['accessFlags'],m.get('line','')])
 for f in c['fields']:
  members.append(['常量' if 'value' in f else '字段',f['type']+' '+f['name'],'',f.get('value',''),f['accessFlags'],f.get('line','')])
 data.append({'n':c['name'],'c':c['category'],'t':tags(c['name']),'o':c['origin'],'d':c.get('classDefIndex',''),'l':c.get('declaration',{}).get('line',''),'m':module_of.get(c['name'],''),'a':members})
summary=p['summary']; payload=json.dumps(data,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c')
page='''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红旗 HS5 接口目录 · 2026-10-15</title>
<style>
:root{font-family:system-ui,-apple-system,"PingFang SC",sans-serif;color:#173141;background:#f3f6f8}*{box-sizing:border-box}body{margin:0}header{background:#122e40;color:#fff;padding:30px max(24px,calc((100vw - 1320px)/2))}h1{font-size:28px;margin:0 0 10px}header p{color:#c5d6df;max-width:1000px;line-height:1.65;margin:10px 0}nav{display:flex;gap:10px;flex-wrap:wrap}a{color:#075e90}nav a{color:#d0e9f6;font-size:14px}main{max-width:1380px;margin:auto;padding:24px}.stats{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}.stat{background:white;border:1px solid #dce4e9;border-radius:9px;padding:12px 18px;min-width:150px}.stat strong{display:block;font-size:26px}.stat span{font-size:13px;color:#526d7b}.filters{background:white;border:1px solid #dce4e9;padding:18px;border-radius:10px}.search{display:block;width:100%;font:inherit;font-size:17px;padding:13px;border:1px solid #809dab;border-radius:6px;margin:8px 0 15px}.row{display:flex;align-items:center;gap:12px;flex-wrap:wrap}select,button{font:inherit;border:1px solid #a7bac5;border-radius:5px;background:white;padding:7px 10px;color:#173141}button{cursor:pointer}button:hover{background:#e8f3f9}button:disabled{opacity:.4;cursor:default}.chips{margin-top:14px}.chips button{border:0;background:#eaf2f6;font-size:13px}.notice{line-height:1.65;border-left:4px solid #c19841;background:#fff9eb;padding:12px 16px;margin:18px 0;font-size:14px}.count{display:flex;justify-content:space-between;align-items:center;margin:20px 0 10px;gap:12px;flex-wrap:wrap}.wrap{overflow:auto;border:1px solid #d8e2e9;border-radius:8px;background:white}table{width:100%;border-collapse:collapse;min-width:900px;font-size:13px}th{text-align:left;background:#e7eff4;position:sticky;top:0;padding:12px}td{padding:10px 12px;border-top:1px solid #e2e9ed;vertical-align:top;overflow-wrap:anywhere}td:nth-child(1){width:30%}td:nth-child(2){width:47%}code{font-family:ui-monospace,Menlo,monospace}small{color:#607582}details{margin-top:7px}details summary{cursor:pointer;color:#58768a}.pager{display:flex;gap:14px;justify-content:center;align-items:center;margin:22px}footer{font-size:13px;color:#5e7480;padding:20px 0}.pill{display:inline-block;background:#e8f3ec;color:#2c5c41;padding:2px 5px;border-radius:3px;margin-right:6px}.num{font-weight:bold;color:#0c6086}.empty{padding:35px;text-align:center}.mobileHint{font-size:12px;color:#617986}@media(max-width:680px){main{padding:14px}header{padding:22px 16px}.stat{min-width:130px;flex:1}h1{font-size:24px}}
</style></head><body><header><h1>红旗 HS5 · 接口目录与使用资料</h1><p>2026-10-15 ｜ Android 9 / t1c_acrn ｜ 3222 Qisi/FAW + 独立来源的 Alfus/HIDL 恢复材料。可离线检索声明、事务号、字段和常量。</p><nav><a href="使用手册.html#doc0">交付说明</a><a href="使用手册.html#doc1">使用手册</a><a href="使用手册.html#doc2">模块与完整合约</a><a href="使用手册.html#doc3">权限与实车验收</a><a href="使用手册.html#doc4">证据与复核</a><a href="catalog/interfaces.json" download>JSON</a><a href="catalog/methods.csv" download>方法CSV</a><a href="catalog/binder-transactions.csv" download>事务号CSV</a></nav></header><main>
<div class="stats">STATS</div><section class="filters" aria-label="检索条件"><label for="q">搜索名称、方法、中文功能、模块号或事务号</label><input id="q" class="search" type="search" placeholder="例如：空调 / ICarATCManager setACTemperature / QisiNaviInfo" autocomplete="off"><div class="row"><label>范围 <select id="scope"><option value="api">接口、SDK、回调、数据与常量</option><option value="all">全部恢复类（含内部实现/辅助类）</option></select></label><label>类型 <select id="kind"><option value="">全部成员</option><option>方法</option><option>字段</option><option>常量</option></select></label><label>模块 <select id="module"><option value="">全部模块与其他服务</option>MODULES</select></label><label><input type="checkbox" id="public" checked> 仅public成员</label><button id="reset">重置</button></div><div class="row chips">CHIPS</div></section>
<p class="notice"><strong>证据边界：</strong>目录默认是静态声明。事务号取自 Stub 的 TRANSACTION 常量；空白表示本次未关联，不能按顺序猜测。方法存在不代表车型配备、权限获准或实车通过。HIDL 与普通 Binder 不共用 Parcel；字段顺序不代表 wire 顺序。已核实用法请读使用手册。</p>
<div class="count"><strong id="count" role="status" aria-live="polite"></strong><span class="mobileHint">每页100项 · 中文检索使用功能别名 · 空格分词同时匹配</span></div><div class="wrap"><table><thead><tr><th>类 / 模块 / 来源</th><th>成员签名</th><th>事务号</th><th>常量值</th></tr></thead><tbody id="results"></tbody></table><div id="empty" class="empty" hidden>没有匹配项。可清空模块过滤或切换到全部恢复类。</div></div><div class="pager"><button id="prev">上一页</button><span id="page"></span><button id="next">下一页</button></div><footer>此页内置数据，不联网。完整目录保留原始DEX描述符、accessFlags、来源哈希与classDefIndex。来源路径指原工作区取证材料，包内精选原文见evidence目录。数据不是OEM SDK授权或Apple Ultra交付证明。</footer></main>
<script id="data" type="application/json">PAYLOAD</script><script>
'use strict';const classes=JSON.parse(document.getElementById('data').textContent);const byId=id=>document.getElementById(id);const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));let page=0,matches=[];const rows=[];for(const c of classes)for(const a of c.a)rows.push({c,a,s:(c.n+' '+c.t+' '+c.m+' '+a.slice(0,4).join(' ')).toLowerCase()});const excluded=new Set(['内部实现','Binder生成辅助类','HIDL传输与数据','其他厂商类']);
function filter(){const terms=byId('q').value.toLowerCase().trim().split(/\\s+/).filter(Boolean),all=byId('scope').value==='all',kind=byId('kind').value,mod=byId('module').value,pub=byId('public').checked;matches=rows.filter(r=>(all||!excluded.has(r.c.c))&&(!kind||r.a[0]===kind)&&(!mod||String(r.c.m)===mod)&&(!pub||(r.a[4]&1))&&terms.every(t=>r.s.includes(t)));page=0;render();}
function render(){const total=Math.max(1,Math.ceil(matches.length/100));page=Math.min(page,total-1);byId('count').textContent=`${matches.length.toLocaleString()} 个匹配成员 / ${new Set(matches.map(r=>r.c.n)).size} 个类型（静态声明）`;byId('results').innerHTML=matches.slice(page*100,page*100+100).map(({c,a})=>`<tr><td><code>${esc(c.n)}</code><br><small>${esc(c.c)}${c.m!==''?' · 模块 '+esc(c.m):''}</small><details><summary>来源定位</summary><small>${esc(c.o)}<br>classDefIndex: ${esc(c.d)} · LF行: ${esc(a[5]||c.l||'未标注')}</small></details></td><td><span class="pill">${esc(a[0])}</span><code>${esc(a[1])}</code></td><td class="num">${esc(a[2])}</td><td><code>${esc(a[3])}</code></td></tr>`).join('');byId('empty').hidden=matches.length>0;byId('page').textContent=`${page+1} / ${total}`;byId('prev').disabled=page===0;byId('next').disabled=page+1>=total;}
for(const id of ['q','scope','kind','module','public'])byId(id).addEventListener(id==='q'?'input':'change',filter);byId('prev').onclick=()=>{page--;render()};byId('next').onclick=()=>{page++;render()};for(const button of document.querySelectorAll('[data-query]'))button.onclick=()=>{byId('q').value=button.dataset.query;byId('scope').value='api';byId('module').value='';byId('kind').value='';filter()};byId('reset').onclick=()=>{byId('q').value='';byId('scope').value='api';byId('kind').value='';byId('module').value='';byId('public').checked=true;filter()};if(location.hash.startsWith('#q='))byId('q').value=decodeURIComponent(location.hash.slice(3));filter();
</script></body></html>'''
stats=[('恢复类型',summary['classes']),('声明方法（含辅助类）',summary['declaredMethods']),('关联Binder事务',summary['exactBinderMethods']),('Qisi模块',summary['moduleMappings']),('CarPlay常量组',summary['qisiCarPlayGroups'])]
page=page.replace('STATS',''.join(f'<div class="stat"><strong>{v:,}</strong><span>{k}</span></div>' for k,v in stats)).replace('MODULES',''.join(f'<option value="{m["module"]}">{m["module"]} · {html.escape(m["interface"].split(".")[-1])}</option>' for m in sorted(p['modules'],key=lambda m:m['module']))).replace('CHIPS',''.join(f'<button data-query="{t}">{t}</button>' for t in ['空调','座椅','车速','仪表','方向盘','音频','蓝牙','主题','QisiNaviInfo'])).replace('PAYLOAD',payload)
(out/'index.html').write_text(page)
print('reference and offline HTML built',len(data),'types')
