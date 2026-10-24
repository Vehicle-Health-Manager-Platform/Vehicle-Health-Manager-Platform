"""Private OCR service; disabled unless a local engine and credential exist."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
import secrets
from recognition import MAX_BODY, decode_image, recognize_image, PaddleEngine

class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)
    def log_message(self, *_): pass
    def do_GET(self):
        if self.path == '/health':
            self.respond(200, {'status': 'UP', 'recognition': 'READY' if self.server.engine else 'UNAVAILABLE'})
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
        image = None
        try:
            raw = self.rfile.read(size)
            if len(raw) != size: raise ValueError('truncated request')
            image = decode_image(json.loads(raw))
        except Exception: return self.respond(422, {'error': 'invalid_image_request'})
        try: result = recognize_image(image, self.server.engine)
        except Exception: return self.respond(503, {'error': 'recognition_failed'})
        finally: image.close()
        self.respond(200, result)
    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.close_connection = True
        self.send_response(status)
        for key, value in [('Content-Type','application/json; charset=utf-8'),('Cache-Control','no-store'),('Connection','close'),('Content-Length',str(len(body)))]: self.send_header(key,value)
        self.end_headers()
        self.wfile.write(body)

def create_server(address, engine=None, token=''):
    if engine is not None and (not isinstance(token,str) or len(token)<32 or not token.isascii() or any(ord(c)<33 or ord(c)>126 for c in token)):
        raise ValueError('OCR service credential missing')
    server = HTTPServer(address, Handler)
    server.engine, server.token = engine, token
    return server

if __name__ == '__main__':
    token = os.environ.get('OCR_SERVICE_TOKEN','')
    engine = None
    if os.environ.get('OCR_ENABLED') == 'true':
        engine = PaddleEngine(os.environ.get('OCR_DETECTION_MODEL_DIR'),os.environ.get('OCR_RECOGNITION_MODEL_DIR'))
    create_server(('0.0.0.0',int(os.environ.get('OCR_PORT','8090'))),engine,token).serve_forever()
