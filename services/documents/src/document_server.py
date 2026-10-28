"""Private document endpoint. Disabled by default; no production fixture or automatic download."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
import os
import secrets
import threading
from document_protocol import MAX_BODY,DocumentError,request,result
import hashlib
from document_worker import ProcessDocument

class Server(ThreadingHTTPServer):
    daemon_threads=True;request_queue_size=4
    def __init__(self,address,engine=None,token=''):
        if engine is not None and (not isinstance(token,str) or not 32<=len(token)<=512 or not all(33<=ord(c)<=126 for c in token)):raise ValueError('DOCUMENT credential missing')
        self.engine=engine;self.token=token;self.connections=threading.BoundedSemaphore(4);self.jobs=threading.BoundedSemaphore(1)
        super().__init__(address,Handler)
    def process_request(self,request,address):
        if not self.connections.acquire(False):self.shutdown_request(request);return
        try:super().process_request(request,address)
        except Exception:self.connections.release();self.shutdown_request(request);raise
    def process_request_thread(self,request,address):
        try:super().process_request_thread(request,address)
        finally:self.connections.release()
    def server_close(self):
        try:super().server_close()
        finally:
            close=getattr(self.engine,'close',None)
            if close:close()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*_):pass
    def send_error(self,code,message=None,explain=None):
        # Also cover unsupported methods and HTTP parser failures without HTML,
        # request-line reflection or diagnostics. Headers may not exist yet.
        raw=b'{"error":"http_request_refused"}';self.close_connection=True
        self.send_response(code)
        for key,value in [('Content-Type','application/json; charset=utf-8'),('Cache-Control','no-store'),('Content-Length',str(len(raw))),('Connection','close')]:self.send_header(key,value)
        try:self.end_headers();self.wfile.write(raw)
        except OSError:pass
    def setup(self):
        super().setup();self.connection.settimeout(3);self.body_consumed=False
        def expire():
            try:self.connection.shutdown(2)
            except OSError:pass
        self.timer=threading.Timer(3,expire);self.timer.daemon=True;self.timer.start()
    def finish(self):self.timer.cancel();super().finish()
    def reply(self,status,data):
        # Drain only bounded, unambiguous bodies before closing; avoids reset hiding
        # an early error on Windows. The same three-second read deadline still applies.
        lengths=self.headers.get_all('Content-Length',[])
        if self.command=='POST' and not self.body_consumed and not self.headers.get('Transfer-Encoding') and len(lengths)==1 and lengths[0].isascii() and lengths[0].isdigit() and len(lengths[0])<=10:
            remaining=int(lengths[0])
            if 0<remaining<=MAX_BODY:
                try:
                    while remaining:
                        chunk=self.rfile.read(min(65536,remaining))
                        if not chunk:break
                        remaining-=len(chunk)
                except OSError:pass
            self.body_consumed=True
        raw=json.dumps(data,ensure_ascii=False).encode();self.close_connection=True
        self.send_response(status)
        for key,value in [('Content-Type','application/json; charset=utf-8'),('Cache-Control','no-store'),('Content-Length',str(len(raw))),('Connection','close')]:self.send_header(key,value)
        try:self.end_headers();self.wfile.write(raw)
        except OSError:pass
    def do_GET(self):self.reply(200,{'status':'UP','recognition':getattr(self.server.engine,'status','CONFIG_VERIFIED') if self.server.engine else 'UNAVAILABLE'}) if self.path=='/health' else self.reply(404,{'error':'not_found'})
    def do_POST(self):
        if self.path!='/extract':return self.reply(404,{'error':'not_found'})
        if self.server.engine is None:return self.reply(503,{'error':'document_not_configured'})
        auth=self.headers.get_all('Authorization',[])
        if len(auth)!=1 or not secrets.compare_digest(auth[0].encode(),('Bearer '+self.server.token).encode()):return self.reply(401,{'error':'unauthorized'})
        sizes=self.headers.get_all('Content-Length',[]);types=self.headers.get_all('Content-Type',[])
        if self.headers.get('Transfer-Encoding') or len(sizes)!=1 or not sizes[0].isascii() or not sizes[0].isdigit() or len(sizes[0])>10:return self.reply(411,{'error':'length_required'})
        size=int(sizes[0])
        if not 0<size<=MAX_BODY:return self.reply(413,{'error':'LIMIT_EXCEEDED'})
        encodings=self.headers.get_all('Content-Encoding',[])
        if len(types)!=1 or types[0].split(';',1)[0].strip().lower()!='application/json' or len(encodings)>1 or encodings and encodings[0].strip().lower()!='identity':return self.reply(415,{'error':'json_required'})
        if not self.server.jobs.acquire(False):return self.reply(429,{'error':'busy'})
        try:
            self.body_consumed=True
            try:raw=self.rfile.read(size)
            except OSError:return self.reply(422,{'error':'INVALID_DOCUMENT'})
            if len(raw)!=size:return self.reply(422,{'error':'INVALID_DOCUMENT'})
            self.timer.cancel()
            try:
                content_type,source=request(raw)
                output=result(self.server.engine.extract_request(raw))
                if output['content_type']!=content_type or output['content_sha256']!=hashlib.sha256(source).hexdigest():raise DocumentError('INVALID_RESULT')
            except DocumentError as error:return self.reply(413 if error.code=='LIMIT_EXCEEDED' else 503 if error.code=='UNAVAILABLE' else 422,{'error':error.code})
            except Exception:return self.reply(503,{'error':'UNAVAILABLE'})
            self.reply(200,output)
        finally:self.server.jobs.release()

def configured_server(address,environment=None):
    env=os.environ if environment is None else environment
    flag=env.get('DOCUMENT_SERVICE_ENABLED','false')
    if flag not in ('false','true'):raise ValueError('Invalid DOCUMENT enable flag')
    token=env.get('DOCUMENT_SERVICE_TOKEN','')
    if flag=='true' and (not isinstance(token,str) or not 32<=len(token)<=512 or not all(33<=ord(c)<=126 for c in token)):raise ValueError('DOCUMENT credential missing')
    if flag=='true' and env.get('DOCUMENT_CORE_ENABLED')!='true':raise ValueError('Document core disabled')
    engine=ProcessDocument() if flag=='true' else None
    return Server(address,engine,token)

if __name__=='__main__':
    try:server=configured_server(('0.0.0.0',int(os.environ.get('DOCUMENT_PORT','8092'))))
    except Exception:raise SystemExit('DOCUMENT startup verification failed') from None
    try:server.serve_forever()
    finally:server.shutdown();server.server_close()
