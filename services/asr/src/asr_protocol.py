"""Bounded MP3 and candidate protocol. No decoding, model import or network access here."""
import base64
import json
import math
import re

MAX_AUDIO=3*1024*1024
MAX_BODY=4*1024*1024+1024
MAX_RESPONSE=65536
class InvalidRequest(ValueError): pass

def loads(raw):
    def unique(pairs):
        result={}
        for key,value in pairs:
            if key in result: raise InvalidRequest('duplicate key')
            result[key]=value
        return result
    def constant(_): raise InvalidRequest('non-finite JSON')
    try:return json.loads(raw,object_pairs_hook=unique,parse_constant=constant)
    except Exception:raise InvalidRequest('invalid JSON') from None

def mp3_duration(data):
    if not isinstance(data,bytes) or not 0<len(data)<=MAX_AUDIO:raise InvalidRequest('invalid audio')
    pos=frames=0
    if data[:3]==b'ID3':
        if len(data)<10 or data[3] not in (3,4) or data[4]!=0 or data[5]!=0:raise InvalidRequest('invalid tag')
        size=0
        for value in data[6:10]:
            if value>=128:raise InvalidRequest('invalid tag')
            size=(size<<7)|value
        if size>65536 or size>len(data)-10:raise InvalidRequest('invalid tag')
        pos=10+size
    while pos<len(data):
        if len(data)-pos<4:raise InvalidRequest('truncated frame')
        a,b,c,d=data[pos:pos+4]
        if a!=255 or b&254!=242 or c&252!=104 or d&192!=192 or d&3==2:raise InvalidRequest('unsupported MP3')
        pos+=216+((c>>1)&1);frames+=1
        if pos>len(data) or frames>1666:raise InvalidRequest('audio budget')
    duration=frames*36
    if not 1000<=duration<=60000:raise InvalidRequest('audio duration')
    return duration

def audio_request(raw):
    if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_BODY:raise InvalidRequest('request budget')
    value=loads(raw)
    if not isinstance(value,dict) or set(value)!={'version','task','audio_base64','duration_ms'} or type(value['version']) is not int or value['version']!=1 or value['task']!='ASR' or type(value['duration_ms']) is not int:raise InvalidRequest('request shape')
    encoded=value['audio_base64']
    if not isinstance(encoded,str) or len(encoded)>(MAX_AUDIO+2)//3*4:raise InvalidRequest('audio budget')
    try:data=base64.b64decode(encoded,validate=True)
    except Exception:raise InvalidRequest('invalid base64') from None
    duration=mp3_duration(data)
    if value['duration_ms']!=duration:raise InvalidRequest('duration mismatch')
    return data,duration

def utf16(text):
    try:return len(text.encode('utf-16-le'))//2
    except UnicodeError:raise InvalidRequest('invalid text') from None

def result(value,duration):
    if type(duration) is not int or not 1000<=duration<=60000:raise InvalidRequest('invalid duration')
    keys={'version','task','source','status','language','duration_ms','segments','requires_confirmation','audio_recognition_performed'}
    if not isinstance(value,dict) or set(value)!=keys or type(value['version']) is not int or value['version']!=1 or value['task']!='ASR' or value['source']!='FASTER_WHISPER' or type(value['duration_ms']) is not int or value['duration_ms']!=duration or value['requires_confirmation'] is not True or value['audio_recognition_performed'] is not True:raise InvalidRequest('invalid result')
    segments=value['segments'];language=value['language'];status=value['status']
    if status not in ('TRANSCRIBED','EMPTY') or not isinstance(segments,list) or len(segments)>50 or (status=='EMPTY')!=(len(segments)==0) or not (isinstance(language,str) and re.fullmatch('[a-z]{2,3}',language) or status=='EMPTY' and language is None):raise InvalidRequest('invalid result')
    end=0;texts=[]
    for row in segments:
        if not isinstance(row,dict) or set(row)!={'start_ms','end_ms','text','confidence'}:raise InvalidRequest('invalid segment')
        start,finish,text,score=row['start_ms'],row['end_ms'],row['text'],row['confidence']
        if type(start) is not int or type(finish) is not int or not end<=start<finish<=duration:raise InvalidRequest('invalid time')
        if not isinstance(text,str) or not text.strip() or text!=text.strip() or any(ord(c)<32 or 127<=ord(c)<=159 for c in text) or utf16(text)>500:raise InvalidRequest('invalid text')
        if not (score is None or type(score) in (int,float) and math.isfinite(score) and 0<=score<=1):raise InvalidRequest('invalid score')
        end=finish;texts.append(text)
    if utf16('\n'.join(texts))>1000:raise InvalidRequest('output budget')
    return value
