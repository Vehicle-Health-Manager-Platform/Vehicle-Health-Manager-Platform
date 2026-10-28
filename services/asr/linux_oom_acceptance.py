"""Real cgroup OOM check in a dedicated low-memory container, never a shared runtime."""
import http.client
import json
from pathlib import Path
import time


def events():
    return dict((key, int(value)) for key,value in
        (line.split() for line in Path('/sys/fs/cgroup/memory.events').read_text().splitlines()))


def main():
    deadline=time.monotonic()+30
    while True:
        probe=http.client.HTTPConnection('127.0.0.1',8091,timeout=2)
        try:
            probe.request('GET','/health');response=probe.getresponse()
            if response.status==200 and json.loads(response.read())['recognition']=='CONFIG_VERIFIED':break
        except (OSError,http.client.HTTPException):pass
        finally:probe.close()
        if time.monotonic()>deadline:raise RuntimeError('Dedicated low-memory server startup deadline')
        time.sleep(.1)
    before = events()
    connection = http.client.HTTPConnection('127.0.0.1',8091,timeout=95)
    import os
    try:
        connection.request('POST','/transcribe',body=Path('/fixtures/asr-request.json').read_bytes(),
            headers={'Authorization':'Bearer '+os.environ['ASR_SERVICE_TOKEN'],'Content-Type':'application/json'})
        response=connection.getresponse()
        safe_failure=response.status==503 and json.loads(response.read(65537))=={'error':'asr_unavailable'}
    finally:
        connection.close()
    after=events()
    connection=http.client.HTTPConnection('127.0.0.1',8091,timeout=5)
    try:
        connection.request('GET','/health');response=connection.getresponse()
        healthy=response.status==200 and json.loads(response.read())['recognition']=='CONFIG_VERIFIED'
    finally:connection.close()
    proof={'passed':safe_failure and after.get('oom_kill',0)>before.get('oom_kill',0) and healthy,
        'safe_503':safe_failure,'oom_kill_increased':after.get('oom_kill',0)>before.get('oom_kill',0),
        'supervisor_survived':healthy,'memory_max':int(Path('/sys/fs/cgroup/memory.max').read_text()),
        'memory_events_before':before,'memory_events_after':after}
    print(json.dumps(proof))
    return 0 if proof['passed'] else 1


if __name__=='__main__':raise SystemExit(main())
