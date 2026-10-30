"""Explicit real-engine check using a generated image, never an owner photo."""
import argparse
import base64
from http.client import HTTPConnection
import io
from importlib.metadata import version
import json
import os
import secrets
from pathlib import Path
import sys
import threading
from model_assets import verify_models

VERSIONS = {'paddleocr': '3.7.0', 'paddlepaddle': '3.3.1', 'Pillow': '13.0.0'}

def checked_result(result):
    if result['status'] not in ('CANDIDATE', 'LOW_CONFIDENCE') or len(result['candidates']) != 1 or result['candidates'][0]['mileage_km'] != 123456 or result['requires_confirmation'] is not True:
        raise ValueError('Synthetic mileage recognition check failed')

def run(root):
    dependencies = {name: version(name) for name in VERSIONS}
    if dependencies != VERSIONS: raise ValueError('Runtime dependency versions mismatch')
    models = verify_models(root)
    cache = Path(root).resolve().parent / 'ocr-runtime-cache'
    for name, child in [('PADDLE_PDX_CACHE_HOME', 'paddlex'), ('PADDLE_HOME', 'paddle'),
                        ('HF_HOME', 'huggingface'), ('MODELSCOPE_CACHE', 'modelscope'), ('XDG_CACHE_HOME', 'xdg')]:
        os.environ.setdefault(name, str(cache / child))
    # Model files have already been verified locally; no hub availability probe is needed.
    os.environ['PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK'] = 'True'
    sys.path.insert(0, str(Path(__file__).parent / 'src'))
    from recognition import PaddleEngine, recognize_image
    from PIL import Image, ImageDraw, ImageFont
    engine = PaddleEngine(models['PP-OCRv5_mobile_det'], models['PP-OCRv5_mobile_rec'])
    with Image.new('RGB', (1100, 220), 'white') as image:
        ImageDraw.Draw(image).text((40, 65), 'ODO 123456 km', font=ImageFont.load_default(size=80), fill='black')
        result = recognize_image(image, engine)
        encoded = io.BytesIO(); image.save(encoded, format='PNG')
    checked_result(result)
    from server import create_server
    token = secrets.token_hex(32)
    server = create_server(('127.0.0.1', 0), engine, token)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    connection = HTTPConnection('127.0.0.1', server.server_port, timeout=30)
    try:
        body = json.dumps({'scene': 'mileage', 'image_base64': base64.b64encode(encoded.getvalue()).decode('ascii')})
        connection.request('POST', '/recognize', body, {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        if response.status != 200 or response.getheader('Cache-Control') != 'no-store':
            raise ValueError('Private OCR HTTP check failed')
        checked_result(json.loads(response.read()))
    finally:
        connection.close(); server.shutdown(); server.server_close(); thread.join(5)
    if thread.is_alive(): raise ValueError('Private OCR test server did not stop')
    return {'passed': True, 'dependencies': dependencies, 'real_engine_initialized': True, 'synthetic_image_inference_passed': True,
            'private_http_synthetic_inference_passed': True, 'private_http_server_stopped': True,
            'status': result['status'], 'requires_confirmation': result['requires_confirmation'],
            'owner_instrument_accuracy_accepted': False, 'wechat_journey_accepted': False}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--models-root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    try:
        report = run(args.models_root)
    except Exception as error:
        args.report.write_text(json.dumps({'passed': False, 'error_kind': type(error).__name__,
            'owner_instrument_accuracy_accepted': False, 'wechat_journey_accepted': False}, indent=2) + '\n', encoding='utf-8')
        print('OCR runtime check failed; inspect local dependencies and verified model assets', file=sys.stderr)
        raise SystemExit(1)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('Real OCR engine synthetic image check passed; owner accuracy and WeChat journey untested')
