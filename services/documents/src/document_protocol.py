"""Internal bounded candidates, never a diagnostic fact or an authorized public API."""
import base64
import json
import math
from numbers import Real
import re

MAX_PDF=10*1024*1024
MAX_IMAGE=4*1024*1024
MAX_BODY=(MAX_PDF+2)//3*4+2048
MAX_RESPONSE=65536
MAX_TEXT=4096
MAX_BLOCKS=80


class DocumentError(ValueError):
    def __init__(self,code):
        if code not in ('INVALID_DOCUMENT','INVALID_RESULT','LIMIT_EXCEEDED','UNAVAILABLE'):code='UNAVAILABLE'
        self.code=code
        super().__init__(code)


def loads(raw):
    def pairs(rows):
        out={}
        for key,value in rows:
            if key in out:raise DocumentError('INVALID_DOCUMENT')
            out[key]=value
        return out
    def invalid(_):raise DocumentError('INVALID_DOCUMENT')
    try:return json.loads(raw,object_pairs_hook=pairs,parse_constant=invalid)
    except Exception:raise DocumentError('INVALID_DOCUMENT') from None


def utf16(value):
    try:return len(value.encode('utf-16-le'))//2
    except UnicodeError:raise DocumentError('INVALID_RESULT') from None


def text(value,multiline=False):
    if not isinstance(value,str) or any((ord(c)<32 or 127<=ord(c)<=159) and not (multiline and c=='\n') for c in value):raise DocumentError('INVALID_RESULT')
    utf16(value)
    return value


def request(raw):
    if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_BODY:raise DocumentError('LIMIT_EXCEEDED')
    body=loads(raw)
    if not isinstance(body,dict) or set(body)!={'version','content_type','document_base64'} or type(body['version']) is not int or body['version']!=1 or body['content_type'] not in ('application/pdf','image/jpeg','image/png') or not isinstance(body['document_base64'],str):raise DocumentError('INVALID_DOCUMENT')
    maximum=MAX_PDF if body['content_type']=='application/pdf' else MAX_IMAGE
    if len(body['document_base64'])>(maximum+2)//3*4:raise DocumentError('LIMIT_EXCEEDED')
    try:raw=base64.b64decode(body['document_base64'],validate=True)
    except Exception:raise DocumentError('INVALID_DOCUMENT') from None
    if not 0<len(raw)<=maximum:raise DocumentError('INVALID_DOCUMENT')
    return body['content_type'],raw


def native_lines(value):
    text(value,True)
    result=[];start=0
    for line in value.split('\n'):
        if utf16(line)>500:raise DocumentError('LIMIT_EXCEEDED')
        if line.strip():result.append({'text':line,'start':start,'end':start+utf16(line)})
        start+=utf16(line)+1
    return result


def polygon(value,width,height):
    if not isinstance(value,list) or len(value)!=4:raise DocumentError('INVALID_RESULT')
    for point in value:
        if not isinstance(point,list) or len(point)!=2 or any(isinstance(v,bool) or not isinstance(v,Real) or not math.isfinite(v) for v in point) or not 0<=point[0]<=width or not 0<=point[1]<=height:raise DocumentError('INVALID_RESULT')
    crosses=[]
    for i in range(4):
        a,b,c=value[i],value[(i+1)%4],value[(i+2)%4]
        crosses.append((b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0]))
    if not (all(v>0 for v in crosses) or all(v<0 for v in crosses)):raise DocumentError('INVALID_RESULT')


def result(value):
    keys={'version','task','source','content_type','content_sha256','page_count','pages','status','requires_confirmation','image_recognition_performed','all_pages_processed'}
    if not isinstance(value,dict) or set(value)!=keys or type(value['version']) is not int or value['version']!=1 or value['task']!='DOCUMENT' or value['source']!='PDFBOX_PADDLE' or value['content_type'] not in ('application/pdf','image/jpeg','image/png') or not isinstance(value['content_sha256'],str) or not re.fullmatch('[a-f0-9]{64}',value['content_sha256']) or value['requires_confirmation'] is not True or value['all_pages_processed'] is not True or type(value['image_recognition_performed']) is not bool:raise DocumentError('INVALID_RESULT')
    pages=value['pages'];count=value['page_count']
    if type(count) is not int or not 1<=count<=4 or not isinstance(pages,list) or len(pages)!=count or value['content_type']!='application/pdf' and count!=1:raise DocumentError('INVALID_RESULT')
    size=blocks=0;ocr=False
    for index,page in enumerate(pages,1):
        if not isinstance(page,dict) or set(page)!={'page','method','text','raster_size','blocks'} or type(page['page']) is not int or page['page']!=index or page['method'] not in ('NATIVE_TEXT','OCR','EMPTY') or not isinstance(page['blocks'],list):raise DocumentError('INVALID_RESULT')
        content=text(page['text'],True);size+=utf16(content);blocks+=len(page['blocks'])
        method=page['method'];raster=page['raster_size'];ocr=ocr or method=='OCR'
        if method=='OCR':
            if not isinstance(raster,list) or len(raster)!=2 or any(type(v) is not int or not 1<=v<=4096 for v in raster) or raster[0]*raster[1]>6_000_000:raise DocumentError('INVALID_RESULT')
        elif raster is not None:raise DocumentError('INVALID_RESULT')
        expected=native_lines(content) if method=='NATIVE_TEXT' else None
        if method=='NATIVE_TEXT' and not content.strip():raise DocumentError('INVALID_RESULT')
        if method=='EMPTY' and (content or page['blocks']) or expected is not None and len(expected)!=len(page['blocks']):raise DocumentError('INVALID_RESULT')
        for n,block in enumerate(page['blocks']):
            if not isinstance(block,dict) or set(block)!={'text','confidence','source'}:raise DocumentError('INVALID_RESULT')
            line=text(block['text']);source=block['source'];confidence=block['confidence']
            if not line.strip() or utf16(line)>500 or not isinstance(source,dict):raise DocumentError('INVALID_RESULT')
            if method=='NATIVE_TEXT':
                row=expected[n]
                if confidence is not None or source!={'kind':'TEXT_OFFSET','start':row['start'],'end':row['end']} or line!=row['text'] or type(source['start']) is not int or type(source['end']) is not int:raise DocumentError('INVALID_RESULT')
            elif method=='OCR':
                if set(source)!={'kind','points'} or source['kind']!='RASTER_POLYGON' or isinstance(confidence,bool) or not isinstance(confidence,Real) or not math.isfinite(confidence) or not 0<=confidence<=1:raise DocumentError('INVALID_RESULT')
                polygon(source['points'],*raster)
            else:raise DocumentError('INVALID_RESULT')
        if method=='OCR' and content!='\n'.join(block['text'] for block in page['blocks']):raise DocumentError('INVALID_RESULT')
    if size>MAX_TEXT or blocks>MAX_BLOCKS:raise DocumentError('LIMIT_EXCEEDED')
    if value['image_recognition_performed']!=ocr or value['status']!=('EXTRACTED' if blocks else 'EMPTY'):raise DocumentError('INVALID_RESULT')
    if len(json.dumps(value,ensure_ascii=False,allow_nan=False).encode())>MAX_RESPONSE-128:raise DocumentError('LIMIT_EXCEEDED')
    return value
