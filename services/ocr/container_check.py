"""Client on the owned private network; generated image only, no database or owner data."""
import base64
import hashlib
from http.client import HTTPConnection
import io
import json
import os
import time
from PIL import Image, ImageDraw, ImageFont


def call(raw=None, token=None):
    connection = HTTPConnection('ocr', 8090, timeout=15)
    try:
        connection.request('GET' if raw is None else 'POST', '/health' if raw is None else '/recognize', raw,
            {'Authorization': 'Bearer ' + (token or os.environ['OCR_SERVICE_TOKEN']), 'Content-Type': 'application/json'})
        response = connection.getresponse()
        if response.getheader('Cache-Control') != 'no-store': raise ValueError('Missing private response policy')
        return response.status, json.loads(response.read())
    finally: connection.close()


def run():
    deadline = time.monotonic() + 60
    while True:
        try:
            status, health = call()
            if status == 200 and health.get('recognition') == 'READY': break
        except (OSError, ValueError): pass
        if time.monotonic() >= deadline: raise ValueError('OCR runtime not ready')
        time.sleep(.25)
    with Image.new('RGB', (1100, 220), 'white') as image:
        ImageDraw.Draw(image).text((40, 65), 'ODO 123456 km', font=ImageFont.load_default(size=80), fill='black')
        output = io.BytesIO(); image.save(output, format='PNG')
    raw = json.dumps({'scene': 'mileage', 'image_base64': base64.b64encode(output.getvalue()).decode('ascii')}).encode()
    if call(raw, token='explicit-invalid-credential')[0] != 401: raise ValueError('Unauthorized inference allowed')
    for _ in range(2):
        status, result = call(raw)
        candidates = result.get('candidates', [])
        if status != 200 or result.get('status') != 'CANDIDATE' or result.get('requires_confirmation') is not True or \
                len(candidates) != 1 or candidates[0].get('mileage_km') != 123456 or candidates[0].get('confidence', 0) < .9:
            raise ValueError('Real synthetic mileage inference failed')
    if call(b'{')[0] != 422: raise ValueError('Invalid JSON handling changed')
    if call()[1].get('recognition') != 'READY': raise ValueError('Warm worker unavailable')
    return {'passed': True, 'candidate_requests': 2, 'mileage_km': 123456, 'requires_confirmation': True,
        'unauthorized_status': 401, 'invalid_json_status': 422, 'health_ready': True,
        'synthetic_image_sha256': hashlib.sha256(output.getvalue()).hexdigest()}


if __name__ == '__main__': print(json.dumps(run()))
