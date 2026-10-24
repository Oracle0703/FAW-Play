#!/usr/bin/env python3
"""Render this delivery's controlled Markdown subset into a portable printable HTML manual."""
from pathlib import Path
import html,re
from urllib.parse import quote,urlsplit,unquote
out=Path(__file__).resolve().parents[1]
docs=['README.md','01-接口使用手册.md','02-模块与完整合约.md','03-权限与实车验收.md','04-证据与复核.md','examples/README.md']
anchors={str((out/p).resolve()):'doc'+str(i) for i,p in enumerate(docs)}
def inline(text,source):
    tokens=[]
    def keep(s):tokens.append(s);return '\x00'+str(len(tokens)-1)+'\x00'
    text=re.sub(r'`([^`]+)`',lambda m:keep('<code>'+html.escape(m[1])+'</code>'),text)
    def link(m):
        target=m[2];parts=urlsplit(target)
        if not parts.scheme and not target.startswith('#'):
            resolved=(source.parent/unquote(parts.path)).resolve()
            target='使用手册.html#'+anchors[str(resolved)] if str(resolved) in anchors else str(resolved.relative_to(out.resolve()))+('#'+parts.fragment if parts.fragment else '')
        return keep('<a href="'+html.escape(target,quote=True)+'">'+html.escape(m[1])+'</a>')
    text=re.sub(r'\[([^]]+)\]\(([^)]+)\)',link,text)
    text=html.escape(text)
    text=re.sub(r'\*\*(.+?)\*\*',r'<strong>\1</strong>',text)
    for i,s in enumerate(tokens):text=text.replace('\x00'+str(i)+'\x00',s)
    return text

def render(source):
    rows=source.read_text().split('\n');output=[];i=0
    while i<len(rows):
        row=rows[i]
        if not row.strip():i+=1;continue
        if row.startswith('```'):
            language=row[3:];i+=1;code=[]
            while i<len(rows) and not rows[i].startswith('```'):code.append(rows[i]);i+=1
            output.append('<pre><code>'+html.escape('\n'.join(code))+'</code></pre>');i+=1;continue
        m=re.match(r'^(#{1,6}) (.*)',row)
        if m:
            level=len(m[1]);output.append(f'<h{level}>'+inline(m[2],source)+f'</h{level}>');i+=1;continue
        if row.startswith('|'):
            table=[]
            while i<len(rows) and rows[i].startswith('|'):table.append(rows[i]);i+=1
            output.append('<div class="table"><table>')
            for n,r in enumerate(table):
                cells=[v.strip() for v in r.strip().strip('|').split('|')]
                if all(re.fullmatch(r':?-+:?',v) for v in cells):continue
                tag='th' if n==0 else 'td'
                output.append('<tr>'+''.join(f'<{tag}>'+inline(v,source)+f'</{tag}>' for v in cells)+'</tr>')
            output.append('</table></div>');continue
        if re.match(r'^(?:- |\d+\. )',row):
            ordered=bool(re.match(r'^\d+\. ',row));tag='ol' if ordered else 'ul';output.append('<'+tag+'>')
            while i<len(rows) and re.match(r'^(?:- |\d+\. )',rows[i]):output.append('<li>'+inline(re.sub(r'^(?:- |\d+\. )','',rows[i]),source)+'</li>');i+=1
            output.append('</'+tag+'>');continue
        text=[]
        while i<len(rows) and rows[i].strip() and not re.match(r'^(?:#|\||```|- |\d+\. )',rows[i]):text.append(rows[i]);i+=1
        if text:output.append('<p>'+inline(' '.join(text),source)+'</p>')
        else:i+=1
    return '\n'.join(output)
nav=''.join('<a href="#doc'+str(i)+'">'+label+'</a>' for i,label in enumerate(['交付说明','接口使用','完整合约','权限验收','证据复核','源码示例']))
body=''.join('<article id="doc'+str(i)+'">'+render(out/p)+'</article>' for i,p in enumerate(docs))
page='''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红旗HS5完整接口与使用手册 · 2026-10-15</title><style>
body{margin:0;background:#f3f6f8;color:#18313f;font:16px/1.8 system-ui,-apple-system,"PingFang SC",sans-serif}header{background:#132f40;color:white;padding:20px 28px}header a{color:#cce8f7;margin-right:18px}nav{position:sticky;top:0;background:#fff;border-bottom:1px solid #cbd9e0;padding:12px 24px;display:flex;gap:18px;flex-wrap:wrap;font-size:14px;z-index:1}main{max-width:1100px;margin:auto;padding:20px}article{background:#fff;border:1px solid #dce5ea;border-radius:8px;margin:20px 0;padding:30px;scroll-margin-top:100px}a{color:#05608e}h1{font-size:28px}h2{font-size:23px;border-top:1px solid #e4ebef;padding-top:20px;margin-top:35px}h3{font-size:18px}pre{background:#edf3f7;border-radius:5px;padding:16px;overflow:auto;line-height:1.55;font-size:13px}code{font-family:ui-monospace,Menlo,monospace;font-size:.88em;overflow-wrap:anywhere}.table{overflow:auto}table{width:100%;border-collapse:collapse;font-size:14px;line-height:1.65}th,td{border:1px solid #d6e2e9;padding:8px 10px;text-align:left;vertical-align:top}th{background:#e9f1f6}li{margin:7px 0}@media(max-width:650px){main{padding:8px}article{padding:18px}nav{position:static}}@media print{body{background:#fff;font-size:10pt}header,nav{display:none}main{max-width:none;padding:0}article{padding:0;border:0;break-before:page}article:first-child{break-before:auto}a{color:inherit;text-decoration:none}pre{white-space:pre-wrap}tr,pre{break-inside:avoid}}
</style></head><body><header><strong>红旗HS5 · 完整接口与使用手册</strong><p><a href="index.html">打开可搜索接口目录</a><a href="README.md">Markdown原件</a>可离线阅读，也可用浏览器打印</p></header><nav>NAV</nav><main>BODY</main></body></html>'''
(out/'使用手册.html').write_text(page.replace('NAV',nav).replace('BODY',body))
print('Readable handbook HTML built')
