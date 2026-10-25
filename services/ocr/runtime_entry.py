"""Verified offline Linux runtime entry; the ordinary disabled image stays separate."""
from importlib.metadata import version
import os
from pathlib import Path
import platform
import runpy
from model_assets import verify_models


def prepare():
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        raise ValueError('This runtime lock requires Linux x86_64')
    if os.environ.get('OCR_ENABLED') != 'true': raise ValueError('Runtime requires explicit OCR_ENABLED=true')
    expected = {'paddleocr': '3.7.0', 'paddlepaddle': '3.3.1', 'Pillow': '12.3.0'}
    if any(version(name) != value for name, value in expected.items()): raise ValueError('Runtime versions mismatch')
    models = verify_models(Path('/models'))
    os.environ['OCR_DETECTION_MODEL_DIR'] = models['PP-OCRv5_mobile_det']
    os.environ['OCR_RECOGNITION_MODEL_DIR'] = models['PP-OCRv5_mobile_rec']
    os.environ['OCR_WORKER_NETWORK_DISABLED'] = 'true'


if __name__ == '__main__':
    prepare()
    runpy.run_module('server', run_name='__main__')
