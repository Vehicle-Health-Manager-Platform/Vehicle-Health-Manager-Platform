"""Owned acceptance transport only: loopback -> Docker stdin -> private actual HTTP.
No model output is synthesized. No published Docker port or arbitrary target URL.
"""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import subprocess
import threading

TOKEN='synthetic-embedding-private-test-token'
FORWARD="""import http.client,json,sys
v=json.load(sys.stdin)
c=http.client.HTTPConnection('127.0.0.1',8093,timeout=24)
c.request('POST','/embed',json.dumps(v['body'],ensure_ascii=False).encode(),{'Authorization':v['authorization'],'Content-Type':'application/json'})
r=c.getresponse();raw=r.read(131073)
assert len(raw)<=131072
print(json.dumps({'status':r.status,'type':r.getheader('Content-Type'),'body':raw.decode('utf-8')}))
c.close()
"""

class Bridge:
    def __init__(self,name):
        self.name=name
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                self.connection.settimeout(3)
                try:
                    sizes=self.headers.get_all('Content-Length',[])
                    if self.path!='/embed' or len(sizes)!=1 or not sizes[0].isascii() or not sizes[0].isdigit() or not 0<int(sizes[0])<=32768 or self.headers.get('Transfer-Encoding'):raise ValueError()
                    raw=self.rfile.read(int(sizes[0]))
                    if len(raw)!=int(sizes[0]):raise ValueError()
                    packet={'body':json.loads(raw),'authorization':self.headers.get('Authorization','')}
                    result=subprocess.run(['docker','exec','-i',owner.name,'python','-c',FORWARD],input=json.dumps(packet),capture_output=True,text=True,encoding='utf-8',timeout=26)
                    if result.returncode:raise ValueError()
                    out=json.loads(result.stdout);body=out['body'].encode()
                    if not 100<=out['status']<=599 or len(body)>131072:raise ValueError()
                    self.send_response(out['status']);self.send_header('Content-Type',out['type']);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
                except Exception:
                    self.send_response(503);self.send_header('Content-Length','0');self.end_headers()
                finally:self.close_connection=True
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.url=f'http://127.0.0.1:{self.server.server_port}/embed'
    def __enter__(self):self.thread.start();return self
    def __exit__(self,*args):self.server.shutdown();self.server.server_close();self.thread.join(5)
