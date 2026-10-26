"""Local PaddleOCR adapter: image bytes only, no URL/path input."""
import base64
import io
import json
from pathlib import Path
from numbers import Real
from mileage import parse_mileage
from license_fields import parse_license
MAX_IMAGE = 4 * 1024 * 1024
MAX_BODY = 6 * 1024 * 1024

class InvalidImage(ValueError): pass


def load_request(raw):
    def invalid_constant(_): raise InvalidImage('invalid JSON')
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value: raise InvalidImage('duplicate request key')
            value[key] = item
        return value
    try:
        return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)
    except Exception: raise InvalidImage('invalid request') from None

def decode_image(request):
    if not isinstance(request, dict) or set(request) != {'scene', 'image_base64'} or request['scene'] not in ('mileage', 'license'):
        raise InvalidImage('invalid request')
    value = request['image_base64']
    if not isinstance(value, str) or len(value) > (MAX_IMAGE + 2) // 3 * 4:
        raise InvalidImage('invalid image')
    try:
        body = base64.b64decode(value, validate=True)
        if not 0 < len(body) <= MAX_IMAGE: raise InvalidImage('image size')
        from PIL import Image, ImageOps
        with Image.open(io.BytesIO(body)) as image:
            width, height = image.size
            if image.format not in ('JPEG', 'PNG') or getattr(image, 'n_frames', 1) != 1:
                raise InvalidImage('unsupported image')
            if not 1 <= width <= 4096 or not 1 <= height <= 4096 or width * height > 6_000_000:
                raise InvalidImage('image dimensions')
            image.load()
            return ImageOps.exif_transpose(image).convert('RGB')
    except InvalidImage: raise
    except Exception: raise InvalidImage('invalid image') from None

class PaddleEngine:
    def __init__(self, detection_dir, recognition_dir):
        for directory in (detection_dir, recognition_dir):
            if not directory or not (Path(directory) / 'inference.yml').is_file():
                raise ValueError('local model directory missing')
        from paddleocr import PaddleOCR
        # Plain CPU profile avoids the observed Paddle 3.3.1 oneDNN PIR attribute failure.
        self.model = PaddleOCR(ocr_version='PP-OCRv5', device='cpu', enable_mkldnn=False, cpu_threads=2,
            text_detection_model_name='PP-OCRv5_mobile_det', text_recognition_model_name='PP-OCRv5_mobile_rec',
            text_detection_model_dir=detection_dir, text_recognition_model_dir=recognition_dir,
            use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False)
    def recognize(self, image):
        import numpy as np
        results = iter(self.model.predict(np.asarray(image)[:, :, ::-1].copy()))
        first = next(results, None)
        if first is None or next(results, None) is not None: raise ValueError('invalid model result')
        texts, scores = first['rec_texts'], first['rec_scores']
        if not isinstance(texts, list) or len(texts) != len(scores) or len(texts) > 100: raise ValueError('invalid model result')
        if any(isinstance(score, bool) or not isinstance(score, Real) for score in scores): raise ValueError('invalid model confidence')
        return [{'text': text, 'confidence': float(score)} for text, score in zip(texts, scores)]

def recognize_image(image, engine, scene='mileage'):
    if scene not in ('mileage', 'license'): raise InvalidImage('invalid scene')
    result = (parse_license if scene == 'license' else parse_mileage)(engine.recognize(image))
    result['version'] = 1
    result['scene'] = scene
    result['image_recognition_performed'] = True
    return result
