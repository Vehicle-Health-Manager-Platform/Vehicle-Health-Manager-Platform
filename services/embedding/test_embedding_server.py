"""HTTP fixtures verify transport/policy only; no embedding inference in this suite."""
import copy
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_server import Server,configured_server
from embedding_protocol import EmbeddingError,MAX_BODY
from test_embedding_protocol import body,answer,HASH

TOKEN='synthetic-embedding-token-32-characters'
class Engine:
    status='TEST_ONLY';profile_hash=HASH
    def __init__(self):self.calls=0;self.closed=False;self.output=answer()
    def embed_request(self,raw):
        self.calls+=1
        if isinstance(self.output,Exception):raise self.output
        return copy.deepcopy(self.output)
    def close(self):self.closed=True

class ServerTest(unittest.TestCase):
    def setUp(self):
        self.engine=Engine();self.server=Server(('127.0.0.1',0),self.engine,TOKEN)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join(3)
    def call(self,raw=None,path='/embed',headers=None,method='POST'):
        raw=body() if raw is None else raw
        c=http.client.HTTPConnection(*self.server.server_address,timeout=5)
        h={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'} if headers is None else headers
        c.request(method,path,body=raw if method=='POST' else None,headers=h)
        r=c.getresponse();value=(r.status,json.loads(r.read()),dict(r.getheaders()));c.close();return value
    def test_success_is_candidate_with_no_store(self):
        status,value,headers=self.call();self.assertEqual(status,200);self.assertEqual(value,answer());self.assertEqual(headers['Cache-Control'],'no-store')
    def test_health_never_implies_real_inference(self):
        self.assertEqual(self.call(method='GET',path='/health')[1]['recognition'],'TEST_ONLY');self.assertEqual(self.engine.calls,0)
    def test_path_query_and_token_are_strict(self):
        for path in ['/embed?url=x','/transcribe','/embed/']:
            self.assertEqual(self.call(path=path)[0],404)
        self.assertEqual(self.call(headers={'Content-Type':'application/json'})[0],401);self.assertEqual(self.engine.calls,0)
    def test_unsupported_method_has_safe_json_error(self):
        self.assertEqual(self.call(method='PUT')[:2],(501,{'error':'http_request_refused'}));self.assertEqual(self.engine.calls,0)
    def test_duplicate_authentication_and_lengths_are_refused(self):
        for name,values,status in [('Authorization',['Bearer '+TOKEN,'Bearer '+TOKEN],401),('Content-Length',['2','2'],411)]:
            c=http.client.HTTPConnection(*self.server.server_address,timeout=5)
            c.putrequest('POST','/embed');c.putheader('Content-Type','application/json')
            if name!='Authorization':c.putheader('Authorization','Bearer '+TOKEN)
            if name!='Content-Length':c.putheader('Content-Length','2')
            for value in values:c.putheader(name,value)
            c.endheaders();c.send(b'{}');r=c.getresponse();self.assertEqual(r.status,status);r.read();c.close()
        self.assertEqual(self.engine.calls,0)
    def test_invalid_json_does_not_reach_engine(self):
        for raw in [b'{}',body()+b'{}',b'{"version":1,"version":1}']:
            self.assertEqual(self.call(raw)[0],422)
        self.assertEqual(self.engine.calls,0)
    def test_type_encoding_and_transfer_refused(self):
        for extra in [{'Content-Type':'text/plain'},{'Content-Encoding':'gzip'}]:
            headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json',**extra}
            self.assertEqual(self.call(headers=headers)[0],415)
        self.assertEqual(self.engine.calls,0)
    def test_over_budget_is_bounded_before_read(self):
        self.assertEqual(self.call(b'',headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json','Content-Length':str(MAX_BODY+1)})[:2],(413,{'error':'LIMIT_EXCEEDED'}))
    def test_single_flight_refuses_second_request(self):
        self.server.jobs.acquire()
        try:self.assertEqual(self.call()[0],429);self.assertEqual(self.engine.calls,0)
        finally:self.server.jobs.release()
    def test_wrong_source_and_invalid_projection_refused(self):
        self.engine.output['profile_hash']='b'*64
        self.assertEqual(self.call()[:2],(422,{'error':'INVALID_RESULT'}))
        self.engine.output={'private':'secret'}
        self.assertEqual(self.call()[0],422)
    def test_safe_errors_never_expose_exception(self):
        for error,code,status in [(EmbeddingError('LIMIT_EXCEEDED'),'LIMIT_EXCEEDED',413),(EmbeddingError('INVALID_REQUEST'),'INVALID_REQUEST',422),(RuntimeError('private document'),'UNAVAILABLE',503)]:
            self.engine.output=error;self.assertEqual(self.call()[:2],(status,{'error':code}))
    def test_configuration_closed_by_default_and_validates_gates(self):
        server=configured_server(('127.0.0.1',0),{});self.assertIsNone(server.engine);server.server_close()
        for env in [{'EMBEDDING_SERVICE_ENABLED':'yes'},{'EMBEDDING_SERVICE_ENABLED':'true'},{'EMBEDDING_SERVICE_ENABLED':'true','EMBEDDING_SERVICE_TOKEN':TOKEN}]:
            with self.assertRaises(ValueError):configured_server(('127.0.0.1',0),env)
        with patch('embedding_server.ProcessEmbedding',return_value=self.engine) as constructor:
            server=configured_server(('127.0.0.1',0),{'EMBEDDING_SERVICE_ENABLED':'true','EMBEDDING_MODEL_ENABLED':'true','EMBEDDING_SERVICE_TOKEN':TOKEN})
            constructor.assert_called_once_with('');server.server_close();self.assertTrue(self.engine.closed)

if __name__=='__main__':unittest.main()
