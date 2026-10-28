"""Offline-only small multilingual CPU/int8 adapter; no model name lookup."""
import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import re
from asr_protocol import result,loads

REQUIRED={'model.bin','config.json','tokenizer.json','vocabulary.txt'}
PACKAGES={'faster-whisper','ctranslate2','av','tokenizers','onnxruntime','huggingface-hub','numpy'}

def digest_file(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):value.update(block)
    return value.hexdigest()

def inventory(root,manifest):
    files={};total=0
    for path in root.rglob('*'):
        if path.is_symlink():raise ValueError('Model symlinks are not allowed')
        if not path.is_file() or path.resolve()==manifest:continue
        size=path.stat().st_size;total+=size
        if size>1073741824 or total>2147483648 or len(files)>=128:raise ValueError('Model inventory budget')
        files[path.relative_to(root).as_posix()]=path
    return files
def verify(root,manifest):
    root=Path(root).resolve(strict=True);path=Path(manifest).resolve(strict=True)
    if path.stat().st_size>65536:raise ValueError('Model manifest budget')
    data=loads(path.read_bytes())
    if not isinstance(data,dict) or set(data)!={'version','repository','revision','license_reference','license_accepted','packages','files'} or type(data.get('version')) is not int or data['version']!=1 or data.get('repository')!='Systran/faster-whisper-small' or not isinstance(data.get('revision'),str) or not re.fullmatch('[a-f0-9]{40}',data['revision']) or not isinstance(data.get('license_reference'),str) or not 1<=len(data['license_reference'])<=1024 or not data['license_reference'].strip() or any(ord(c)<32 for c in data['license_reference']) or data.get('license_accepted') is not True:raise ValueError('Model declaration missing')
    versions=data.get('packages',{})
    if not isinstance(versions,dict) or not PACKAGES<=set(versions) or versions.get('faster-whisper')!='1.2.1' or len(versions)>128 or any(not isinstance(k,str) or not re.fullmatch('[a-zA-Z0-9_.-]{1,80}',k) or not isinstance(v,str) or not 1<=len(v)<=80 or importlib.metadata.version(k)!=v for k,v in versions.items()):raise ValueError('Runtime versions mismatch')
    files=data.get('files',{})
    if not isinstance(files,dict) or not REQUIRED<=set(files):raise ValueError('Incomplete local model')
    actual=inventory(root,path)
    if set(actual)!=set(files):raise ValueError('Model file inventory mismatch')
    for name,digest in files.items():
        child=actual[name]
        if not child.resolve().is_relative_to(root) or not isinstance(digest,str) or not re.fullmatch('[a-f0-9]{64}',digest) or digest_file(child)!=digest:raise ValueError('Model hash mismatch')
    return root

class FasterWhisper:
    def __init__(self,root,manifest):
        root=verify(root,manifest)
        from faster_whisper import WhisperModel
        self.model=WhisperModel(str(root),device='cpu',compute_type='int8',cpu_threads=2,num_workers=1,local_files_only=True)
    def transcribe(self,audio,duration):
        # MP3 framing has already limited the decoded duration; decoding stays inside this worker.
        from faster_whisper.audio import decode_audio
        samples=decode_audio(io.BytesIO(audio),sampling_rate=16000)
        if not 16000<=len(samples)<=960000:raise ValueError('Decoded audio budget')
        # Whisper can hallucinate words on digital silence. Only exact zero energy
        # is handled here; no amplitude threshold that could discard quiet speech.
        if not any(samples):
            return result({'version':1,'task':'ASR','source':'FASTER_WHISPER','status':'EMPTY','language':None,
                'duration_ms':duration,'segments':[],'requires_confirmation':True,'audio_recognition_performed':True},duration)
        segments,info=self.model.transcribe(samples,beam_size=5,condition_on_previous_text=False,vad_filter=False,word_timestamps=False)
        rows=[]
        for segment in segments:
            if len(rows)>=50:raise ValueError('Segment budget')
            rows.append({'start_ms':round(segment.start*1000),'end_ms':round(segment.end*1000),'text':segment.text.strip(),'confidence':None})
        return result({'version':1,'task':'ASR','source':'FASTER_WHISPER','status':'TRANSCRIBED' if rows else 'EMPTY','language':info.language if rows else None,'duration_ms':duration,'segments':rows,'requires_confirmation':True,'audio_recognition_performed':True},duration)
