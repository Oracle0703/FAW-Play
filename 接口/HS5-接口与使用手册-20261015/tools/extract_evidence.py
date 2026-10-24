#!/usr/bin/env python3
"""Extract a compact LF-indexed evidence selection. Requires the original local workspace."""
from pathlib import Path
import argparse,hashlib,json,re,collections
p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);root=p.parse_args().workspace.resolve()
out=Path(__file__).resolve().parents[1];dest=out/'evidence/excerpts';dest.mkdir(exist_ok=True)
index=[]
qisi='work/update-package-20261009/format-audit/QisiAdapterService/bytecode.dis.txt'
wanted={
 'Lltd/qisi/adapterproxy/data/QisiNaviInfo;','Lltd/qisi/adapterproxy/data/QisiFullMapInfo;',
 'Lltd/qisi/adapterproxy/data/QisiMusicInfo;','Lltd/qisi/adapterproxy/data/QisiCallInfo;',
 'Lltd/qisi/adapterproxy/ICarInstrumentRelatedManager;','Lltd/qisi/adapterproxy/ICarClusterUpdateManager;',
 'Lltd/qisi/adapterproxy/CarInstrumentRelatedManagerSDKImpl;',
}
current=None;chunk=[];start=0
with (root/qisi).open(errors='replace') as f:
 for ln,line in enumerate(f,1):
  m=re.match(r"  class #\d+: .*\('(L[^']+)'\)",line)
  if m:
   if current in wanted:
    target=dest/(current[1:-1].replace('/','_')+'.txt')
    target.write_text(f'# Source: {qisi}\n# Original LF start: {start}\n'+''.join(chunk))
    index.append({'source':qisi,'lfStart':start,'descriptor':current,'copy':str(target.relative_to(out))})
   current=m[1];start=ln;chunk=[]
  if current in wanted:chunk.append(line)
# Pull every method cited by the later OEM UI semantic audit.
requests=collections.defaultdict(set)
def walk(v,inherited=None):
 if isinstance(v,dict):
  src=v.get('source',inherited)
  if isinstance(src,str) and src.endswith('bytecode.dis.txt') and isinstance(v.get('lfStart'),int): requests[src].add(v['lfStart'])
  for x in v.values():walk(x,src)
 elif isinstance(v,list):
  for x in v:walk(x,inherited)
walk(json.loads((out/'evidence/hs5-comfort-ui-semantics-3222.json').read_text()))
for rel,starts in requests.items():
 path=root/rel;chunks=[];active=None;chunk=[]
 for ln,line in enumerate(path.open(errors='replace'),1):
  if active is not None and (re.match(r'   (direct|virtual)_method #',line) or line.startswith('  class #')):
   chunks.append((active,chunk));active=None;chunk=[]
  if ln in starts:active=ln
  if active is not None:chunk.append(line)
 if active is not None:chunks.append((active,chunk))
 target=dest/'3222-HVAC-UI-cited-methods.txt'
 target.write_text('# Source: '+rel+'\n'+''.join('\n# Original LF start: '+str(n)+'\n'+''.join(a) for n,a in chunks))
 index.append({'source':rel,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'methodsRequested':len(starts),'methodsExtracted':len(chunks),'lfStarts':sorted(starts),'copy':str(target.relative_to(out))})
(out/'evidence/excerpt-index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n')
print('Evidence excerpts:',len(index),'files')
