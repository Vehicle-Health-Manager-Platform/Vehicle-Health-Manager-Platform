"""Record already downloaded local assets and an installed, operator-reviewed runtime. No download."""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re
import sys
sys.path.insert(0,str(Path(__file__).parent/'src'))
from asr_model import REQUIRED,PACKAGES,inventory,digest_file
def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,required=True);parser.add_argument('--revision',required=True);parser.add_argument('--license-reference',required=True);parser.add_argument('--accept-license',action='store_true');parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args(argv);root=args.root.resolve(strict=True)
    if not re.fullmatch('[a-f0-9]{40}',args.revision) or not args.accept_license:raise SystemExit('Exact revision and explicit reviewed license acceptance required')
    files={name:digest_file(path) for name,path in inventory(root,args.output.resolve()).items()}
    if not REQUIRED<=set(files):raise SystemExit('Required local model files missing')
    packages={key:importlib.metadata.version(key) for key in sorted(PACKAGES)}
    if packages['faster-whisper']!='1.2.1':raise SystemExit('Expected faster-whisper1.2.1')
    args.output.write_text(json.dumps({'version':1,'repository':'Systran/faster-whisper-small','revision':args.revision,'license_reference':args.license_reference,'license_accepted':True,'packages':packages,'files':files},indent=2)+'\n',encoding='utf-8')
if __name__=='__main__':main()
