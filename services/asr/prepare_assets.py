"""Prepare and verify the exact public ASR assets; never resolve versions at runtime."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import unquote, urlparse
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate resource declaration')
        result[key] = value
    return result


def records(kind):
    if kind not in ('wheels', 'model'):
        raise ValueError('Unknown resource kind')
    name = 'runtime-wheels-linux.json' if kind == 'wheels' else 'models.lock.json'
    data = json.loads((HERE / name).read_text(encoding='utf-8'), object_pairs_hook=unique)
    rows = data['files']
    if data.get('version') != 1 or not isinstance(rows, list) or not 1 <= len(rows) <= 128:
        raise ValueError('Invalid resource inventory')
    if kind == 'model' and (data.get('repository') != 'Systran/faster-whisper-small' or
            not re.fullmatch('[a-f0-9]{40}', data.get('revision', '')) or data.get('license') != 'MIT'):
        raise ValueError('Invalid model declaration')
    names = set()
    for row in rows:
        name = row['filename']
        url = urlparse(row['url'])
        if not re.fullmatch(r'[A-Za-z0-9_.+-]{1,200}', name) or name in ('.', '..') or name in names or \
                not re.fullmatch('[a-f0-9]{64}', row['sha256']) or type(row['bytes']) is not int or \
                not 0 < row['bytes'] <= 1024 * 1024 * 1024 or url.scheme != 'https' or url.query or url.fragment:
            raise ValueError('Invalid resource bounds')
        if kind == 'wheels':
            if url.netloc != 'files.pythonhosted.org' or not url.path.startswith('/packages/') or \
                    not name.endswith('.whl') or unquote(url.path.rsplit('/', 1)[-1]) != name or \
                    not re.fullmatch('[A-Za-z0-9_.-]+', row['name']) or not re.fullmatch('[A-Za-z0-9_.+!-]+', row['version']):
                raise ValueError('Invalid wheel source')
        elif url.netloc != 'huggingface.co' or url.path != f"/Systran/faster-whisper-small/resolve/{data['revision']}/{name}":
            raise ValueError('Invalid model source')
        names.add(name)
    if sum(row['bytes'] for row in rows) > 2 * 1024 ** 3:
        raise ValueError('Resource total budget')
    if kind == 'model' and not {'model.bin', 'config.json', 'tokenizer.json', 'vocabulary.txt'} <= names:
        raise ValueError('Required model files missing')
    return rows


def wheel_lock(rows):
    return ''.join(f"{row['name']}=={row['version']} --hash=sha256:{row['sha256']}\n" for row in rows)


def verify(root, kind):
    root = Path(root)
    rows = records(kind)
    expected = {row['filename'] for row in rows}
    if kind == 'wheels':
        expected.add('requirements.lock')
    if root.is_symlink() or not root.is_dir() or {p.name for p in root.iterdir()} != expected:
        raise ValueError('Resource directory inventory mismatch')
    for row in rows:
        path = root / row['filename']
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row['bytes'] or checksum(path) != row['sha256']:
            raise ValueError('Resource checksum mismatch')
    if kind == 'wheels':
        path = root / 'requirements.lock'
        if path.is_symlink() or not path.is_file() or path.read_text(encoding='utf-8') != wheel_lock(rows):
            raise ValueError('Wheel installation lock mismatch')
        tracked = (HERE / 'requirements-runtime-linux.lock.txt').read_text(encoding='utf-8')
        lines = ''.join(line + '\n' for line in tracked.splitlines() if line and not line.startswith('#'))
        if lines != wheel_lock(rows):
            raise ValueError('Tracked installation lock mismatch')
    return len(rows)


def download_part(row, start, end, output, opener=urlopen):
    url = row['url']
    if urlparse(url).netloc == 'huggingface.co':
        # Keep cache entries distinct where intermediaries ignore the Range header.
        url += f'?download=true&asr_range={start}'
    for attempt in range(3):
        try:
            request = Request(url, headers={'Range': f'bytes={start}-{end}', 'Accept-Encoding': 'identity'})
            with opener(request, timeout=45) as response:
                final = urlparse(response.geturl())
                if final.scheme != 'https' or final.username or final.password or response.status != 206 or \
                        response.headers.get('Content-Range') != f"bytes {start}-{end}/{row['bytes']}":
                    raise ValueError('Asset range response mismatch')
                raw = response.read(end-start+2)
                if len(raw) != end-start+1:
                    raise ValueError('Truncated asset range')
            output.write_bytes(raw)
            return
        except Exception:
            if attempt == 2:
                raise


def download(row, output, opener=urlopen):
    # Declared public URLs only, bounded bytes and final hash; no URLs from user requests.
    step = 4*1024*1024
    if row['bytes'] > step:
        with tempfile.TemporaryDirectory(prefix='asr-parts-', dir=output.parent) as temporary:
            folder = Path(temporary)
            starts = range(0, row['bytes'], step)
            def fetch(start):
                download_part(row, start, min(start+step, row['bytes'])-1, folder/str(start), opener)
            with ThreadPoolExecutor(max_workers=8) as pool:
                list(pool.map(fetch, starts))
            with output.open('xb') as target:
                for start in starts:
                    target.write((folder/str(start)).read_bytes())
        if output.stat().st_size != row['bytes'] or checksum(output) != row['sha256']:
            raise ValueError('Downloaded asset mismatch')
        return
    with opener(row['url'], timeout=90) as response, output.open('xb') as target:
        final = urlparse(response.geturl())
        if final.scheme != 'https' or final.username or final.password:
            raise ValueError('Insecure asset redirect')
        if response.status != 200:
            raise ValueError('Unexpected asset response')
        count = 0
        while chunk := response.read(min(1024 * 1024, row['bytes'] - count + 1)):
            count += len(chunk)
            if count > row['bytes']:
                raise ValueError('Asset byte budget')
            target.write(chunk)
    if count != row['bytes'] or checksum(output) != row['sha256']:
        raise ValueError('Downloaded asset mismatch')


def prepare(root, kind):
    root = Path(root).absolute()
    if root.exists() or root.is_symlink():
        return verify(root, kind)
    rows = records(kind)
    root.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='asr-assets-', dir=root.parent) as temporary:
        stage = Path(temporary) / 'assets'
        stage.mkdir()
        for row in rows:
            download(row, stage / row['filename'])
        if kind == 'wheels':
            (stage / 'requirements.lock').write_text(wheel_lock(rows), encoding='utf-8')
        count = verify(stage, kind)
        stage.rename(root)
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=('wheels', 'model'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--download-public-assets', action='store_true')
    args = parser.parse_args()
    count = prepare(args.output, args.kind) if args.download_public_assets else verify(args.output, args.kind)
    print(f'{args.kind}: {count} files verified; no inference or accuracy acceptance')


if __name__ == '__main__':
    main()
