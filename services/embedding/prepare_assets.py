"""Explicit hash-verified public artifact preparation; never a service runtime dependency."""
import argparse
import hashlib
from pathlib import Path
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request,urlopen
from urllib.parse import urlparse
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_assets import records,verify,wheel_lock,digest

def part(row,start,end,target,opener):
    for attempt in range(3):
        try:
            request=Request(row['url']+f'?embedding_range={start}',headers={'Range':f'bytes={start}-{end}','Accept-Encoding':'identity','User-Agent':'autocare-offline-assets/1'})
            with opener(request,timeout=90) as response:
                final=urlparse(response.geturl())
                if final.scheme!='https' or final.username or final.password or response.status!=206 or response.headers.get('Content-Range')!=f"bytes {start}-{end}/{row['bytes']}":raise ValueError('Embedding range response mismatch')
                raw=response.read(end-start+2)
            if len(raw)!=end-start+1:raise OSError('Embedding range interrupted')
            Path(target).write_bytes(raw);return
        except OSError:
            if attempt==2:raise

def download(row,target,opener=urlopen):
    target=Path(target)
    if target.exists() or target.is_symlink():raise ValueError('Embedding destination already exists')
    step=4*1024**2
    if row['bytes']>step:
        with tempfile.TemporaryDirectory(prefix='embedding-parts-',dir=target.parent) as temporary:
            folder=Path(temporary);starts=list(range(0,row['bytes'],step))
            with ThreadPoolExecutor(max_workers=4) as pool:list(pool.map(lambda start:part(row,start,min(start+step,row['bytes'])-1,folder/str(start),opener),starts))
            with target.open('xb') as output:
                for start in starts:output.write((folder/str(start)).read_bytes())
    else:
        for attempt in range(3):
            try:
                request=Request(row['url'],headers={'Accept-Encoding':'identity','User-Agent':'autocare-offline-assets/1'})
                with opener(request,timeout=90) as response,target.open('xb') as output:
                    final=urlparse(response.geturl())
                    if response.status!=200 or final.scheme!='https' or final.username or final.password:raise ValueError('Insecure resource response')
                    raw=response.read(row['bytes']+1)
                    if len(raw)!=row['bytes']:raise OSError('Embedding resource interrupted')
                    output.write(raw)
                break
            except OSError:
                target.unlink(missing_ok=True)
                if attempt==2:raise
    if target.stat().st_size!=row['bytes'] or digest(target)!=row['sha256']:raise ValueError('Embedding asset checksum mismatch')

def prepare(root,kind,locks=None,fetch=download):
    root=Path(root).absolute();kwargs={} if locks is None else {'locks':locks}
    if root.exists() or root.is_symlink():return verify(root,kind,**kwargs)
    data=records(kind,**kwargs);root.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='embedding-assets-',dir=root.parent) as temporary:
        stage=Path(temporary)/'assets';stage.mkdir()
        for row in data['files']:
            target=stage/row['filename'];target.parent.mkdir(parents=True,exist_ok=True);fetch(row,target)
        if kind=='wheels':(stage/'requirements.lock').write_text(wheel_lock(data['files']),encoding='utf-8')
        verify(stage,kind,**kwargs);stage.rename(root)
    return data

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--kind',choices=('model','wheels'),required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--download-public-assets',action='store_true');args=parser.parse_args(argv)
    data=prepare(args.output,args.kind) if args.download_public_assets else verify(args.output,args.kind)
    print(f"{args.kind}: {len(data['files'])} resources verified; no inference or accuracy acceptance")
if __name__=='__main__':main()
