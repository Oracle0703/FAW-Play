#!/usr/bin/env python3
"""Validate this delivery, optionally cross-checking the original workspace evidence."""
from pathlib import Path
import argparse,collections,csv,hashlib,json,re
from html.parser import HTMLParser
from urllib.parse import unquote,urlsplit
p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path);p.add_argument('--write-report',action='store_true',help='Regenerate verification report; rebuild SHA manifest afterwards');a=p.parse_args()
out=Path(__file__).resolve().parents[1];checks=[]
def ok(name,value,detail=None):
    assert value,name
    checks.append({'check':name,'status':'pass','detail':detail})
cat=json.loads((out/'catalog/interfaces.json').read_text());cs=cat['classes'];by={c['descriptor']:c for c in cs}
ok('unique class descriptors',len(by)==len(cs),len(cs))
ok('method counts',sum(len(c['methods']) for c in cs)==cat['summary']['declaredMethods'])
for c in cs:
    tx=[m['transaction'] for m in c['methods'] if 'transaction' in m]
    ok_dummy=len(tx)==len(set(tx))
    assert ok_dummy,c['name']+' duplicate transaction'
    for m in c['methods']:
        if 'transaction' in m:
            stub=by[c['descriptor'][:-1]+'$Stub;']
            f=next(f for f in stub['fields'] if f['name']=='TRANSACTION_'+m['name'])
            assert m['transaction']==f['value']
ok('all transaction values linked to actual Stub fields',True,cat['summary']['exactBinderMethods'])
ok('unique module IDs',len(cat['modules'])==len({m['module'] for m in cat['modules']}),len(cat['modules']))
for m in cat['modules']:assert ('L'+m['interface'].replace('.','/')+';') in by
ok('module targets exist in catalog',True)
with (out/'catalog/methods.csv').open(encoding='utf-8-sig',newline='') as f: rows=list(csv.DictReader(f))
ok('methods CSV complete',len(rows)==cat['summary']['declaredMethods'])
with (out/'catalog/binder-transactions.csv').open(encoding='utf-8-sig',newline='') as f:rows=list(csv.DictReader(f))
ok('transaction CSV complete',len(rows)==cat['summary']['exactBinderMethods'])
for item in json.loads((out/'evidence/copied-source-index.json').read_text()):
    data=(out/item['copy']).read_bytes();assert hashlib.sha256(data).hexdigest()==item['sha256'],item['copy']
    if a.workspace:assert (a.workspace/item['source']).read_bytes()==data
ok('copied evidence and examples preserve source hashes',True)
for item in json.loads((out/'evidence/excerpt-index.json').read_text()):
    assert (out/item['copy']).exists()
    if 'methodsRequested' in item:assert item['methodsRequested']==item['methodsExtracted']==32
ok('selected UI evidence methods present',True,32)
# Key signatures independently reviewed in the prior vehicle audit.
expect=[('ICarATCManager','setACTemperature',12,'(IF)I'),('ICarATCManager','getACFrontTemperature',11,'(I)F'),('ICarATCManager','getAmbientTemp',93,'()F'),('ICarVehicleStateManager','getAverageFuelConsumptionFloat',59,'()F'),('ICarInstrumentRelatedManager','sendNaviInfoToCluster',6,'(Lltd/qisi/adapterproxy/data/QisiNaviInfo;)I')]
for owner,name,tx,desc in expect:
    c=by['Lltd/qisi/adapterproxy/'+owner+';'];m=next(m for m in c['methods'] if m['name']==name)
    assert m['transaction']==tx and m['descriptor']==desc
ok('critical signatures and types',True,len(expect))
carplay=[c for c in cs if c['descriptor'].startswith('Lltd/qisi/adapterproxy/phonelink/QISICarPlayType$') and c['accessFlags']&0x200]
ok('CarPlay groups',len(carplay)==41)
if a.workspace:
    old=json.loads((a.workspace/'work/qisi-interface-audit-20261010/qisi-static-integers.json').read_text());n=0
    for c in carplay+[by['Lltd/qisi/adapterproxy/Constants;']]:
        actual={f['name']:f['value'] for f in c['fields'] if f['descriptor']=='I' and 'value' in f}
        assert old[c['descriptor']]['constants']==actual,c['name'];n+=len(actual)
    ok('independent previous constant extraction matches',True,n)
    for src in json.loads((out/'catalog/source-index.json').read_text()):assert hashlib.sha256((a.workspace/src['path']).read_bytes()).hexdigest()==src['sha256']
    ok('all original extraction input hashes match',True)
# Relative file links must resolve inside the delivery. Ignore anchors and code blocks in Markdown.
links=[]
for path in out.rglob('*.md'):
    text=re.sub(r'```.*?```','',path.read_text(),flags=re.S)
    links.extend((path,m) for m in re.findall(r'\]\(([^)]+)\)',text))
class Parser(HTMLParser):
    def handle_starttag(self,tag,attrs):
        if tag=='a':
            for k,v in attrs:
                if k=='href':links.append((self.path,v))
for htmlfile in out.glob('*.html'):
    x=Parser();x.path=htmlfile;x.feed(htmlfile.read_text())
for path,target in links:
    if target.startswith(('https:','http:','#')):continue
    rel=unquote(urlsplit(target.strip('<>')).path)
    resolved=(path.parent/rel).resolve()
    assert resolved.is_relative_to(out.resolve()) and resolved.exists(),(path.name,target)
ok('all relative documentation links resolve',True,len(links))
text=(out/'index.html').read_text();assert '<script src=' not in text and '<link ' not in text
ok('offline HTML embeds data and has no external scripts/styles',True)
report={'date':'2026-10-15','result':'pass','scope':'documentation/catalog only; no live HS5 verification','summary':cat['summary'],'checks':checks,'manualVerification':{'python':'py_compile and diagnostics --help passed; diagnostics not run against adb','java':'javac source/target8 using Android35 android.jar passed (3 obsolete option warnings); not a device/runtime test','browser':'Local HTTP preview: module33, float transaction12, FT=4, multiword/Chinese search, empty result, pagination and rendered handbook passed; file:// direct-open blocked by browser URL policy and not counted as a pass','notRun':['HS5 runtime','vehicle writes','ADB diagnostics against a device','APK installation','Apple official Ultra integration']}}
if a.write_report:(out/'验证报告.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'result':'pass','checks':len(checks),'summary':cat['summary']},ensure_ascii=False,indent=2))
