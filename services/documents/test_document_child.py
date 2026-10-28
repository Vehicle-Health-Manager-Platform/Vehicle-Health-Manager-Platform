"""Explicit subprocess test fixture; never selected by the production worker."""
import json
import os
from pathlib import Path
import sys
import time
sys.path.insert(0,str(Path(__file__).parent/'src'))
sys.path.insert(0,str(Path(__file__).parents[1]/'ocr/src'))
from worker import read_frame,write_frame
from document_protocol import MAX_BODY,request
from test_document_protocol import answer

if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='hang':time.sleep(20)
    else:
        read_frame(sys.stdin.buffer,4);raw=read_frame(sys.stdin.buffer,MAX_BODY);_,data=request(raw)
        if mode=='environment':
            assert not any(name in os.environ for name in ('DOCUMENT_SERVICE_TOKEN','HF_TOKEN','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','AWS_ACCESS_KEY_ID','UNRELATED_CREDENTIAL'))
            assert os.environ.get('PYTHONDONTWRITEBYTECODE')=='1' and os.environ.get('PYTHONPYCACHEPREFIX')=='/tmp/document-pycache'
        value=answer(data)
        if mode=='wrong_hash':value['content_sha256']='0'*64
        if mode=='crash':os._exit(9)
        Path('owned-temporary-fixture').write_text('synthetic')
        write_frame(sys.stdout.buffer,json.dumps({'kind':'ok','result':value}).encode())
