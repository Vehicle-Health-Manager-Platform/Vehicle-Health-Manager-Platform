"""Fetch only the locked official Linux wheels, with bounded byte ranges and final hashes."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import urlparse, unquote
from urllib.request import Request, urlopen, build_opener, ProxyHandler

LOCK = Path(__file__).with_name('runtime-wheels-linux.json')
PART = 1024 * 1024


def records():
    rows = json.loads(LOCK.read_text(encoding='utf-8'))['wheels']
    if not rows or len({row['filename'] for row in rows}) != len(rows): raise ValueError('Invalid wheel manifest')
    for row in rows:
        url = urlparse(row['url'])
        if not re.fullmatch(r'[A-Za-z0-9_.+-]+\.whl',row['filename']) or not re.fullmatch('[a-f0-9]{64}',row['sha256']) or \
            type(row['bytes']) is not int or not 0 < row['bytes'] <= 256*1024*1024 or url.scheme != 'https' or \
            url.netloc != 'files.pythonhosted.org' or not url.path.startswith('/packages/') or url.query or url.fragment or \
            unquote(url.path.rsplit('/',1)[-1]) != row['filename']:
            raise ValueError('Wheel manifest source or bounds invalid')
    return rows


def checksum(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        while data := source.read(PART): value.update(data)
    return value.hexdigest()


def verify_wheels(root):
    root = Path(root); rows = records()
    if root.is_symlink() or not root.is_dir() or {p.name for p in root.iterdir()} != {r['filename'] for r in rows}:
        raise ValueError('Wheel directory contents mismatch')
    for row in rows:
        path = root/row['filename']
        if path.is_symlink() or not path.is_file() or path.stat().st_size != row['bytes'] or checksum(path) != row['sha256']:
            raise ValueError('Wheel file checksum or type mismatch')
    return len(rows)


def download_part(row, start, end, destination, opener=urlopen):
    for attempt in range(3):
        try:
            request = Request(row['url'], headers={'Range':f'bytes={start}-{end}', 'Accept-Encoding':'identity'})
            with opener(request, timeout=90) as response:
                if response.status != 206 or response.headers.get('Content-Range') != f"bytes {start}-{end}/{row['bytes']}":
                    raise ValueError('Wheel range response mismatch')
                data = response.read(end-start+2)
                if len(data) != end-start+1: raise ValueError('Wheel range truncated')
            destination.write_bytes(data)
            return
        except Exception:
            if attempt == 2: raise


def prepare(output, direct=False, mirror_tuna=False):
    output = Path(output)
    if output.exists() or output.is_symlink(): return verify_wheels(output)
    output.parent.mkdir(parents=True,exist_ok=True)
    rows = records()
    opener = build_opener(ProxyHandler({})).open if direct else urlopen
    with tempfile.TemporaryDirectory(prefix='ocr-wheels-',dir=output.parent) as temporary:
        stage = Path(temporary)/'wheels'; stage.mkdir()
        parts = Path(temporary)/'parts'; parts.mkdir()
        def fetch(job):
            row,start,end = job
            if mirror_tuna: row = {**row, 'url':row['url'].replace('https://files.pythonhosted.org/','https://pypi.tuna.tsinghua.edu.cn/')}
            download_part(row,start,end,parts/(row['filename']+'.'+str(start)),opener)
        jobs = [(row,start,min(row['bytes']-1,start+PART-1)) for row in rows for start in range(0,row['bytes'],PART)]
        with ThreadPoolExecutor(max_workers=8) as pool: list(pool.map(fetch,jobs))
        for row in rows:
            with (stage/row['filename']).open('wb') as target:
                for start in range(0,row['bytes'],PART): target.write((parts/(row['filename']+'.'+str(start))).read_bytes())
        count = verify_wheels(stage)
        stage.rename(output)
    return count


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output',required=True,type=Path); parser.add_argument('--check',action='store_true'); parser.add_argument('--direct',action='store_true',help='Explicitly bypass system proxy; TLS verification remains enabled'); parser.add_argument('--mirror-tuna',action='store_true',help='Use TUNA for transport; official PyPI size and SHA256 still required'); args=parser.parse_args()
    count = verify_wheels(args.output) if args.check else prepare(args.output,args.direct,args.mirror_tuna)
    print('Linux wheels verified:',count,'; no inference or container started')
