"""Explicit isolated-runner bootstrap; not the production service entry point."""
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, '/app')
from prepare_manifest import main as manifest


def main():
    if os.environ.get('ASR_ISOLATED_ACCEPTANCE') != 'true':
        raise SystemExit('Explicit isolated acceptance environment required')
    lock = json.loads(Path('/workspace/services/asr/models.lock.json').read_text())
    target = '/tmp/asr-manifest.json'
    manifest(['--root', '/models', '--revision', lock['revision'], '--license-reference', lock['license_reference'],
        '--accept-license', '--output', target])
    os.environ.update({'ASR_SERVICE_ENABLED': 'true', 'ASR_MODEL_ROOT': '/models', 'ASR_MODEL_MANIFEST': target})
    os.execv(sys.executable, [sys.executable, '/app/asr_server.py'])


if __name__ == '__main__':
    main()
