#!/usr/bin/env python3
"""Whitelisted read-only HS5 diagnostics; does not read credentials, VIN/PDSN or Wi-Fi dumps."""
from pathlib import Path
import argparse, json, subprocess
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',help='Select an adb device; serial is not written into the report')
p.add_argument('--package',choices=['com.shihab.diplay','com.shilapi.xcertplay.hs5','com.shilapi.xcertplay'],default='com.shilapi.xcertplay.hs5')
p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
adb=['adb']+(['-s',a.serial] if a.serial else [])
def shell(*args):
    try:
        r=subprocess.run(adb+['shell',*args],capture_output=True,text=True,timeout=15)
        return {'exitCode':r.returncode,'output':r.stdout.strip(),'error':r.stderr.strip()}
    except (OSError,subprocess.TimeoutExpired) as e:
        return {'exitCode':None,'error':type(e).__name__}
props=['ro.build.version.release','ro.build.version.sdk','ro.product.model','ro.product.device','ro.board.platform','ro.product.cpu.abilist','ro.build.display.id','wifi.interface','wifi.direct.interface']
report={'scope':'read-only; no vehicle transactions or permission changes','properties':{k:shell('getprop',k) for k in props},'package':a.package}
r=shell('service','list')
if 'output' in r:
    r['output']='\n'.join(line for line in r['output'].splitlines() if any(t in line for t in ['link_mutex_service','smart_antenna_service','vendor_global_service','apple-server','car_service']))
report['selectedServices']=r
r=shell('dumpsys','package',a.package)
if 'output' in r:
    r['output']='\n'.join(line.strip() for line in r['output'].splitlines() if any(t in line for t in ['versionCode=','versionName=','targetSdk=','requested permissions:','install permissions:','runtime permissions:','android.permission.','android.car.permission.','granted=']))
report['packagePermissions']=r
report['appops']=shell('appops','get',a.package)
report['navigationMixing']=shell('settings','get','system','mixing_down_media_volume')
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Saved selected read-only diagnostics to',a.output)
