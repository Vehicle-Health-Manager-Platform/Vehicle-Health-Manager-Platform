"""Private OCR service; disabled unless a local engine and credential exist."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import secrets
import signal
import socket
import threading
from recognition import MAX_BODY, InvalidImage, decode_image, recognize_image
from worker import ProcessRecognizer, WorkerBusy

class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4
    def __init__(self,address,handler):
        self.connections=threading.BoundedSemaphore(4)
        self.jobs=threading.BoundedSemaphore(1)
        super().__init__(address,handler)
    def process_request(self,request,address):
        if not self.connections.acquire(blocking=False):
            # No handler slot exists to parse this connection. Refuse at TCP level.
            self.shutdown_request(request);return
        try: super().process_request(request,address)
        except Exception: self.connections.release();self.shutdown_request(request);raise
    def process_request_thread(self,request,address):
        try: super().process_request_thread(request,address)
        finally: self.connections.release()
    def server_close(self):
        try: super().server_close()
        finally:
            close=getattr(getattr(self,'engine',None),'close',None)
            if close: close()

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(3)
        self.body_consumed=False
        self._read_guard=threading.Lock();self._reading=True
        def expire():
            with self._read_guard:
                if self._reading:
                    try: self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError: pass
        self._read_timer=threading.Timer(3,expire);self._read_timer.daemon=True;self._read_timer.start()
    def stop_read_deadline(self):
        with self._read_guard:
            self._reading=False;self._read_timer.cancel()
    def finish(self):
        self.stop_read_deadline()
        super().finish()
    def log_message(self, *_): pass
    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'status': 'UP', 'recognition': getattr(self.server.engine,'status','READY') if self.server.engine else 'UNAVAILABLE'})
        else: self.respond(404, {'error': 'not_found'})
    def do_POST(self):
        if self.path != '/recognize': return self.respond(404, {'error': 'not_found'})
        if self.server.engine is None: return self.respond(501, {'error': 'recognition_not_configured'})
        auth = self.headers.get_all('Authorization', [])
        if len(auth) != 1 or not secrets.compare_digest(auth[0].encode(), ('Bearer ' + self.server.token).encode()):
            return self.respond(401, {'error': 'unauthorized'})
        lengths = self.headers.get_all('Content-Length', [])
        if self.headers.get('Transfer-Encoding') or len(lengths) != 1 or not lengths[0].isascii() or not lengths[0].isdigit() or len(lengths[0]) > 10:
            return self.respond(411, {'error': 'content_length_required'})
        size = int(lengths[0])
        if not 0 < size <= MAX_BODY: return self.respond(413, {'error': 'request_too_large'})
        if self.headers.get_content_type() != 'application/json': return self.respond(415, {'error': 'json_required'})
        if not self.server.jobs.acquire(blocking=False): return self.respond(429,{'error':'recognition_busy'})
        try:
            self.body_consumed=True
            try:
                raw=self.rfile.read(size)
                if len(raw)!=size: raise InvalidImage('truncated request')
            except Exception: return self.respond(422,{'error':'invalid_image_request'})
            self.stop_read_deadline()
            try:
                if hasattr(self.server.engine,'recognize_request'):
                    result=self.server.engine.recognize_request(raw)
                else:
                    # Explicit in-process engines remain for historical protocol test fixtures.
                    try: image=decode_image(json.loads(raw))
                    except Exception: raise InvalidImage('invalid image') from None
                    with image: result=recognize_image(image,self.server.engine)
            except InvalidImage: return self.respond(422,{'error':'invalid_image_request'})
            except WorkerBusy: return self.respond(429,{'error':'recognition_busy'})
            except Exception: return self.respond(503,{'error':'recognition_failed'})
            self.respond(200,result)
        finally: self.server.jobs.release()
    def respond(self, status, payload):
        # Drain only bounded, unambiguous bodies to avoid losing early error replies
        # to a TCP reset on Windows. Slow reads still stop at the socket deadline.
        lengths=self.headers.get_all('Content-Length',[])
        if self.command=='POST' and not self.body_consumed and not self.headers.get('Transfer-Encoding') and len(lengths)==1 and lengths[0].isascii() and lengths[0].isdigit() and len(lengths[0])<=10:
            remaining=int(lengths[0])
            if 0<remaining<=MAX_BODY:
                try:
                    while remaining:
                        chunk=self.rfile.read(min(65536,remaining))
                        if not chunk: break
                        remaining-=len(chunk)
                except OSError: pass
            self.body_consumed=True
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.close_connection = True
        self.send_response(status)
        for key, value in [('Content-Type','application/json; charset=utf-8'),('Cache-Control','no-store'),('Connection','close'),('Content-Length',str(len(body)))]: self.send_header(key,value)
        try:
            self.end_headers()
            self.wfile.write(body)
        except OSError: pass

def create_server(address, engine=None, token=''):
    if engine is not None and (not isinstance(token,str) or len(token)<32 or not token.isascii() or any(ord(c)<33 or ord(c)>126 for c in token)):
        raise ValueError('OCR service credential missing')
    server = BoundedServer(address, Handler)
    server.engine, server.token = engine, token
    return server

if __name__ == '__main__':
    token = os.environ.get('OCR_SERVICE_TOKEN','')
    engine = server = None
    def stop(*_): raise SystemExit(0)
    signal.signal(signal.SIGTERM,stop)
    try:
        if os.environ.get('OCR_ENABLED') == 'true':
            if len(token)<32 or not token.isascii() or any(ord(c)<33 or ord(c)>126 for c in token): raise ValueError('OCR service credential missing')
            engine = ProcessRecognizer(os.environ.get('OCR_DETECTION_MODEL_DIR',''),os.environ.get('OCR_RECOGNITION_MODEL_DIR',''))
        server=create_server(('0.0.0.0',int(os.environ.get('OCR_PORT','8090'))),engine,token)
        server.serve_forever()
    finally:
        if server: server.server_close()
        elif engine: engine.close()
