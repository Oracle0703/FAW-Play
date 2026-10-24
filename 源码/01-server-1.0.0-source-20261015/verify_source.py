#!/usr/bin/env python3
from pathlib import Path
import hashlib, json, sys
root = Path(__file__).resolve().parent
manifest = json.loads((root / 'manifest.json').read_text())
expected = {f['path']: f for f in manifest['files']}
ignored = {'.gradle', '.kotlin', 'build', '.cxx', '.externalNativeBuild', '.git', '.idea', '__pycache__', '.private'}
actual = {p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()
          and not any(x in ignored for x in p.relative_to(root).parts)
          and p != root / 'manifest.json'
          and p.name not in {'.DS_Store', 'local.properties'}}
missing = sorted(expected.keys() - actual)
extra = sorted(actual - expected.keys())
changed = [n for n in sorted(expected.keys() & actual)
           if hashlib.sha256((root / n).read_bytes()).hexdigest() != expected[n]['sha256']]
result = {'files': len(expected), 'missing': missing, 'extra': extra, 'changed': changed,
          'ok': not (missing or extra or changed)}
print(json.dumps(result, ensure_ascii=False, indent=2))
sys.exit(0 if result['ok'] else 1)
