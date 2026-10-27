"""Real PDFBox/Paddle on generated files. No user document or public API acceptance."""
import base64
import hashlib
import json
from pathlib import Path
import sys
import time
sys.path.insert(0,'/document/src')
from document_worker import ProcessDocument
from document_protocol import DocumentError


def request(name):
    raw=(Path('/fixtures')/name).read_bytes()
    return json.dumps({'version':1,'content_type':'image/png' if name.endswith('.png') else 'application/pdf',
        'document_base64':base64.b64encode(raw).decode()}).encode()


def main():
    worker=ProcessDocument();report={'passed':False,'checks':{},'samples':{},'real_user_accuracy_accepted':False}
    def check(name,condition):
        report['checks'][name]=bool(condition)
        if not condition:raise RuntimeError(name)
    try:
        import document_worker
        check('source_modules_without_host_bytecode',document_worker.__cached__.startswith('/tmp/document-pycache/') and not Path(document_worker.__cached__).exists())
        for name in ('native.pdf','blank.pdf','image.png','scan.pdf','mixed.pdf','rotated.pdf'):
            started=time.monotonic();value=worker.extract_request(request(name))
            report['samples'][name]={'elapsed_seconds':round(time.monotonic()-started,3),'result':value,
                'expected_number_present':any('123456' in page['text'] for page in value['pages'])}
            check(name+'_all_pages',value['all_pages_processed'] and worker.pid is None)
        native=report['samples']['native.pdf']['result']
        check('native_two_pages_without_ocr',native['page_count']==2 and not native['image_recognition_performed'] and all(p['method']=='NATIVE_TEXT' for p in native['pages']))
        check('blank_is_empty_without_ocr',report['samples']['blank.pdf']['result']['status']=='EMPTY' and not report['samples']['blank.pdf']['result']['image_recognition_performed'])
        for name in ('image.png','scan.pdf','mixed.pdf'):
            value=report['samples'][name]
            check(name+'_actual_ocr_expected_number',value['result']['image_recognition_performed'] and value['expected_number_present'])
        # Rotated glyph recognition is recorded, not promoted to a quality guarantee.
        for name,code in (('overpages.pdf','LIMIT_EXCEEDED'),('overtext.pdf','LIMIT_EXCEEDED'),
                ('corrupt.pdf','INVALID_DOCUMENT'),('encrypted.pdf','INVALID_DOCUMENT'),
                ('action.pdf','INVALID_DOCUMENT'),('external.pdf','INVALID_DOCUMENT')):
            try:worker.extract_request(request(name));actual='UNEXPECTED_SUCCESS'
            except DocumentError as error:actual=error.code
            check(name+'_rejected_without_partial',actual==code)
        worker.timeout=.05
        try:worker.extract_request(request('native.pdf'));actual='UNEXPECTED_SUCCESS'
        except DocumentError as error:actual=error.code
        check('real_child_timeout_and_reap',actual=='UNAVAILABLE' and worker.pid is None and not worker.lock.locked())
        worker.timeout=60
        check('native_recovers_after_timeout',worker.extract_request(request('native.pdf'))['status']=='EXTRACTED')
        report['passed']=True
    except Exception as error:report['failure']=type(error).__name__+': '+str(error)
    finally:
        worker.close();report['checks']['closed_no_child']=worker.closed and worker.pid is None
        report['passed']=report['passed'] and all(report['checks'].values())
    print(json.dumps(report,ensure_ascii=False))
    return 0 if report['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
