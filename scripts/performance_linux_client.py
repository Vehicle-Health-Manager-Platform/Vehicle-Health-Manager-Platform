"""Private stdio bridge to the unchanged load workers inside an owned Linux container.

Only numeric observations go to stdout. Configuration (including synthetic tokens)
arrives on stdin and is never echoed. No provider or production endpoints are used.
"""
import json
import os
import re
import sys
from run_performance_baseline import LoadClients, preflight, MERCHANTS


def configuration(raw):
    value=json.loads(raw)
    if set(value)!={'host','port','concurrency','processes','tokens','mode','slot_base'} or value['port']!=8080 \
            or not isinstance(value['host'],str) or not re.fullmatch('autocare-perf-[a-f0-9]{12}-backend',value['host']) \
            or value['mode'] not in ('home','merchant','order') \
            or type(value['concurrency']) is not int or not 1<=value['concurrency']<=2000 \
            or type(value['processes']) is not int or not 1<=value['processes']<=value['concurrency'] \
            or value['concurrency']%value['processes'] or type(value['slot_base']) is not int or value['slot_base']<0 or value['slot_base']%MERCHANTS:
        raise ValueError('Invalid owned load target')
    if value['mode']!='order':
        if not isinstance(value['tokens'],dict):raise ValueError('Invalid reader tokens')
        value['tokens']={int(k):v for k,v in value['tokens'].items()}
    elif not isinstance(value['tokens'],list):raise ValueError('Invalid writer tokens')
    tokens=value['tokens'].values() if isinstance(value['tokens'],dict) else value['tokens']
    if not value['tokens'] or any(not isinstance(t,str) or not 1<=len(t)<=4096 for t in tokens):raise ValueError('Invalid load tokens')
    return value


def main():
    # Child-process diagnostics inherit stderr, never the framed observation channel.
    protocol=os.fdopen(os.dup(sys.stdout.fileno()),'w',encoding='utf-8',buffering=1)
    os.dup2(sys.stderr.fileno(),sys.stdout.fileno())
    config=configuration(sys.stdin.readline())
    if '--preflight' in sys.argv:
        result=preflight(config['port'],config['mode'],config['tokens'],host=config['host'],
                         slot_base=config['slot_base'] if config['mode']=='order' else 0)
        protocol.write(json.dumps(result)+'\n');return
    clients=LoadClients(config['host'],config['port'],config['concurrency'],config['processes'],config['tokens'],config['mode'])
    try:
        protocol.write('{"ready":true}\n')
        for line in sys.stdin:
            command=json.loads(line)
            if command=={'stop':True}:break
            if set(command)!={'round'} or type(command['round']) is not int or command['round']<0:raise ValueError('Invalid round')
            samples,elapsed=clients.round(command['round'])
            protocol.write(json.dumps({'samples':samples,'elapsed':elapsed})+'\n')
    finally:clients.stop()
    protocol.write('{"stopped":true}\n')


if __name__=='__main__':main()
