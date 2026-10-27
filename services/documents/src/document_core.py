"""Private page preparation and OCR composition. Call only in the isolated child."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import sys
from document_protocol import DocumentError,loads,native_lines,request,result,MAX_RESPONSE


def native_page(page):
    return {'page':page['page'],'method':'NATIVE_TEXT' if page['kind']=='NATIVE' else 'EMPTY',
        'text':page['text'],'raster_size':None,'blocks':[{'text':row['text'],'confidence':None,
        'source':{'kind':'TEXT_OFFSET','start':row['start'],'end':row['end']}} for row in page['blocks']]}


def ocr_page(number,raw,engine):
    from recognition import decode_image,DocumentLinesLimit
    with decode_image({'scene':'mileage','image_base64':base64.b64encode(raw).decode()}) as image:
        try:rows=engine.recognize_document(image)
        except DocumentLinesLimit:raise DocumentError('LIMIT_EXCEEDED') from None
        except (ValueError,KeyError,TypeError):raise DocumentError('INVALID_RESULT') from None
        if not isinstance(rows,list) or len(rows)>80:raise DocumentError('LIMIT_EXCEEDED')
        blocks=[]
        for row in rows:
            if not isinstance(row,dict) or set(row)!={'text','confidence','points'}:raise DocumentError('INVALID_RESULT')
            blocks.append({'text':row['text'],'confidence':row['confidence'],'source':{'kind':'RASTER_POLYGON','points':row['points']}})
        return {'page':number,'method':'OCR','text':'\n'.join(row['text'] for row in rows),'raster_size':list(image.size),'blocks':blocks}


def java_plan(raw,work):
    output=work/'plan.json'
    with output.open('xb') as stream:
        try:
            process=subprocess.run([sys.executable,str(Path(__file__).with_name('java_exec.py'))],input=raw,
                stdout=stream,stderr=subprocess.DEVNULL,cwd=work,timeout=25,
                env={**os.environ,'OCR_SUPERVISOR_PID':str(os.getpid())})
        except subprocess.TimeoutExpired:raise DocumentError('UNAVAILABLE') from None
    if process.returncode or output.stat().st_size>MAX_RESPONSE:raise DocumentError('UNAVAILABLE')
    value=loads(output.read_bytes())
    if isinstance(value,dict) and set(value)=={'version','task','status','error_code'} and type(value['version']) is int and value['version']==1 and value['task']=='DOCUMENT_PLAN' and value['status']=='REJECTED':raise DocumentError(value['error_code'])
    return value


def extract(raw,work,engine_factory,prepare=java_plan):
    content_type,body=request(raw);pages=[];engine=None
    def engine_once():
        nonlocal engine
        if engine is None:engine=engine_factory()
        return engine
    if content_type=='application/pdf':
        plan=prepare(body,work)
        if not isinstance(plan,dict) or set(plan)!={'version','task','source','page_count','pages'} or type(plan['version']) is not int or plan['version']!=1 or plan['task']!='DOCUMENT_PLAN' or plan['source']!='PDFBOX_3_0_5' or type(plan['page_count']) is not int or not 1<=plan['page_count']<=4 or not isinstance(plan['pages'],list) or len(plan['pages'])!=plan['page_count']:raise DocumentError('INVALID_RESULT')
        for index,page in enumerate(plan['pages'],1):
            if not isinstance(page,dict) or set(page)!={'page','kind','text','blocks','raster'} or type(page['page']) is not int or page['page']!=index or page['kind'] not in ('NATIVE','OCR','EMPTY'):raise DocumentError('INVALID_RESULT')
            if page['kind']=='OCR':
                raster=page['raster'];name=f'page-{index}.png'
                if page['text']!='' or page['blocks']!=[] or not isinstance(raster,dict) or set(raster)!={'filename','width','height','sha256'} or raster['filename']!=name or any(type(raster[k]) is not int for k in ('width','height')):raise DocumentError('INVALID_RESULT')
                path=work/name
                if path.is_symlink() or not path.is_file() or not 0<path.stat().st_size<=4*1024*1024:raise DocumentError('INVALID_RESULT')
                image=path.read_bytes()
                if hashlib.sha256(image).hexdigest()!=raster['sha256']:raise DocumentError('INVALID_RESULT')
                output=ocr_page(index,image,engine_once())
                if output['raster_size']!=[raster['width'],raster['height']] or any(type(v) is not int or not 1<=v<=2048 for v in output['raster_size']) or output['raster_size'][0]*output['raster_size'][1]>4_000_000:raise DocumentError('INVALID_RESULT')
                pages.append(output)
            else:
                if page['raster'] is not None or page['blocks']!=native_lines(page['text']) or page['kind']=='EMPTY' and (page['text'] or page['blocks']):raise DocumentError('INVALID_RESULT')
                pages.append(native_page(page))
    else:
        magic=b'\x89PNG\r\n\x1a\n' if content_type=='image/png' else b'\xff\xd8\xff'
        if not body.startswith(magic):raise DocumentError('INVALID_DOCUMENT')
        pages.append(ocr_page(1,body,engine_once()))
    return result({'version':1,'task':'DOCUMENT','source':'PDFBOX_PADDLE','content_type':content_type,
        'content_sha256':hashlib.sha256(body).hexdigest(),'page_count':len(pages),'pages':pages,
        'status':'EXTRACTED' if any(page['blocks'] for page in pages) else 'EMPTY',
        'requires_confirmation':True,'image_recognition_performed':any(page['method']=='OCR' for page in pages),'all_pages_processed':True})
