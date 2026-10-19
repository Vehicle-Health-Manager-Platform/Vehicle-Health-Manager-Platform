"""Private PDF and frozen minimal share summaries, without object storage or supplier IO."""
import base64
import hashlib
from io import BytesIO
import json
import re
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble, seed as identity_seed

OUTPUT=ROOT/'docs/testing/postman/export-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/export-case-manifest.json'
OPERATION_COUNT=7
BASE64_ACCEPT='application/vnd.autocare.pdf-base64+json;version=1'


def cases():
    c=Cases();a=c.add
    report='/api/archive/report?vehicle_id=1';shares='/api/archive/shares';view='/api/archive/share/view'
    c.read('pdf',report,extra=['pm.expect(pm.response.headers.get("Content-Type")).to.include("application/pdf");pm.expect(pm.response.headers.get("Content-Disposition")).to.include("vehicle-archive-report.pdf");'],boundary=(report+'&from=2026-10-20&to=2026-10-21',200),error=(report+'&from=2026-10-01',400))
    for item in c.items:
        if item['status']==200:
            item['event'][0]['script']['exec'][1]='pm.test("PDF bytes",()=>pm.expect(pm.response.text().slice(0,5)).to.eql("%PDF-"));'
    a('pdf_base64','GET',report,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(1);pm.expect(d.content_type).to.eql("application/pdf");pm.expect(d.file_name).to.eql("vehicle-archive-report.pdf");pm.expect(d.content_length).to.be.above(1000);pm.expect(d.content_base64).to.match(/^JVBERi0/);'])
    c.items[-1]['request']['header'].append({'key':'Accept','value':BASE64_ACCEPT})
    a('pdf_filtered','GET',report+'&from=2026-10-01&to=2026-10-02',200,'boundary')
    c.items[-1]['request']['header'].append({'key':'Accept','value':BASE64_ACCEPT})
    a('pdf_foreign','GET',report,404,'unauthorized',role='other')
    a('pdf_duplicate_vehicle','GET',report+'&vehicle_id=2',400,'error')
    a('pdf_unknown_query','GET',report+'&user_id=1',400,'error')
    create={'vehicle_id':1,'expires_in_hours':1}
    no_token='for(const x of ["share_token","token=","token_hash"])pm.expect(pm.response.text()).not.to.include(x);'
    c.write('share_create',shares,create,extra=['pm.expect(pm.response.json().data.share_id).to.eql(1);',no_token])
    a('share_create_foreign','POST',shares,404,'unauthorized',dict(create,vehicle_id=2))
    a('share_bad_expiry','POST',shares,400,'boundary',dict(create,expires_in_hours=2))
    a('share_create_query','POST',shares+'?user_id=1',400,'error',create)
    c.read('shares',shares+'?vehicle_id=1',extra=['pm.expect(pm.response.json().data.list).to.have.lengthOf(1);',no_token],boundary=(shares+'?vehicle_id=2',404),error=(shares+'?vehicle_id=1&user_id=1',400))
    a('share_duplicate_vehicle','GET',shares+'?vehicle_id=1&vehicle_id=2',400,'error')
    a('shares_foreign_owner','GET',shares+'?vehicle_id=1',404,'unauthorized',role='other')
    c.read('link',shares+'/1/link',extra=['const d=pm.response.json().data;pm.expect(d.path).to.match(/^\/pages\/archive\/shared\?token=[a-f0-9]{64}$/);pm.collectionVariables.set("share_token",d.path.split("token=")[1]);'],boundary=(shares+'/999999/link',410),error=(shares+'/1/link?user_id=1',400))
    a('link_foreign','GET',shares+'/1/link',404,'unauthorized',role='other')
    guard='const s=pm.response.json().data.summary;pm.expect(Object.keys(s).sort()).to.eql(["version","generated_at","from","to","registered_mileage","undated_count","entries"].sort());for(const r of s.entries)pm.expect(Object.keys(r).sort()).to.eql(["archive_type","input_type","recorded_date","mileage","work_minutes","test_mode"].sort());for(const x of ["SYNTHETIC_PRIVATE","SYNTHETIC_HIDDEN","plate_no","vin","title","notes","archive_id","user_id","file_id","source","content_base64"])pm.expect(JSON.stringify(s)).not.to.include(x);'
    share_body={'share_token':'{{share_token}}'}
    a('recipient_view','POST',view,200,'normal',share_body,'other',extra=[guard,'const s=pm.response.json().data.summary;pm.expect(s.entries).to.have.lengthOf(5);pm.expect(s.entries.some(x=>x.test_mode===true)).to.eql(true);pm.expect(s.entries.every(x=>x.archive_type<=7)).to.eql(true);pm.collectionVariables.set("frozen_summary",JSON.stringify(s));'])
    a('view_invalid_token','POST',view,410,'boundary',{'share_token':'0'*64},'other')
    a('view_malformed','POST',view,400,'error',{},'other')
    a('view_anonymous','POST',view,401,'unauthorized',share_body,'anonymous')
    a('view_wrong_role','POST',view,403,'unauthorized',share_body,'merchant')
    a('view_query','POST',view+'?vehicle_id=1',400,'error',share_body,'other')
    archive={'vehicle_id':1,'archive_type':1,'recorded_date':'2026-10-06','mileage':200,'title':'后加合成记录','notes':'SYNTHETIC_PRIVATE_LATER','file_ids':[]}
    c.write('later_archive','/api/archive/add',archive,extra=['pm.expect(pm.response.json().data.archive_id).to.eql(11);'])
    a('old_snapshot_frozen','POST',view,200,'boundary',share_body,'other',extra=[guard,'pm.expect(pm.response.json().data.summary).to.eql(JSON.parse(pm.collectionVariables.get("frozen_summary")));'])
    c.write('new_share',shares,create,extra=['pm.expect(pm.response.json().data.share_id).to.eql(2);'])
    a('new_link','GET',shares+'/2/link',200,'normal',extra=['pm.collectionVariables.set("new_share_token",pm.response.json().data.path.split("token=")[1]);'])
    a('new_snapshot','POST',view,200,'normal',{'share_token':'{{new_share_token}}'},'other',extra=[guard,'pm.expect(pm.response.json().data.summary.entries).to.have.lengthOf(6);'])
    a('revoke_foreign','POST',shares+'/1/revoke',404,'unauthorized',{},'other')
    c.write('revoke',shares+'/1/revoke',{},bad={'unexpected':1},extra=['pm.expect(pm.response.json().data.status).to.eql("REVOKED");',no_token])
    a('revoked_view','POST',view,410,'boundary',share_body,'other')
    a('revoked_link','GET',shares+'/1/link',410,'boundary')
    a('revoke_query','POST',shares+'/2/revoke?user_id=1',400,'error',{})
    c.write('revoke_new',shares+'/2/revoke',{},bad={'unexpected':1})
    a('both_revoked','GET',shares+'?vehicle_id=1',200,'boundary',extra=['pm.expect(pm.response.json().data.list.every(x=>x.status==="REVOKED")).to.eql(true);',no_token])
    a('recipient_cannot_read_source_pdf','GET',report,404,'unauthorized',role='other')
    for item in c.items:
        if item['request']['url'].startswith('{{base_url}}'+view):
            item['request']['header']=[h for h in item['request']['header'] if h['key']!='Idempotency-Key']
    return c.items


def assets():return assemble(cases(),'Autocare private PDF and frozen share summaries','isolated_export_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=identity_seed(mysql,secret)
    mysql.sql("UPDATE vehicle SET vin='LTEST123456789012' WHERE id=1;")
    rows=[(1,1,1,3,'2026-10-01',False),(2,1,2,1,'2026-10-02',False),(3,1,3,3,None,False),(4,1,8,3,'2026-10-03',False),(5,1,6,3,'2026-10-04',False),(6,1,2,4,'2026-10-05',False),(7,1,2,4,'2026-10-05',False),(8,1,1,2,'2026-10-01',False),(9,1,1,3,'2026-10-01',True),(10,2,1,3,'2026-10-01',False)]
    for archive,vehicle,kind,source,day,deleted in rows:
        hidden=archive in (7,8,9,10)
        body={'title':'SYNTHETIC_HIDDEN' if hidden else '合成保养记录' if archive==1 else 'SYNTHETIC_PRIVATE_TITLE','notes':'SYNTHETIC_HIDDEN' if hidden else 'SYNTHETIC_PRIVATE_NOTES '+('中文换行和分页校验。'*70 if archive==1 else '合成私有说明'),'mileage':100}
        if source==4:body.update(source={'user_id':2 if archive==7 else 1},test_mode=True,work_minutes=40,no_parts=True,parts_used=[])
        payload=json.dumps(body,ensure_ascii=False).replace("'","''")
        date='NULL' if day is None else "'"+day+"'"
        mysql.sql(f"INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at,is_deleted) VALUES({archive},{vehicle},{kind},{source},'{payload}',{date},{int(deleted)});")
    result['isolated_export_cases']='true'
    return result


def environment(tokens):return [('KNOWLEDGE_AI_ENABLED','false'),('UPLOAD_STORAGE_ENABLED','false'),('UPLOAD_SCAN_ENABLED','false')]


def pdf_evidence(raw):
    from pypdf import PdfReader, __version__
    if len(raw)>3*1024*1024 or not raw.startswith(b'%PDF-'):raise ValueError('Invalid exported PDF bytes')
    reader=PdfReader(BytesIO(raw),strict=True)
    if reader.is_encrypted or not reader.pages:raise ValueError('Unreadable exported PDF')
    text='\n'.join(page.extract_text() or '' for page in reader.pages)
    if '车辆健康档案报告' not in text:raise ValueError('Missing extractable Chinese report text')
    for hidden in ['SYNTHETIC_HIDDEN','粤A12345','LTEST123456789012']:
        if hidden in text:raise ValueError('Hidden or unmasked source in private PDF')
    return {'pages':len(reader.pages),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'chinese_text_verified':True,'pypdf_version':__version__},text


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    result=audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)
    run=json.loads(path.read_text(encoding='utf-8'))['run']
    executions={x['item']['name']:x for x in run['executions']}
    capabilities={}
    for name,key in [('link','share_token'),('new_link','new_share_token')]:
        body=json.loads(bytes(executions[name]['response']['stream']['data']))
        match=re.fullmatch(r'/pages/archive/shared\?token=([a-f0-9]{64})',body['data']['path'])
        if body.get('code')!=0 or not match:raise ValueError('Missing actual share capability link')
        capabilities[key]=match.group(1)
    for item in collection['item']:
        if item['request']['url'].startswith('{{base_url}}/api/archive/share/view'):
            planned=item['request']['body']['raw']
            for key,value in capabilities.items():planned=planned.replace('{{'+key+'}}',value)
            actual=executions[item['name']]['request']['body']['raw']
            if json.loads(planned)!=json.loads(actual):raise ValueError('Share view did not use its actual declared capability')
    evidence={}
    for name in ['pdf','pdf_boundary','pdf_base64','pdf_filtered']:
        raw=bytes(executions[name]['response']['stream']['data'])
        if name in ('pdf_base64','pdf_filtered'):
            accepts=[h['value'] for h in executions[name]['request'].get('header',[]) if h['key'].lower()=='accept' and not h.get('disabled')]
            if accepts!=[BASE64_ACCEPT]:raise ValueError('PDF envelope request did not negotiate base64 format')
            data=json.loads(raw)['data'];raw=base64.b64decode(data['content_base64'],validate=True)
            if data['version']!=1 or data['content_length']!=len(raw) or data['content_type']!='application/pdf' or data['file_name']!='vehicle-archive-report.pdf':raise ValueError('Invalid PDF base64 envelope')
        evidence[name],text=pdf_evidence(raw)
        if name in ('pdf','pdf_base64') and ('SYNTHETIC_PRIVATE_NOTES' not in text or '合成保养记录' not in text):raise ValueError('Missing owned private PDF entries')
        if name=='pdf_filtered' and 'SYNTHETIC_PRIVATE_TITLE' not in text:raise ValueError('Missing inclusive filtered PDF entries')
        if name=='pdf':(path.parent/'owner-report.private.pdf').write_bytes(raw)
    result['pdf_evidence']=evidence
    return result


def verify_database(mysql):
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in {'shares':'archive_report_share','archives':'vehicle_archive','payments':'payment'}.items()}
    if actual!={'shares':2,'archives':11,'payments':0}:raise ValueError('Export/share rows missing or duplicated')
    checks={
        'both_shares_revoked':mysql.text('SELECT COUNT(*) FROM archive_report_share WHERE revoked_at IS NOT NULL;')=='2',
        'frozen_summary_sizes':mysql.text("SELECT GROUP_CONCAT(JSON_LENGTH(summary,'$.entries') ORDER BY id) FROM archive_report_share;")=='5,6',
        'three_successful_views_only':mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='ARCHIVE_SHARE_VIEW';")=='3',
        'no_token_in_receipts_or_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%token=%' OR after_state LIKE '%share_token%';")=='0',
        'no_capability_token_in_write_receipts':mysql.text("SELECT COUNT(*) FROM idempotency_record WHERE request_path LIKE '/api/archive/shares%' AND (response_body LIKE '%token=%' OR response_body LIKE '%share_token%');")=='0',
        'no_private_values_in_summary':mysql.text("SELECT COUNT(*) FROM archive_report_share WHERE summary LIKE '%SYNTHETIC_PRIVATE%' OR summary LIKE '%SYNTHETIC_HIDDEN%';")=='0',
    }
    if not all(checks.values()):raise ValueError('Export/share privacy or lifecycle invariant failed')
    return {'row_counts':actual,'checks':checks}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Export API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
