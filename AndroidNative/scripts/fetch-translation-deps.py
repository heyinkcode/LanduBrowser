"""Fetch the pinned on-device translator into the ignored integration cache."""
import hashlib
import json
from pathlib import Path
import urllib.request


root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'translation-dependencies.json').read_text(encoding='utf-8'))
cache = root / '.local/translation'
cache.mkdir(parents=True, exist_ok=True)
for artifact in lock['artifacts']:
    target = cache / artifact['name']
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == artifact['sha256']:
        continue
    temporary = target.with_suffix(target.suffix + '.part')
    try:
        with urllib.request.urlopen(artifact['url'], timeout=120) as response:
            payload = response.read()
        if hashlib.sha256(payload).hexdigest() != artifact['sha256']:
            raise RuntimeError('Translation dependency checksum mismatch: ' + artifact['name'])
        temporary.write_bytes(payload)
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
print('Pinned translation dependencies verified')
