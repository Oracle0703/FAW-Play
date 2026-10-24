#!/usr/bin/env python3
"""Build a declaration catalog from recovered DEX/CDEX metadata and smali.
No device access; numeric Binder transactions come only from Stub constants.
Usage: python3 tools/build_catalog.py --workspace /path/to/workspace
"""
from pathlib import Path
import argparse, collections, csv, hashlib, json, re, struct

OUT = Path(__file__).resolve().parents[1]
P = argparse.ArgumentParser()
P.add_argument('--workspace', type=Path, required=True)
ARGS = P.parse_args()
ROOT = ARGS.workspace.resolve()
SOURCES = {}

def source(path):
    rel = str(path.relative_to(ROOT))
    if rel not in SOURCES:
        SOURCES[rel] = {'path': rel, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size}
    return rel

def uleb(b, p):
    n = 0
    for shift in range(0,35,7):
        v=b[p]; p+=1; n|=(v&127)<<shift
        if not v&128: return n,p
    raise ValueError('ULEB overflow')

def encoded(b,p):
    h=b[p]; p+=1; k=h&31; z=(h>>5)+1
    if k==0x1e: return None,p
    if k==0x1f: return bool(h>>5),p
    if k==0x1c:
        count,p=uleb(b,p); a=[]
        for _ in range(count): v,p=encoded(b,p); a.append(v)
        return a,p
    if k==0x1d:
        typ,p=uleb(b,p); count,p=uleb(b,p); a=[]
        for _ in range(count):
            name,p=uleb(b,p); v,p=encoded(b,p); a.append([name,v])
        return {'annotationTypeIndex':typ,'elements':a},p
    raw=b[p:p+z]; p+=z
    if k in (0x10,0x11):
        size=4 if k==0x10 else 8
        return struct.unpack('<f' if size==4 else '<d',b'\0'*(size-z)+raw)[0],p
    n=int.from_bytes(raw,'little',signed=k in (0,2,4,6))
    if k in (0x17,0x18,0x19,0x1a,0x1b,0x15,0x16): return {'indexType':k,'index':n},p
    return n,p

def jtype(d):
    arrays=len(d)-len(d.lstrip('[')); d=d[arrays:]
    return {'V':'void','Z':'boolean','B':'byte','S':'short','C':'char','I':'int','J':'long','F':'float','D':'double'}.get(d,d[1:-1].replace('/','.'))+'[]'*arrays

def signature(name,desc):
    args,ret=desc[1:].split(')')
    return jtype(ret)+' '+name+'('+', '.join(jtype(x) for x in re.findall(r'\[*L[^;]+;|\[*[ZBSCIJFD]',args))+')'

def category(d,flags):
    n=d[1:-1]
    if '/hardware/' in n and '/V1_0/' in n: return 'HIDL合约' if flags&0x200 else 'HIDL传输与数据'
    if '$Stub' in n or '$Proxy' in n or '$Default' in n: return 'Binder生成辅助类'
    if '/data/' in n: return '数据模型'
    if '/phonelink/' in n or 'Constants' in n or n in ('android/car/media/CarAudioAttribute','alfusos/audio/AlfusAudio'): return '常量与类型'
    if n.startswith('ltd/qisi/adapterproxy/car/') or 'SDKImpl' in n or '-$$Lambda$' in n: return '内部实现'
    if '/listener/' in n or '/Interfaces/' in n or (flags&0x200 and ('Listener' in n or 'Callback' in n)): return '回调合约'
    if flags&0x200: return '接口声明'
    if re.search(r'/Car\w*Manager;',d): return 'SDK管理器'
    return '其他厂商类'

def dex(path,accept):
    b=path.read_bytes(); h=struct.unpack_from('<20I',b,32)
    base=h[19] if b.startswith(b'cdex') else 0
    strs=[]
    for i in range(h[6]):
        p=struct.unpack_from('<I',b,h[7]+4*i)[0]+base; _,p=uleb(b,p)
        strs.append(b[p:b.index(0,p)].decode('utf-8','replace'))
    types=[strs[struct.unpack_from('<I',b,h[9]+4*i)[0]] for i in range(h[8])]
    protos=[]
    for i in range(h[10]):
        _,r,p=struct.unpack_from('<III',b,h[11]+12*i); a=[]
        if p:
            p+=base; count=struct.unpack_from('<I',b,p)[0]
            a=[types[struct.unpack_from('<H',b,p+4+2*j)[0]] for j in range(count)]
        protos.append('('+''.join(a)+')'+types[r])
    fields=[struct.unpack_from('<HHI',b,h[13]+8*i) for i in range(h[12])]
    methods=[struct.unpack_from('<HHI',b,h[15]+8*i) for i in range(h[14])]
    result=[]; origin=source(path)
    for i in range(h[16]):
        d=struct.unpack_from('<8I',b,h[17]+32*i); name=types[d[0]]
        if not accept(name): continue
        c={'descriptor':name,'name':jtype(name),'accessFlags':d[1], 'category':category(name,d[1]),
           'origin':origin,'classDefIndex':i,'methods':[],'fields':[], 'evidence':'静态声明；未据此确认车型支持或实车验收'}
        if d[6]:
            p=base+d[6]; counts=[]
            for _ in range(4): v,p=uleb(b,p); counts.append(v)
            static=[]
            for group,count in enumerate(counts[:2]):
                idx=0
                for _ in range(count):
                    v,p=uleb(b,p); idx+=v; flags,p=uleb(b,p); owner,t,n=fields[idx]
                    assert types[owner]==name
                    f={'name':strs[n],'descriptor':types[t],'type':jtype(types[t]),'accessFlags':flags,'static':group==0}
                    c['fields'].append(f)
                    if group==0: static.append(f)
            for count in counts[2:]:
                idx=0
                for _ in range(count):
                    v,p=uleb(b,p); idx+=v; flags,p=uleb(b,p); code,p=uleb(b,p); owner,t,n=methods[idx]
                    assert types[owner]==name
                    desc=protos[t]
                    c['methods'].append({'name':strs[n],'descriptor':desc,'signature':signature(strs[n],desc),'accessFlags':flags,'hasCode':bool(code)})
            values=[]
            if d[7]:
                q=base+d[7]; count,q=uleb(b,q)
                for _ in range(count): v,q=encoded(b,q); values.append(v)
            for j,f in enumerate(static):
                v=values[j] if j<len(values) else 0
                if f['descriptor'] in ('I','J','S','B','Z','C') and f['accessFlags']&0x18==0x18 and isinstance(v,(int,bool)):
                    f['value']=v
                if f['name'] in ('DESCRIPTOR','kInterfaceName') and isinstance(v,dict) and v.get('indexType')==0x17:
                    f['value']=strs[v['index']]
        result.append(c)
    return result

def smali_flags(mod):
    mapping={'public':1,'private':2,'protected':4,'static':8,'final':16,'synchronized':32,'volatile':64,'bridge':64,'transient':128,'varargs':128,'native':256,'interface':512,'abstract':1024,'strictfp':2048,'synthetic':4096,'annotation':8192,'enum':16384,'constructor':65536,'declared-synchronized':131072}
    return sum(mapping.get(x,0) for x in mod.split())

def smali(path):
    text=path.read_text(); m=re.search(r'^\.class (?:(.*?) )?(L[^;]+;)$',text,re.M)
    if not m: return None
    mods,name=m.groups(); mods=mods or ''; flags=smali_flags(mods)
    c={'descriptor':name,'name':jtype(name),'accessFlags':flags,'category':category(name,flags),'origin':source(path),'methods':[],'fields':[],'evidence':'既有 system-analysis 静态 smali 声明；运行状态待验证'}
    for ln,line in enumerate(text.splitlines(),1):
        m=re.match(r'\.method\s+(.+?)\s+(\S+)(\([^ ]+)',line)
        if m:
            mod,n,desc=m.groups(); f=smali_flags(mod)
            c['methods'].append({'name':n,'descriptor':desc,'signature':signature(n,desc),'accessFlags':f,'line':ln,'hasCode':'abstract' not in mod})
        m=re.match(r'\.field\s+(.+?)\s+([^ :]+):([^ ]+)(?: = (.*))?',line)
        if m:
            mod,n,t,v=m.groups(); f={'name':n,'descriptor':t,'type':jtype(t),'static':'static' in mod,'accessFlags':smali_flags(mod),'line':ln}
            if v and t=='I':
                try: f['value']=int(v,0)
                except ValueError: pass
            if n=='DESCRIPTOR' and v: f['value']=json.loads(v)
            c['fields'].append(f)
    return c

classes=[]
qisi=ROOT/'work/update-package-20261009/format-audit/QisiAdapterService'
for path in sorted(qisi.glob('*.cdex')):
    classes+=dex(path,lambda n:n.startswith(('Lltd/qisi/adapterproxy/','Lltd/qisi/systemmanager/')))
faw=ROOT/'work/update-package-20261009/format-audit/faw-framework'
for path in sorted(faw.glob('*.cdex')): classes+=dex(path,lambda n:n.startswith(('Lfaw/','Lalfusos/')))
framework=ROOT/'work/system-analysis-20261008/vdex-recovered/framework'
for folder in sorted(framework.glob('boot-alfusos*')):
    for path in sorted(folder.glob('*.cdex')): classes+=dex(path,lambda n:n.startswith(('Lalfusos/','Lfaw/')))
# Supplement services/callbacks from the OEM application's recovered normal DEX.
smali_root=ROOT/'work/system-analysis-20261008/oem-apps'
patterns=[('Carlink','**/ltd/qisi/systemmanager/*.smali'),('AlfusSystemServer','**/faw/car/link/*.smali'),('AlfusController','**/com/alfus/controller/power/PowerController*.smali'),('Carlink','**/android/car/media/CarAudio*.smali'),('Carlink','**/android/car/ICar*.smali'),('Carlink','**/android/car/Car.smali'),('Carlink','**/android/car/hardware/property/*.smali'),('Carlink','**/android/car/hardware/power/*.smali'),('Carlink','**/android/car/hardware/CarProperty*.smali'),('Carlink','**/android/car/hardware/CarSensor*.smali')]
known={c['descriptor'] for c in classes}
for app,pattern in patterns:
    for path in sorted((smali_root/app).glob(pattern)):
        c=smali(path)
        if c and c['descriptor'] not in known: classes.append(c); known.add(c['descriptor'])
# Exact transaction linkage: use Stub constants, never declaration/list order.
by_name={c['descriptor']:c for c in classes}; transactions=[]
for c in classes:
    if not c['descriptor'].endswith('$Stub;'): continue
    parent=c['descriptor'][:-6]+';'; contract=by_name.get(parent)
    if not contract: continue
    for f in c['fields']:
        if f['name'].startswith('TRANSACTION_') and 'value' in f:
            name=f['name'][12:]; targets=[m for m in contract['methods'] if m['name']==name]
            for m in targets:
                m['transaction']=f['value']; m['transactionEvidence']={'origin':c['origin'],'classDefIndex':c.get('classDefIndex'),'field':f['name']}
                transactions.append({'interface':contract['name'],'method':name,'transaction':f['value'],'signature':m['signature'],'origin':c['origin'],'field':f['name']})
# Stream only declaration locations and unambiguous queryBinder literal arguments.
modules=[]
for path in [qisi/'bytecode.dis.txt', faw/'bytecode.dis.txt']+[p/'bytecode.dis.txt' for p in sorted(framework.glob('boot-alfusos*'))]:
    if not path.exists(): continue
    origin=source(path); current=None; method=None; regs={}; pending=None
    for ln,line in enumerate(path.open(errors='replace'),1):
        m=re.match(r"  class #\d+: .*\('(L[^']+)'\)",line)
        if m:
            current=by_name.get(m[1]); method=None; regs={}; pending=None
            if current: current['declaration']={'origin':origin,'line':ln}
            continue
        if not current: continue
        m=re.match(r'   (?:direct|virtual)_method #\d+: (\S+) (\(.*)',line)
        if m:
            method=(m[1],m[2].strip()); regs={}; pending=None
            for item in current['methods']:
                if (item['name'],item['descriptor'])==method: item['line']=ln
            continue
        m=re.search(r'const(?:/4|/16)? (v\d+), #int (-?\d+)',line)
        if m: regs[m[1]]=int(m[2])
        if 'IVehicleRemoteClienter;.queryBinder:(I)' in line:
            m=re.search(r'invoke-interface \{([^}]+)\}',line)
            if m:
                reg=m[1].split(',')[-1].strip()
                pending={'module':regs.get(reg),'manager':current['name'],'origin':origin,'line':ln}
        if pending and '.asInterface:(Landroid/os/IBinder;)' in line:
            m=re.search(r'(Lltd/qisi/adapterproxy/[^ ;]+)\$Stub;\.asInterface',line)
            if m and pending['module'] is not None:
                pending['interface']=jtype(m[1]+';'); modules.append(pending)
            pending=None
        # A register overwritten after a literal is no longer an established constant.
        if '|000' not in line or 'const' not in line:
            m=re.search(r'\|[0-9a-f]+: (?:move-result\S*|move-object\S*|iget\S*|sget\S*) (v\d+)',line)
            if m: regs.pop(m[1],None)
# identical rematerialized disassembly lines must not duplicate catalog entries
modules=list({(m['module'],m['interface'],m['manager']):m for m in modules}.values())
classes.sort(key=lambda c:c['name']); transactions.sort(key=lambda t:(t['interface'],t['transaction']))
counts=collections.Counter(c['category'] for c in classes)
summary={'classes':len(classes),'declaredMethods':sum(len(c['methods']) for c in classes),'interfaces':sum(bool(c['accessFlags']&0x200) for c in classes),'exactBinderMethods':len(transactions),'moduleMappings':len(modules),'categories':dict(counts),'qisiCarPlayGroups':sum(c['descriptor'].startswith('Lltd/qisi/adapterproxy/phonelink/QISICarPlayType$') and c['accessFlags']&0x200!=0 for c in classes)}
catalog={'schemaVersion':1,'date':'2026-10-15','scope':'Recovered 3222 Qisi/FAW declarations; earlier system-analysis Alfus/HIDL and OEM system contracts (source versions remain separate). Static evidence only unless the handbook states otherwise. Not an OEM support guarantee.','summary':summary,'classes':classes,'modules':modules}
(OUT/'catalog/interfaces.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
(OUT/'catalog/source-index.json').write_text(json.dumps(list(SOURCES.values()),ensure_ascii=False,indent=2)+'\n')
def csvout(name,fields,rows):
    with (OUT/'catalog'/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fields); w.writeheader(); w.writerows({k:r.get(k,'') for k in fields} for r in rows)
csvout('methods.csv',['class','category','method','signature','descriptor','transaction','origin','line','accessFlags'],[dict(m,method=m['name'],category=c['category'],origin=c['origin'],**{'class':c['name']}) for c in classes for m in c['methods']])
csvout('binder-transactions.csv',['interface','method','transaction','signature','origin','field'],transactions)
csvout('module-map.csv',['module','interface','manager','origin','line'],modules)
csvout('fields-and-constants.csv',['class','category','name','type','descriptor','value','static','accessFlags','origin'],[dict(f,category=c['category'],origin=c['origin'],**{'class':c['name']}) for c in classes for f in c['fields']])
print(json.dumps(summary,ensure_ascii=False,indent=2))
print('Source files:',len(SOURCES))
