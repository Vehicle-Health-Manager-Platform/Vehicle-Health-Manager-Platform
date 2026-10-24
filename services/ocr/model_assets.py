"""Prepare pinned local assets offline; never download during inference."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile

LOCK = Path(__file__).with_name('models.lock.json')

def model_lock():
    return json.loads(LOCK.read_text(encoding='utf-8'))['models']

def checked_bytes(path, record):
    if path.is_symlink() or not path.is_file() or path.stat().st_size != record['bytes']:
        raise ValueError('Model asset size or type mismatch')
    value = path.read_bytes()
    if hashlib.sha256(value).hexdigest() != record['sha256']:
        raise ValueError('Model asset checksum mismatch')
    return value

def verify_models(root):
    root = Path(root)
    for model in model_lock():
        directory = root / (model['name'] + '_infer')
        if directory.is_symlink() or not directory.is_dir() or {p.name for p in directory.iterdir()} != set(model['files']):
            raise ValueError('Model directory contents mismatch')
        for name, record in model['files'].items():
            checked_bytes(directory / name, record)
    return {model['name']: str(root / (model['name'] + '_infer')) for model in model_lock()}

def prepare_models(archives, output):
    archives, output = Path(archives), Path(output)
    if output.exists() or output.is_symlink():
        raise ValueError('Choose a new output directory; existing assets are never overwritten')
    # A fresh staging directory keeps failed validation from publishing partial models.
    with tempfile.TemporaryDirectory(prefix='ocr-assets-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'models'; stage.mkdir()
        for model in model_lock():
            archive = archives / (model['name'] + '.tar')
            verified = checked_bytes(archive, model)
            prefix = model['name'] + '_infer'
            with tarfile.open(fileobj=io.BytesIO(verified), mode='r:') as tar:
                members = tar.getmembers()
                expected = {prefix, *(prefix + '/' + name for name in model['files'])}
                if len(members) != len(expected) or {m.name for m in members} != expected:
                    raise ValueError('Model archive contents mismatch')
                directory = stage / prefix; directory.mkdir()
                for member in members:
                    if member.name == prefix:
                        if not member.isdir(): raise ValueError('Invalid model directory')
                        continue
                    name = member.name.split('/')[1]
                    if not member.isfile() or member.size != model['files'][name]['bytes']:
                        raise ValueError('Invalid model file')
                    with tar.extractfile(member) as source:
                        (directory / name).write_bytes(source.read())
        verify_models(stage)
        stage.rename(output)
    return verify_models(output)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archives', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check: verify_models(args.output)
    elif args.archives: prepare_models(args.archives, args.output)
    else: parser.error('--archives is required when preparing assets')
    print('OCR model assets verified; no inference or network performed')

if __name__ == '__main__':
    main()
