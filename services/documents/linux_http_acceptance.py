"""Actual private HTTP -> owned worker -> PDFBox/Paddle on generated English files.
No public owner route, real upload/scanner, user document or accuracy certification.
"""
import hashlib
import http.client
import json
from pathlib import Path
import secrets
import sys
import threading
sys.path.insert(0,'/document/src')
from document_server import configured_server
from linux_acceptance import request

def main():
    token=secrets.token_hex(32)
    server=configured_server(('127.0.0.1',0),{'DOCUMENT_SERVICE_ENABLED':'true','DOCUMENT_CORE_ENABLED':'true','DOCUMENT_SERVICE_TOKEN':token})
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    report={'passed':False,'checks':{},'samples':{},'real_user_accuracy_accepted':False}
    def check(name,condition):
        report['checks'][name]=bool(condition)
        if not condition:raise RuntimeError(name)
    def call(raw=None,auth=True,path='/extract',method='POST'):
        connection=http.client.HTTPConnection(*server.server_address,timeout=75)
        connection.request(method,path,body=raw,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if auth else {})})
        response=connection.getresponse();value=(response.status,json.loads(response.read()),response.getheader('Cache-Control'));connection.close();return value
    try:
        check('health_is_configuration_only',call(path='/health',method='GET')[1]['recognition']=='CONFIG_VERIFIED')
        check('private_token_required',call(request('native.pdf'),False)[:2]==(401,{'error':'unauthorized'}))
        check('url_query_refused',call(request('native.pdf'),path='/extract?url=x')[0]==404)
        for name in ('native.pdf','scan.pdf','image.png'):
            status,value,cache=call(request(name));report['samples'][name]=value
            check(name+'_source_bound_complete',status==200 and cache=='no-store' and value['all_pages_processed'] and value['content_sha256']==hashlib.sha256((Path('/fixtures')/name).read_bytes()).hexdigest())
            check(name+'_actual_content',any('123456' in p['text'] for p in value['pages']) and value['image_recognition_performed']==(name!='native.pdf'))
        check('invalid_document_safe_error',call(request('corrupt.pdf'))[:2]==(422,{'error':'INVALID_DOCUMENT'}))
        check('overpages_no_partial',call(request('overpages.pdf'))[:2]==(413,{'error':'LIMIT_EXCEEDED'}))
        server.jobs.acquire()
        try:check('singleflight_rejects_queue',call(request('native.pdf'))[0]==429)
        finally:server.jobs.release()
        check('child_reaped_between_requests',server.engine.pid is None)
        report['passed']=True
    finally:
        server.shutdown();server.server_close();thread.join(3)
        report['closed']=server.engine.closed and not thread.is_alive()
        report['passed']=report['passed'] and report['closed']
        print(json.dumps(report,ensure_ascii=False,allow_nan=False),flush=True)
    return 0 if report['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
