"""Fixed local resources only. Verification never downloads or resolves a revision."""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import re

HERE=Path(__file__).resolve().parent
LOCKS=HERE.parent if (HERE.parent/'models.lock.json').is_file() else HERE
MODEL_ID='BAAI/bge-small-zh-v1.5'
ENCODER_POLICY='BGE_CLS_FP32_TOKENIZER512_V1'
MODEL_FILES={'README.md','config.json','config_sentence_transformers.json','model.safetensors','modules.json','sentence_bert_config.json','special_tokens_map.json','tokenizer.json','tokenizer_config.json','vocab.txt','1_Pooling/config.json'}

def digest(path):
    value=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()

def load(path):
    def unique(rows):
        out={}
        for key,value in rows:
            if key in out:raise ValueError('Duplicate resource declaration')
            out[key]=value
        return out
    return json.loads(Path(path).read_text(encoding='utf-8'),object_pairs_hook=unique)

def records(kind,locks=LOCKS):
    if kind not in ('model','wheels'):raise ValueError('Unknown embedding resource kind')
    data=load(Path(locks)/('models.lock.json' if kind=='model' else 'runtime-wheels-linux.json'))
    rows=data.get('files')
    if data.get('version')!=1 or not isinstance(rows,list) or not 1<=len(rows)<=64:raise ValueError('Invalid embedding inventory')
    if kind=='model' and (data.get('repository')!=MODEL_ID or not re.fullmatch('[a-f0-9]{40}',data.get('revision','')) or data.get('license')!='MIT' or data.get('dimension')!=512 or data.get('max_tokens')!=512 or data.get('query_prefix')!='为这个句子生成表示以用于检索相关文章：'):raise ValueError('Invalid embedding model policy')
    from urllib.parse import urlparse,unquote
    names=set()
    for row in rows:
        name=row.get('filename','');url=urlparse(row.get('url',''))
        if name in names or not re.fullmatch('[a-f0-9]{64}',row.get('sha256','')) or type(row.get('bytes')) is not int or not 0<row['bytes']<=512*1024**2 or url.scheme!='https' or url.username or url.password or url.query or url.fragment:raise ValueError('Invalid embedding asset')
        if kind=='model':
            if name not in MODEL_FILES or url.netloc!='huggingface.co' or url.path!=f"/{MODEL_ID}/resolve/{data['revision']}/{name}":raise ValueError('Unexpected model source')
        else:
            if not re.fullmatch(r'[A-Za-z0-9_.+-]{1,200}\.whl',name) or unquote(url.path.rsplit('/',1)[-1])!=name or not re.fullmatch('[A-Za-z0-9_.-]+',row.get('name','')) or not re.fullmatch('[A-Za-z0-9_.+!-]+',row.get('version','')):raise ValueError('Invalid embedding wheel')
            if url.netloc=='files.pythonhosted.org':
                if not url.path.startswith('/packages/'):raise ValueError('Unexpected wheel source')
            elif url.netloc in ('download.pytorch.org','download-r2.pytorch.org'):
                if row['name']!='torch' or row['version']!='2.8.0+cpu' or not url.path.startswith('/whl/cpu/'):raise ValueError('Unexpected CPU source')
            else:raise ValueError('Unexpected wheel source')
            if row['name'].lower().startswith(('nvidia','triton')):raise ValueError('GPU dependencies not allowed')
        names.add(name)
    if kind=='model' and names!=MODEL_FILES or sum(row['bytes'] for row in rows)>1024**3:raise ValueError('Embedding resource budget')
    return data

def wheel_lock(rows):return ''.join(f"{r['name']}=={r['version']} --hash=sha256:{r['sha256']}\n" for r in rows)

def verify(root,kind='model',locks=LOCKS):
    root=Path(root);data=records(kind,locks);expected={r['filename'] for r in data['files']}
    if kind=='wheels':expected.add('requirements.lock')
    if root.is_symlink() or not root.is_dir():raise ValueError('Embedding resources missing')
    paths=list(root.rglob('*'))
    if any(p.is_symlink() for p in paths) or {p.relative_to(root).as_posix() for p in paths if p.is_file()}!=expected:raise ValueError('Embedding resource inventory mismatch')
    for row in data['files']:
        path=root/row['filename']
        if not path.is_file() or path.stat().st_size!=row['bytes'] or digest(path)!=row['sha256']:raise ValueError('Embedding resource checksum mismatch')
    if kind=='wheels' and (root/'requirements.lock').read_text(encoding='utf-8')!=wheel_lock(data['files']):raise ValueError('Embedding install lock mismatch')
    return data

def profile(locks=LOCKS,code=HERE):
    model=records('model',locks);wheels=records('wheels',locks)
    code=Path(code)
    guards=code if (code/'process_limits.py').is_file() else next((p/'services/ocr/src' for p in code.parents if (p/'services/ocr/src/process_limits.py').is_file()),None)
    if guards is None:raise ValueError('Embedding guard code missing')
    def source_hash(path):return hashlib.sha256(path.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
    identity={'model':model,'wheels':wheels,'policy':ENCODER_POLICY,'code':{name:source_hash(code/name) for name in ('embedding_assets.py','embedding_protocol.py','embedding_model.py','embedding_process.py')},'guards':{name:source_hash(guards/name) for name in ('process_limits.py','network_guard.py')}}
    runtime=hashlib.sha256(json.dumps(identity,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return {'model':MODEL_ID,'revision':model['revision'],'dimension':512,'query_prefix':model['query_prefix'],'normalization':'L2_V1','chunk_policy':'UNICODE_256_OVERLAP32_V1','encoder_policy':ENCODER_POLICY,'runtime_sha256':runtime}

def fingerprint(value):return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def verify_installed(locks=LOCKS):
    for row in records('wheels',locks)['files']:
        if importlib.metadata.version(row['name'])!=row['version']:raise ValueError('Embedding installed version mismatch')
