"""Container-side real-model checks. Synthetic English TTS and silence only."""
import base64
import hashlib
import http.client
import importlib.metadata
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

sys.path.insert(0, '/app')
from asr_process import ProcessAsr, Unavailable
from asr_protocol import mp3_duration
from asr_server import Server


def encode(samples):
    import av
    import numpy as np
    output = io.BytesIO()
    with av.open(output, 'w', format='mp3', options={'id3v2_version': '0', 'write_xing': '0'}) as container:
        stream = container.add_stream('libmp3lame', rate=16000)
        stream.bit_rate = 48000
        stream.layout = 'mono'
        for start in range(0, len(samples), 16000):
            frame = av.AudioFrame.from_ndarray(samples[start:start+16000].reshape(1, -1), format='fltp', layout='mono')
            frame.sample_rate = 16000
            for packet in stream.encode(frame):
                container.mux(packet)
        for packet in stream.encode():
            container.mux(packet)
    audio = output.getvalue()
    return json.dumps({'version': 1, 'task': 'ASR', 'duration_ms': mp3_duration(audio),
        'audio_base64': base64.b64encode(audio).decode()}).encode()


def fixtures():
    import av
    import numpy as np
    from faster_whisper.audio import decode_audio
    speech = decode_audio('/fixtures/synthetic-english.wav', sampling_rate=16000)
    # A trailing second makes the complete utterance and its boundary visible to the decoder.
    return {'english_tts': encode(np.concatenate([speech, np.zeros(16000, dtype=np.float32)])),
        'silence': encode(np.zeros(3 * 16000, dtype=np.float32))}


def main():
    from asr_model import PACKAGES
    sys.path.insert(0, '/workspace/services/asr')
    import prepare_manifest
    lock = json.loads(Path('/workspace/services/asr/models.lock.json').read_text())
    manifest = '/tmp/asr-manifest.json'
    prepare_manifest.main(['--root', '/models', '--revision', lock['revision'],
        '--license-reference', lock['license_reference'], '--accept-license', '--output', manifest])
    packages = {name: importlib.metadata.version(name) for name in sorted(PACKAGES)}
    proof = {'passed': False, 'real_model_loaded': False, 'scope': 'synthetic English TTS and silence; no Chinese or user accuracy acceptance',
        'packages': packages, 'model_revision': lock['revision'], 'python': sys.version,
        'checks': {}, 'samples': {}, 'real_user_accuracy_accepted': False}
    checks = proof['checks']
    engine = ProcessAsr('/models', manifest)
    token = 'synthetic-private-token-' + 'a' * 40
    server = Server(('127.0.0.1', 0), engine, token)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def request(raw=None, auth=True, path='/transcribe'):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=95)
        try:
            headers = {'Content-Type': 'application/json'}
            if auth:
                headers['Authorization'] = 'Bearer ' + token
            connection.request('GET' if raw is None else 'POST', path, body=raw, headers=headers)
            response = connection.getresponse()
            status, body = response.status, json.loads(response.read(65537))
            assert response.getheader('Cache-Control') == 'no-store'
            return status, body
        finally:
            connection.close()

    def check(name, condition):
        checks[name] = bool(condition)
        if not condition:
            raise AssertionError(name)

    try:
        check('configuration_does_not_claim_ready', request(path='/health')[1]['recognition'] == 'CONFIG_VERIFIED')
        samples = fixtures()
        check('unauthorized_401', request(samples['english_tts'], auth=False) == (401, {'error': 'unauthorized'}))
        check('invalid_json_422', request(b'{}') == (422, {'error': 'invalid_audio'}))
        for name, raw in samples.items():
            started = time.monotonic()
            status, candidate = request(raw)
            elapsed = time.monotonic() - started
            check(name + '_real_pipeline', status == 200)
            check(name + '_process_reaped', engine.pid is None and not engine.lock.locked())
            proof['samples'][name] = {'request_sha256': hashlib.sha256(raw).hexdigest(), 'elapsed_seconds': round(elapsed, 3), 'result': candidate}
        proof['real_model_loaded'] = engine.runtime_verified
        english = ' '.join(row['text'] for row in proof['samples']['english_tts']['result']['segments']).lower()
        check('english_expected_words', 'engine' in english and 'brakes' in english)
        check('silence_empty', proof['samples']['silence']['result']['status'] == 'EMPTY')
        check('health_after_real_inference_ready', request(path='/health')[1]['recognition'] == 'READY')
        # A real production child is killed during model startup, not a fixture sleeping engine.
        engine.timeout = .05
        started = time.monotonic()
        check('real_child_timeout_503', request(samples['english_tts']) == (503, {'error': 'asr_unavailable'}))
        check('timeout_bounded_and_reaped', time.monotonic()-started < 10 and engine.pid is None and not engine.lock.locked())
        check('timeout_drops_ready', request(path='/health')[1]['recognition'] == 'CONFIG_VERIFIED')
        engine.timeout = 85
        errors = []
        def infer():
            try:
                errors.append(request(samples['english_tts'])[0])
            except Exception:
                errors.append('failed')
        active = threading.Thread(target=infer)
        active.start()
        deadline = time.monotonic() + 5
        while not engine.lock.locked() and time.monotonic() < deadline:
            time.sleep(.01)
        check('busy_429', request(samples['english_tts']) == (429, {'error': 'busy'}))
        active.join(95)
        check('recovery_after_timeout', errors == [200] and engine.pid is None)
        # Check the actual syscall guard in an isolated diagnostic subprocess as well.
        guard = subprocess.run([sys.executable, '-c',
            'import errno,socket;from network_guard import enforce;enforce();'
            '\ntry: socket.socket()\nexcept OSError as e: assert e.errno==errno.EPERM\nelse: raise AssertionError("network allowed")'],
            capture_output=True, timeout=10, env={**os.environ, 'PYTHONPATH': '/app'})
        check('kernel_socket_denial', guard.returncode == 0)
        proof['passed'] = True
    except Exception as error:
        proof['failure'] = type(error).__name__ + ': ' + str(error)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)
        checks['closed_and_no_child'] = engine.status == 'CLOSED' and engine.pid is None
    proof['passed'] = proof['passed'] and all(checks.values())
    print(json.dumps(proof, ensure_ascii=False, allow_nan=False))
    return 0 if proof['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
