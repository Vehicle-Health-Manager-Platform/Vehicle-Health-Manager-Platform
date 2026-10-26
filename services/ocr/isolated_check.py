"""Real warm worker + private HTTP check using only a generated mileage image."""
import argparse
import base64
from http.client import HTTPConnection
from importlib.metadata import version
import io
import json
import os
from pathlib import Path
import secrets
import sys
import threading
from model_assets import verify_models
from runtime_check import VERSIONS, checked_result

def run(root):
    dependencies={key:version(key) for key in VERSIONS}
    if dependencies!=VERSIONS:raise ValueError('Runtime versions mismatch')
    models=verify_models(root)
    cache=Path(root).resolve().parent/'ocr-runtime-cache'
    for name,child in [('PADDLE_PDX_CACHE_HOME','paddlex'),('PADDLE_HOME','paddle'),('HF_HOME','huggingface'),('MODELSCOPE_CACHE','modelscope'),('XDG_CACHE_HOME','xdg')]:os.environ.setdefault(name,str(cache/child))
    sys.path.insert(0,str(Path(__file__).parent/'src'))
    from worker import ProcessRecognizer
    from server import create_server
    from PIL import Image,ImageDraw,ImageFont
    worker=ProcessRecognizer(models['PP-OCRv5_mobile_det'],models['PP-OCRv5_mobile_rec'])
    server=thread=None;token=secrets.token_hex(32)
    try:
        pid=worker.pid;profile=worker.resource_profile
        server=create_server(('127.0.0.1',0),worker,token)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        with Image.new('RGB',(1100,220),'white') as image:
            ImageDraw.Draw(image).text((40,65),'ODO 123456 km',font=ImageFont.load_default(size=80),fill='black')
            encoded=io.BytesIO();image.save(encoded,format='PNG')
        body=json.dumps({'scene':'mileage','image_base64':base64.b64encode(encoded.getvalue()).decode()}).encode()
        def call(raw=None):
            connection=HTTPConnection('127.0.0.1',server.server_port,timeout=15)
            try:
                connection.request('GET' if raw is None else 'POST','/health' if raw is None else '/recognize',raw,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
                response=connection.getresponse();assert response.getheader('Cache-Control')=='no-store'
                return response.status,json.loads(response.read())
            finally:connection.close()
        assert call()[1]['recognition']=='READY'
        statuses=[]
        for _ in range(2):
            status,result=call(body);assert status==200;checked_result(result);statuses.append(result['status'])
            assert worker.pid==pid
        assert call(b'{')[0]==422 and worker.pid==pid
        assert call()[1]['recognition']=='READY'
    finally:
        if server:server.shutdown();server.server_close();thread.join(3)
        else:worker.close()
    assert worker.status=='CLOSED' and worker.pid is None
    return {'passed':True,'dependencies':dependencies,'real_worker_http_requests':2,'statuses':statuses,
        'warm_process_reused':True,'invalid_image_did_not_reload':True,'worker_closed':True,'server_thread_stopped':not thread.is_alive(),
        'os_limits':profile,'inference_deadline_seconds':8,'startup_deadline_seconds':30,'cpu_threads':2,
        'owner_instrument_accuracy_accepted':False,'wechat_journey_accepted':False,'linux_runtime_accepted':False}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--models-root',required=True,type=Path);parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    try:report=run(args.models_root)
    except Exception as error:
        report={'passed':False,'error_kind':type(error).__name__}
        args.report.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Isolated OCR check failed',file=sys.stderr);raise SystemExit(1)
    args.report.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('Real isolated worker HTTP check passed; synthetic image only')
