"""Conservative candidates from OCR text, not an image recognizer or verified mileage."""
import math
import re

MAX_MILEAGE = 2_000_000
READING = re.compile(r'(?<![\w.,，])([0-9]{1,7}|[0-9]{1,3}(?:[,，][0-9]{3}){1,2})(?:\s*)(公里|千米|km)(?![\w/／])', re.I)


def parse_mileage(lines):
    """Accept up to 100 OCR {text, confidence} lines; never infer missing units."""
    if not isinstance(lines, list) or len(lines) > 100:
        raise ValueError('invalid OCR lines')
    candidates = {}
    uncertain = False
    for line in lines:
        if not isinstance(line, dict) or set(line) != {'text', 'confidence'}:
            raise ValueError('invalid OCR line')
        text, confidence = line['text'], line['confidence']
        if not isinstance(text, str) or len(text) > 500 or any(ord(c) < 32 for c in text):
            raise ValueError('invalid OCR text')
        if isinstance(confidence, bool) or not isinstance(confidence, (int, float)) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
            raise ValueError('invalid OCR confidence')
        # Speed, trip and miles are not odometer kilometres. Do not convert or guess.
        if re.search(r'\b(?:trip|mph|miles?)\b|小计|短途|英里|时速|km\s*[/／]\s*h|[-+−]\s*\d', text, re.I):
            uncertain = True
            continue
        for match in READING.finditer(text):
            value = int(match[1].replace(',', '').replace('，', ''))
            if value > MAX_MILEAGE:
                uncertain = True
                continue
            candidates[value] = max(confidence, candidates.get(value, 0))
    values = [{'mileage_km': value, 'confidence': candidates[value]} for value in sorted(candidates)]
    if not values:
        status = 'NO_CANDIDATE'
    elif len(values) > 1 or uncertain:
        status = 'AMBIGUOUS'
    elif values[0]['confidence'] < 0.9:
        status = 'LOW_CONFIDENCE'
    else:
        status = 'CANDIDATE'
    return {'status': status, 'candidates': values, 'requires_confirmation': True, 'image_recognition_performed': False}
