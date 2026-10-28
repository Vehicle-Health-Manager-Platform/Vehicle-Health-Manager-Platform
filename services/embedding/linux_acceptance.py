"""Explicit offline actual BGE checks on synthetic Chinese text; not a quality benchmark."""
import errno
import http.client
import json
import math
import os
from pathlib import Path
import sys
import threading
import time
sys.path.insert(0,'/app')
from embedding_assets import profile,fingerprint,verify,verify_installed
from embedding_model import BgeEngine
from embedding_process import ProcessEmbedding
from embedding_protocol import EmbeddingError,request,result
from embedding_server import configured_server

def main():
    checks=[];record={'passed':False,'synthetic_text_only':True,'automotive_semantic_accuracy_accepted':False,'checks':checks}
    def check(name,value):
        checks.append({'name':name,'passed':bool(value)})
        if not value:raise AssertionError(name)
    try:
        verify('/models');verify_installed();p=profile();h=fingerprint(p);record['profile']=p;record['profile_hash']=h
        check('fixed_resources_and_complete_installed_versions',True)
        engine=BgeEngine('/models')
        check('actual_tokenizer_512_boundary',len(engine.tokenizer.encode('车'*510).ids)==512)
        check('actual_tokenizer_513_boundary',len(engine.tokenizer.encode('车'*511).ids)==513)
        try:engine.embed(['车'*511]);raise AssertionError('unexpected truncation')
        except EmbeddingError as error:check('reject_before_model_load_no_silent_truncation',error.code=='LIMIT_EXCEEDED' and engine.model is None)
        os.environ['EMBEDDING_MODEL_ENABLED']='true'
        worker=ProcessEmbedding('/models')
        def raw(texts):return json.dumps({'version':1,'profile_hash':h,'texts':texts},ensure_ascii=False).encode()
        texts=[p['query_prefix']+'汽车轮胎胎压不足应该如何处理？','发现汽车轮胎胎压不足时，应检查漏气并按车辆标准补充气压。','今天的食谱介绍西红柿炒鸡蛋的烹饪方法。']
        start=time.monotonic()
        try:
            first=worker.embed_request(raw(texts));vectors=first['vectors'];record['batch_seconds']=round(time.monotonic()-start,3)
            check('actual_guarded_cpu_model_vectors',len(vectors)==3 and all(len(v)==512 for v in vectors))
            check('finite_unit_norm',all(abs(sum(x*x for x in v)-1)<.0001 and all(math.isfinite(x) for x in v) for v in vectors))
            check('owned_worker_reaped_after_success',worker.pid is None and not worker.lock.locked())
            second=worker.embed_request(raw(texts))
            check('repeat_stability',max(abs(x-y) for a,b in zip(vectors,second['vectors']) for x,y in zip(a,b))<1e-6)
            scores=[sum(x*y for x,y in zip(vectors[0],v)) for v in vectors[1:]];record['synthetic_cosines']=scores
            check('synthetic_related_text_ranks_before_unrelated',scores[0]>scores[1])
            try:worker.embed_request(raw(['正常文本','车'*511]));raise AssertionError('partial batch')
            except EmbeddingError as error:check('whole_batch_token_budget_no_partial_result',error.code=='LIMIT_EXCEEDED')
            check('recovery_after_budget_rejection',len(worker.embed_request(raw(['合成恢复检查']))['vectors'])==1)
        finally:worker.close()
        try:worker.embed_request(raw(['关闭后不执行']));raise AssertionError('closed worker')
        except EmbeddingError:check('close_refuses_new_inference',worker.pid is None)
        token='synthetic-embedding-private-test-token'
        os.environ['EMBEDDING_SERVICE_ENABLED']='true';os.environ['EMBEDDING_SERVICE_TOKEN']=token;os.environ['EMBEDDING_MODEL_ROOT']='/models'
        server=configured_server(('127.0.0.1',0));thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        def call(headers,texts):
            conn=http.client.HTTPConnection(*server.server_address,timeout=25)
            conn.request('POST','/embed',raw(texts),headers)
            response=conn.getresponse();out=(response.status,json.loads(response.read()));conn.close();return out
        try:
            headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
            check('private_http_authentication',call({'Content-Type':'application/json'},['合成'])[0]==401)
            code,value=call(headers,['合成HTTP实际模型'])
            check('private_http_actual_inference',code==200 and result(value,h,1)==value)
            check('private_http_actual_token_budget',call(headers,['车'*511])==(413,{'error':'LIMIT_EXCEEDED'}))
        finally:server.shutdown();server.server_close();thread.join(3)
        # Separate child proves actual kernel denial; main needs loopback for HTTP.
        import subprocess
        proof=subprocess.run([sys.executable,'-c',"from network_guard import enforce; import socket,errno; enforce();\ntry: socket.socket(); raise AssertionError('network allowed')\nexcept OSError as e: assert e.errno==errno.EPERM"],capture_output=True,timeout=5)
        check('kernel_worker_socket_denial',proof.returncode==0)
        record['passed']=True
    except Exception as error:record['failure']=type(error).__name__+': '+str(error)
    print(json.dumps(record,ensure_ascii=False));return 0 if record['passed'] else 1

if __name__=='__main__':raise SystemExit(main())
