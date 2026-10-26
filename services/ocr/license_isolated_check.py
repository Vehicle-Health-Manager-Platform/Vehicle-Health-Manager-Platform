"""Opt-in real Paddle + isolated worker/HTTP observation on generated images only.

The exit code verifies the runtime path and negative controls. Per-field matches
are reported separately, including failures; neither is real-certificate acceptance.
Requires pre-verified local models and an explicit font with Chinese glyphs.
"""
import argparse
import base64
import hashlib
from http.client import HTTPConnection
from importlib.metadata import version
import io
import json
import os
from pathlib import Path
import secrets
import sys
import threading
import time
from model_assets import verify_models
from runtime_check import VERSIONS, checked_result


def run(root, font_path, output):
    dependencies={key:version(key) for key in VERSIONS}
    if dependencies!=VERSIONS:raise ValueError('Runtime versions mismatch')
    models=verify_models(root)
    if not font_path.is_file():raise ValueError('Explicit Chinese font missing')
    cache=Path(root).resolve().parent/'ocr-runtime-cache'
    for name,child in [('PADDLE_PDX_CACHE_HOME','paddlex'),('PADDLE_HOME','paddle'),('HF_HOME','huggingface'),('MODELSCOPE_CACHE','modelscope'),('XDG_CACHE_HOME','xdg')]:os.environ.setdefault(name,str(cache/child))
    os.environ['PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK']='True'
    sys.path.insert(0,str(Path(__file__).parent/'src'))
    from worker import ProcessRecognizer
    from server import create_server
    from PIL import Image, ImageDraw, ImageFont
    font=ImageFont.truetype(str(font_path),64)
    vin='LTEST223456789012'
    cases=[
        ('guangdong','license',['SYNTHETIC TEST - NOT A REAL LICENSE','号牌号码：粤B12345','车辆识别代号：'+vin,'所有人：合成私有姓名','住址：合成私有地址'],{'plate':['粤B12345'],'vin':[vin]}),
        ('shanghai','license',['SYNTHETIC TEST - NOT A REAL LICENSE','号牌号码：沪A12345','车辆识别代号：'+vin],{'plate':['沪A12345'],'vin':[vin]}),
        ('unlabelled','license',['SYNTHETIC TEST - NOT A REAL LICENSE','沪A12345',vin],{'plate':[],'vin':[]}),
        ('blank','license',[],{'plate':[],'vin':[]}),
        ('mileage-control','mileage',['ODO 123456 km'],None),
    ]
    rows=[];server=thread=None
    worker=ProcessRecognizer(models['PP-OCRv5_mobile_det'],models['PP-OCRv5_mobile_rec'])
    try:
        pid=worker.pid;profile=worker.resource_profile
        token=secrets.token_hex(32)
        server=create_server(('127.0.0.1',0),worker,token)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        for name,scene,lines,expected in cases:
            with Image.new('RGB',(1800,680),'white') as image:
                draw=ImageDraw.Draw(image)
                for index,line in enumerate(lines):draw.text((35,30+index*120),line,font=font,fill='black')
                stream=io.BytesIO();image.save(stream,format='PNG');image_bytes=stream.getvalue()
            (output/('license-synthetic-'+name+'.png')).write_bytes(image_bytes)
            body=json.dumps({'scene':scene,'image_base64':base64.b64encode(image_bytes).decode('ascii')}).encode()
            connection=HTTPConnection('127.0.0.1',server.server_port,timeout=15)
            started=time.monotonic()
            try:
                connection.request('POST','/recognize',body,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
                response=connection.getresponse();status=response.status;result=json.loads(response.read())
                if response.getheader('Cache-Control')!='no-store':raise ValueError('No-store response required')
            finally:connection.close()
            if status!=200 or result.get('scene')!=scene or result.get('requires_confirmation') is not True or result.get('image_recognition_performed') is not True:raise ValueError('Runtime protocol failure')
            if worker.pid!=pid:raise ValueError('Warm worker unexpectedly replaced')
            serialized=json.dumps(result,ensure_ascii=False)
            if '合成私有姓名' in serialized or '合成私有地址' in serialized:raise ValueError('Private text returned')
            if scene=='mileage':
                checked_result(result);observed={'mileage_km':[row['mileage_km'] for row in result['candidates']]};matched=True
            else:
                observed={field['key']:[row['value'] for row in field['candidates']] for field in result['fields']}
                matched=observed==expected
                if name in ('unlabelled','blank') and (not matched or result['status']!='NO_CANDIDATE'):raise ValueError('Negative control unexpectedly recognized')
            rows.append({'case':name,'image_sha256':hashlib.sha256(image_bytes).hexdigest(),'scene':scene,'http_status':status,
                'duration_seconds':round(time.monotonic()-started,3),'expected_fields':expected,'observed_fields':observed,'all_expected_fields_matched':matched,'result':result})
    finally:
        if server:server.shutdown();server.server_close();thread.join(3)
        else:worker.close()
    if worker.status!='CLOSED' or worker.pid is not None or thread.is_alive():raise ValueError('Worker/server cleanup failed')
    sources={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in (Path(__file__).parent/'src').glob('*.py')}
    model_hashes={Path(directory).name+'/'+path.name:hashlib.sha256(path.read_bytes()).hexdigest() for directory in models.values() for path in Path(directory).iterdir() if path.is_file()}
    return {'runtime_path_passed':True,'all_synthetic_fields_matched':all(row['all_expected_fields_matched'] for row in rows),
        'real_paddle_used':True,'dependencies':dependencies,'models_verified_locally':True,'model_sha256':model_hashes,'source_sha256':sources,
        'font_sha256':hashlib.sha256(font_path.read_bytes()).hexdigest(),'private_http_requests':len(rows),'warm_worker_reused':True,'worker_closed':True,'http_server_stopped':True,
        'os_limits':profile,'inference_deadline_seconds':8,'startup_deadline_seconds':30,'observations':rows,
        'authorized_real_license_accuracy_accepted':False,'wechat_device_accepted':False,'linux_runtime_accepted':False,
        'boundary':'Generated typography only. Field mismatches stay recorded; no model downloads or real certificate inputs. Windows Job limits do not verify Linux seccomp or outbound-network denial.'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models-root',required=True,type=Path)
    parser.add_argument('--font',required=True,type=Path)
    parser.add_argument('--report',required=True,type=Path)
    args=parser.parse_args()
    try:
        report=run(args.models_root,args.font,args.report.parent)
    except Exception as error:
        args.report.write_text(json.dumps({'runtime_path_passed':False,'error_kind':type(error).__name__,
            'authorized_real_license_accuracy_accepted':False,'wechat_device_accepted':False},indent=2)+'\n',encoding='utf-8')
        print('Synthetic license runtime check failed',file=sys.stderr);return 1
    args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Real Paddle runtime path passed; all synthetic fields matched='+str(report['all_synthetic_fields_matched'])+'; real certificates/device untested')
    return 0


if __name__=='__main__':raise SystemExit(main())
