"""Only label-bound plate/VIN candidates; never return other certificate text."""
import math
import re

LABEL = re.compile(r'^(号牌号码|车牌号码|车牌号|PLATE|车辆识别代号|车辆识别代码|车架号|VIN)\s*[:：]?\s*(.*?)\s*$', re.I)
PLATE_LABELS = {'号牌号码', '车牌号码', '车牌号', 'PLATE'}
PATTERNS = {
    'plate': re.compile(r'[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Za-z][A-Za-z0-9]{5,6}'),
    'vin': re.compile(r'[A-HJ-NPR-Za-hj-npr-z0-9]{17}'),
}


def field_status(candidates):
    if not candidates: return 'NO_CANDIDATE'
    if len(candidates) > 1: return 'AMBIGUOUS'
    return 'CANDIDATE' if candidates[0]['confidence'] >= .9 else 'LOW_CONFIDENCE'


def overall_status(fields):
    states = [field['status'] for field in fields]
    for state in ('AMBIGUOUS', 'LOW_CONFIDENCE'):
        if state in states: return state
    found = states.count('CANDIDATE')
    return 'CANDIDATE' if found == 2 else 'PARTIAL' if found else 'NO_CANDIDATE'


def parse_license(lines):
    if not isinstance(lines, list) or len(lines) > 100: raise ValueError('invalid OCR lines')
    # Validate all lines even though only two kinds of labels can be returned.
    for line in lines:
        if not isinstance(line, dict) or set(line) != {'text', 'confidence'}: raise ValueError('invalid OCR line')
        text, score = line['text'], line['confidence']
        if not isinstance(text, str) or len(text) > 500 or any(ord(c) < 32 or 127 <= ord(c) <= 159 or 0xD800 <= ord(c) <= 0xDFFF for c in text): raise ValueError('invalid OCR text')
        if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1: raise ValueError('invalid OCR confidence')
    values = {'plate': {}, 'vin': {}}
    for index, line in enumerate(lines):
        match = LABEL.fullmatch(line['text'].strip())
        if not match: continue
        key = 'plate' if match[1].upper() in PLATE_LABELS else 'vin'
        value, score = match[2], line['confidence']
        if not value and index + 1 < len(lines):
            following = lines[index + 1]
            value, score = following['text'].strip(), min(score, following['confidence'])
        # No normalization that could repair an OCR mistake: ASCII validation precedes uppercase.
        if not PATTERNS[key].fullmatch(value): continue
        value = value.upper()
        values[key][value] = min(score, values[key].get(value, 1))
        if len(values[key]) > 10: raise ValueError('candidate budget exceeded')
    fields = []
    for key, candidates in values.items():
        rows = [{'value': value, 'confidence': candidates[value]} for value in sorted(candidates)]
        fields.append({'key': key, 'status': field_status(rows), 'candidates': rows})
    return {'version': 1, 'scene': 'license', 'source': 'LOCAL_OCR', 'status': overall_status(fields),
            'fields': fields, 'requires_confirmation': True, 'image_recognition_performed': False}
