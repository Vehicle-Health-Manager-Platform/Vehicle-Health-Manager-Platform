"""Private bounded embedding matrices. No URL, model selection or silent truncation."""
import json
import math
import re
import unicodedata
MAX_BODY=32768
MAX_RESPONSE=131072
DIMENSION=512

class EmbeddingError(ValueError):
    def __init__(self,code):
        self.code=code if code in ('INVALID_REQUEST','LIMIT_EXCEEDED','INVALID_RESULT','UNAVAILABLE') else 'UNAVAILABLE'
        super().__init__(self.code)

def loads(raw):
    def pairs(rows):
        out={}
        for key,value in rows:
            if key in out:raise EmbeddingError('INVALID_REQUEST')
            out[key]=value
        return out
    def invalid(_):raise EmbeddingError('INVALID_REQUEST')
    try:return json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)
    except Exception:raise EmbeddingError('INVALID_REQUEST') from None

def request(raw,profile_hash):
    if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_BODY:raise EmbeddingError('LIMIT_EXCEEDED')
    value=loads(raw)
    if not isinstance(value,dict) or set(value)!={'version','profile_hash','texts'} or type(value['version']) is not int or value['version']!=1 or not isinstance(profile_hash,str) or not re.fullmatch('[a-f0-9]{64}',profile_hash) or value['profile_hash']!=profile_hash:raise EmbeddingError('INVALID_REQUEST')
    texts=value['texts']
    if not isinstance(texts,list) or not 1<=len(texts)<=4:raise EmbeddingError('LIMIT_EXCEEDED')
    for text in texts:
        if not isinstance(text,str) or not text.strip() or any(unicodedata.category(c) in ('Cs','Cf','Cc') and c!='\n' for c in text):raise EmbeddingError('INVALID_REQUEST')
        if len(text.encode('utf-16-le'))//2>1024:raise EmbeddingError('LIMIT_EXCEEDED')
    return texts

def result(value,profile_hash,count):
    if not isinstance(value,dict) or set(value)!={'version','profile_hash','vectors'} or type(value['version']) is not int or value['version']!=1 or value['profile_hash']!=profile_hash or not isinstance(value['vectors'],list) or len(value['vectors'])!=count or not 1<=count<=4:raise EmbeddingError('INVALID_RESULT')
    for vector in value['vectors']:
        if not isinstance(vector,list) or len(vector)!=DIMENSION or any(isinstance(v,bool) or not isinstance(v,(int,float)) or abs(v)>1.00001 or not math.isfinite(v) for v in vector):raise EmbeddingError('INVALID_RESULT')
        if abs(sum(v*v for v in vector)-1)>0.0001:raise EmbeddingError('INVALID_RESULT')
    if len(json.dumps(value,allow_nan=False).encode())>MAX_RESPONSE:raise EmbeddingError('INVALID_RESULT')
    return value
