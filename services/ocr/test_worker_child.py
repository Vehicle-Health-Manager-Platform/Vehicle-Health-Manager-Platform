"""Explicit substitute engine for real process lifecycle tests, never accuracy proof."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import time
sys.path.insert(0,str(Path(__file__).parent/'src'))
from worker import child_loop, read_frame, write_frame, MAX_BODY, MAX_RESPONSE
from process_limits import guard_parent

class Engine:
    def recognize(self,image):
        pixel=image.getpixel((0,0))
        if pixel==(255,0,0): time.sleep(60)
        if pixel==(0,0,255): os._exit(7)
        if pixel==(0,255,0): raise RuntimeError('private model detail')
        return [{'text':'123456 km','confidence':.95}]

def factory():
    if sys.argv[1]=='recover_error':
        marker=Path(sys.argv[2]);count=int(marker.read_text()) if marker.exists() else 0
        marker.write_text(str(count+1))
        if count:raise RuntimeError('private restart detail')
    if sys.argv[1]=='startup_hang': time.sleep(60)
    if sys.argv[1]=='startup_error': raise RuntimeError('private startup detail')
    return Engine()

if __name__=='__main__':
    if sys.argv[1]=='idle_exit':
        guard_parent();assert read_frame(sys.stdin.buffer,4)==b'BOOT'
        write_frame(sys.stdout.buffer,b'{"kind":"ready"}');time.sleep(.1);os._exit(7)
    elif sys.argv[1]=='leak_reader':
        guard_parent();assert read_frame(sys.stdin.buffer,4)==b'BOOT'
        subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        write_frame(sys.stdout.buffer,b'{"kind":"ready"}')
        sys.stdin.buffer.read(4);os._exit(7)
    elif sys.argv[1]=='bad_frame':
        guard_parent();assert read_frame(sys.stdin.buffer,4)==b'BOOT'
        write_frame(sys.stdout.buffer,b'{"kind":"ready"}');read_frame(sys.stdin.buffer,MAX_BODY)
        sys.stdout.buffer.write(struct.pack('!I',MAX_RESPONSE+1));sys.stdout.buffer.flush();time.sleep(60)
    else: child_loop(factory)
