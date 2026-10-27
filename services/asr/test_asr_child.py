"""Explicit isolated engine doubles; production entry point never selects these modes."""
import os
import sys
import time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent/'src'))
from asr_process import child_loop,read_frame,write_frame
from asr_protocol import MAX_BODY

class Engine:
    def transcribe(self,audio,duration):
        mode=sys.argv[1]
        if mode=='environment':
            if any(name in os.environ for name in ('ASR_SERVICE_TOKEN','HF_TOKEN','SYNTHETIC_API_KEY')) or os.environ.get('ASR_ENV_TEST_VALUE')!='retained' or os.environ.get('HF_HUB_OFFLINE')!='1':raise RuntimeError('Invalid child environment')
        if mode=='hang':time.sleep(20)
        if mode=='crash':os._exit(7)
        if mode=='error':raise RuntimeError('private audio diagnostic')
        result={'version':1,'task':'ASR','source':'FASTER_WHISPER','status':'TRANSCRIBED','language':'zh','duration_ms':duration,'segments':[{'start_ms':0,'end_ms':900,'text':'合成声音文字','confidence':None}],'requires_confirmation':True,'audio_recognition_performed':True}
        if mode=='bad_result':result['duration_ms']=2000
        return result

if __name__=='__main__':
    if sys.argv[1]=='no_read':time.sleep(20)
    elif sys.argv[1]=='duplicate':
        read_frame(sys.stdin.buffer,4);read_frame(sys.stdin.buffer,MAX_BODY);write_frame(sys.stdout.buffer,b'{"kind":"ok","kind":"error"}')
    else:child_loop(Engine,secure=False)
