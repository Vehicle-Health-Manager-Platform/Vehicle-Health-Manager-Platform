"""Owner entered data and synthetic health rules; no medical thresholds or supplier IO."""
from datetime import datetime, timedelta, timezone
import json
import uuid
from coupon_api_cases import Cases
from community_api_cases import seed as community_seed
from fulfillment_api_cases import ROOT, assemble

OUTPUT=ROOT/'docs/testing/postman/health-cases.postman_collection.json'
MANIFEST=ROOT/'docs/testing/postman/health-case-manifest.json'
OPERATION_COUNT=14


def replay_key(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'autocare-coupon:replay:'+name))


def cases():
    c=Cases();a=c.add
    archive='/api/archive/1';obs=archive+'/health-observation';otherobs='/api/archive/2/health-observation'
    dashboard='/api/vehicle/1/health-dashboard';confirmation='/api/vehicle/1/health/OIL/confirmation'
    policy='/api/admin/health-policies/1/OIL';history='/api/vehicle/health-observation-history?vehicle_id=1&metric=OIL'
    write_guard='for(const x of ["SYNTHETIC_PRIVATE_PROVIDER","SYNTHETIC_PRIVATE_METHOD","measurement","policy_number"])pm.expect(pm.response.text()).not.to.include(x);'
    c.read('structured_empty',archive+'/structured',extra=['pm.expect(pm.response.json().data.status).to.eql("NOT_SET");'],boundary=('/api/archive/999999/structured',404),error=(archive+'/structured?owner_id=2',400))
    fields={'provider_name':'SYNTHETIC_PRIVATE_PROVIDER','service_items':['SYNTHETIC service'],'performed_mileage':100}
    c.write('structured_set',archive+'/structured',{'expected_revision':0,'fields':fields},method='PUT',extra=[write_guard])
    a('structured_stale','PUT',archive+'/structured',409,'boundary',{'expected_revision':0,'fields':fields})
    a('structured_foreign','PUT',archive+'/structured',404,'unauthorized',{'expected_revision':1,'fields':fields},'other')
    a('structured_read_values','GET',archive+'/structured',200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("OWNER_ENTERED_UNVERIFIED");pm.expect(d.fields.provider_name).to.eql("SYNTHETIC_PRIVATE_PROVIDER");'])
    c.write('structured_clear',archive+'/structured',{'expected_revision':1,'fields':{}},method='PUT')
    a('structured_cleared','GET',archive+'/structured',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.revision).to.eql(2);pm.expect(d.status).to.eql("NOT_SET");pm.expect(d.fields).to.eql({});'])
    c.write('insurance_fields','/api/archive/4/structured',{'expected_revision':0,'fields':{'insurer_name':'SYNTHETIC insurer','policy_number':'SYNTHETIC_PRIVATE_POLICY','coverage_type':'OTHER','coverage_start':'{{today}}','coverage_end':'{{future_day}}'}},method='PUT',extra=[write_guard])
    c.write('inspection_fields','/api/archive/5/structured',{'expected_revision':0,'fields':{'inspection_station':'SYNTHETIC station','inspection_result':'UNKNOWN','valid_until':'{{future_day}}'}},method='PUT')
    a('insurance_bad_date_range','PUT','/api/archive/4/structured',400,'error',{'expected_revision':1,'fields':{'coverage_start':'{{future_day}}','coverage_end':'{{today}}'}})
    c.read('health_current_empty','/api/vehicle/health-observations?vehicle_id=1',extra=['const d=pm.response.json().data;pm.expect(d.list).to.have.lengthOf(6);pm.expect(d.list.every(x=>x.status==="NOT_SET")).to.eql(true);'],boundary=('/api/vehicle/health-observations?vehicle_id=2',404),error=('/api/vehicle/health-observations?vehicle_id=1&owner_id=1',400))
    c.read('observation_empty',obs+'?metric=OIL',extra=['pm.expect(pm.response.json().data.observation.revision).to.eql(0);'],boundary=('/api/archive/3/health-observation?metric=OIL',404),error=(obs+'?metric=INVALID',400))
    measurement={'measurement_name':'SYNTHETIC OIL reading','value':'10','unit':'PERCENT','measured_on':'{{today}}','method':'SYNTHETIC_PRIVATE_METHOD','provenance':'OWNER_MEASURED'}
    record={'metric':'OIL','expected_revision':0,'replace_source':False,'operation':'RECORD','measurement':measurement}
    c.write('record',obs,record,method='PUT',extra=[write_guard])
    a('observation_foreign','PUT',obs,404,'unauthorized',dict(record,expected_revision=1),'other')
    a('future_measurement','PUT',obs,400,'error',dict(record,expected_revision=1,measurement=dict(measurement,measured_on='{{future_day}}')))
    a('percentage_overflow','PUT',obs,400,'boundary',dict(record,expected_revision=1,measurement=dict(measurement,value='100.001')))
    c.read('dashboard_no_policy',dashboard,extra=['const d=pm.response.json().data;pm.expect(d.overall.status).to.eql("UNKNOWN");pm.expect(d.overall.score).to.eql(null);pm.expect(d.metrics[0].status).to.eql("POLICY_NOT_SET");'],boundary=('/api/vehicle/2/health-dashboard',404),error=(dashboard+'?owner_id=1',400))
    c.read('policy_empty',policy,'catalog',extra=['pm.expect(pm.response.json().data.rule).to.eql(null);'],boundary=('/api/admin/health-policies/999999/OIL',404),error=(policy+'?owner_id=1',400))
    a('reviewer_not_catalog','GET',policy,403,'unauthorized',role='reviewer')
    rule={'applicability':'APPLICABLE','source_reference':'SYNTHETIC protocol fixture, never production thresholds','source_checked_on':'{{today}}','valid_until':'{{policy_until}}','source_confirmed':True,'measurement_name':measurement['measurement_name'],'unit':'PERCENT','method':measurement['method'],'provenance':'OWNER_MEASURED','max_age_days':5,'weight':2,'bands':[{'upper_bound':'50','score':25},{'upper_bound':None,'score':100}],'action_project_id':None,'action_score_below':None}
    c.write('policy_set',policy,{'expected_version':0,'rule':rule},'catalog',method='PUT')
    a('policy_stale','PUT',policy,409,'boundary',{'expected_version':0,'rule':rule},'catalog')
    a('before_confirmation','GET',dashboard,200,'boundary',extra=['pm.expect(pm.response.json().data.metrics[0].status).to.eql("CONFIRMATION_REQUIRED");pm.expect(pm.response.json().data.metrics[0].score).to.eql(null);'])
    confirm={'expected_revision':0,'confirmed':True,'observation_revision':1,'policy_version':1}
    c.write('confirm',confirmation,confirm,method='PUT')
    a('confirmation_stale','PUT',confirmation,409,'boundary',confirm)
    a('confirmed_reference','GET',dashboard,200,'normal',extra=['const d=pm.response.json().data;pm.expect(d.metrics[0].status).to.eql("REFERENCE_READY");pm.expect(d.metrics[0].score).to.eql(25);pm.expect(d.overall.status).to.eql("UNKNOWN");pm.expect(d.overall.score).to.eql(null);pm.expect(d.required_projects).to.have.lengthOf(0);'])
    c.write('withdraw_confirmation',confirmation,dict(confirm,expected_revision=1,confirmed=False),method='PUT')
    a('old_confirmation_receipt','PUT',confirmation,200,'boundary',confirm,key=replay_key('confirm'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_confirm")));'])
    a('withdrawal_not_undone','GET',dashboard,200,'boundary',extra=['const m=pm.response.json().data.metrics[0];pm.expect(m.status).to.eql("CONFIRMATION_REQUIRED");pm.expect(m.confirmation.confirmed).to.eql(false);'])
    correction=dict(record,expected_revision=1,operation='CORRECT',measurement=dict(measurement,value='20'))
    c.write('correct',obs,correction,method='PUT')
    c.write('reconfirm',confirmation,dict(confirm,expected_revision=2,observation_revision=2),method='PUT')
    a('replacement_requires_confirmation','PUT',otherobs,409,'boundary',dict(record,expected_revision=2,measurement=dict(measurement,value='30')))
    c.write('replace_source',otherobs,dict(record,expected_revision=2,replace_source=True,measurement=dict(measurement,value='30')),method='PUT')
    a('source_change_invalidates_confirmation','GET',dashboard,200,'boundary',extra=['pm.expect(pm.response.json().data.metrics[0].status).to.eql("CONFIRMATION_REQUIRED");'])
    clear={'metric':'OIL','expected_revision':3,'replace_source':False,'operation':'CLEAR','measurement':{}}
    a('cannot_clear_different_source','PUT',obs,409,'boundary',clear)
    c.write('clear',otherobs,clear,method='PUT')
    a('double_clear_rejected','PUT',otherobs,409,'boundary',dict(clear,expected_revision=4))
    c.read('history',history+'&limit=2',extra=['const d=pm.response.json().data;pm.expect(d.list.map(x=>x.revision)).to.eql([4,3]);pm.expect(d.has_more).to.eql(true);pm.expect(d.next_before_revision).to.eql(3);'],boundary=(history+'&before_revision=1',200),error=(history+'&limit=51',400))
    a('history_older_page','GET',history+'&before_revision=3&limit=2',200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.list.map(x=>x.revision)).to.eql([2,1]);pm.expect(d.list[0].operation).to.eql("CORRECT");pm.expect(d.list[0].supersedes_revision).to.eql(1);pm.expect(d.list[1].corrected).to.eql(true);pm.expect(d.has_more).to.eql(false);'])
    a('history_foreign','GET',history,404,'unauthorized',role='other')
    c.write('coolant_negative',obs,dict(record,metric='COOLANT',measurement=dict(measurement,measurement_name='SYNTHETIC coolant',unit='CELSIUS',value='-10')),method='PUT')
    c.write('coolant_clear',obs,dict(clear,metric='COOLANT',expected_revision=1),method='PUT')
    c.write('percentage_maximum',obs,dict(record,metric='AUX_BATTERY',measurement=dict(measurement,measurement_name='SYNTHETIC battery',value='100')),method='PUT')
    c.write('policy_withdraw',policy,{'expected_version':1,'rule':None},'catalog',method='PUT')
    a('old_policy_receipt','PUT',policy,200,'boundary',{'expected_version':0,'rule':rule},'catalog',key=replay_key('policy_set'),extra=['pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy_set")));'])
    a('policy_stays_withdrawn','GET',policy,200,'boundary',role='catalog',extra=['const d=pm.response.json().data;pm.expect(d.version).to.eql(2);pm.expect(d.rule).to.eql(null);'])
    reminder=archive+'/reminder';reminders='/api/vehicle/reminders?vehicle_id=1'
    c.read('reminder_empty',reminder,extra=['pm.expect(pm.response.json().data.setting.status).to.eql("NOT_SET");'],boundary=('/api/archive/3/reminder',404),error=(reminder+'?owner_id=1',400))
    due={'expected_revision':0,'active':True,'due_mileage':100,'replace_source':False}
    c.write('reminder_due',reminder,due,method='PUT')
    c.read('reminders',reminders,extra=['const d=pm.response.json().data;pm.expect(d.list).to.have.lengthOf(3);pm.expect(d.list[0].status).to.eql("DUE");pm.expect(d.list[0].triggered_by).to.include("MILEAGE");'],boundary=('/api/vehicle/reminders?vehicle_id=2',404),error=(reminders+'&owner_id=1',400))
    a('reminder_stale','PUT',reminder,409,'boundary',due)
    a('reminder_foreign','PUT',reminder,404,'unauthorized',dict(due,expected_revision=1),'other')
    planned=dict(due,expected_revision=1,due_mileage=1000,due_date='{{future_day}}')
    c.write('reminder_planned',reminder,planned,method='PUT')
    a('reminder_planned_read','GET',reminder,200,'normal',extra=['pm.expect(pm.response.json().data.setting.status).to.eql("PLANNED");'])
    preference={'expected_revision':0,'maintenance_days':3,'maintenance_km':500,'insurance_days':0,'inspection_days':0}
    c.write('preference','/api/vehicle/1/reminder-preference',preference,method='PUT')
    a('preference_stale','PUT','/api/vehicle/1/reminder-preference',409,'boundary',preference)
    a('preference_foreign','PUT','/api/vehicle/2/reminder-preference',404,'unauthorized',preference)
    a('early_reminder','GET',dashboard,200,'boundary',extra=['const d=pm.response.json().data;pm.expect(d.reminders.find(x=>x.kind==="MAINTENANCE").status).to.eql("UPCOMING");pm.expect(d.preference.revision).to.eql(1);'])
    c.write('disable_reminder',reminder,{'expected_revision':2,'active':False},method='PUT')
    c.write('insurance_due','/api/archive/4/reminder',{'expected_revision':0,'active':True,'due_date':'{{today}}'},method='PUT')
    a('insurance_mileage_rejected','PUT','/api/archive/4/reminder',400,'error',dict(due,expected_revision=1))
    c.write('inspection_planned','/api/archive/5/reminder',{'expected_revision':0,'active':True,'due_date':'{{policy_until}}'},method='PUT')
    c.write('inspection_disable','/api/archive/5/reminder',{'expected_revision':1,'active':False},method='PUT')
    a('final_reminders','GET',reminders,200,'boundary',extra=['const i=pm.response.json().data.list;pm.expect(i.map(x=>x.status)).to.eql(["INACTIVE","DUE","INACTIVE"]);'])
    a('foreign_vehicle_unchanged','GET','/api/vehicle/health-observations?vehicle_id=2',200,'unauthorized',role='other',extra=['pm.expect(pm.response.json().data.list.every(x=>x.status==="NOT_SET")).to.eql(true);'])
    for name,path in [
        ('health_list_duplicate','/api/vehicle/health-observations?vehicle_id=1&vehicle_id=2'),
        ('health_context_duplicate',obs+'?metric=OIL&metric=TIRE'),
        ('health_context_unknown',obs+'?metric=OIL&user_id=1'),
        ('history_duplicate_vehicle',history+'&vehicle_id=2'),
        ('history_duplicate_metric',history+'&metric=TIRE'),
        ('history_duplicate_cursor',history+'&before_revision=3&before_revision=2'),
        ('history_duplicate_limit',history+'&limit=1&limit=2'),
        ('history_unknown_selector',history+'&owner_id=2'),
        ('reminders_duplicate',reminders+'&vehicle_id=2')]:
        a(name,'GET',path,400,'error')
    for name,path,body in [('structured_query',archive+'/structured',{'expected_revision':2,'fields':{}}),('observation_query',obs,dict(record,expected_revision=4)),('reminder_query',reminder,{'expected_revision':3,'active':False})]:
        a(name,'PUT',path+'?user_id=1',400,'error',body)
    return c.items


def assets():return assemble(cases(),'Autocare owner structured archives health and reminders','isolated_health_cases',OPERATION_COUNT)


def seed(mysql,secret):
    result=community_seed(mysql,secret)
    mysql.sql('UPDATE operator_account SET can_knowledge=0,can_catalog=1 WHERE id=4;')
    result['catalog_token']=result.pop('knowledge_token')
    for archive,vehicle,kind in [(1,1,1),(2,1,1),(3,2,1),(4,1,3),(5,1,7)]:
        mysql.sql(f"INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES({archive},{vehicle},{kind},3,'{{}}','2026-10-01');")
    today=datetime.now(timezone(timedelta(hours=8))).date()
    result.update(isolated_health_cases='true',today=today.isoformat(),future_day=(today+timedelta(days=2)).isoformat(),policy_until=(today+timedelta(days=10)).isoformat())
    return result


def environment(tokens):return [('KNOWLEDGE_AI_ENABLED','false'),('EXPERIENCE_PUBLICATION_ENABLED','false')]


def audit(path,collection,manifest,tokens):
    from run_api_acceptance import audit_owner_cases
    return audit_owner_cases(path,collection,manifest,tokens,expected_operations=OPERATION_COUNT)


def verify_database(mysql):
    tables={'structured':'archive_structured_detail','observations':'vehicle_health_observation','history':'vehicle_health_observation_history','confirmations':'health_evidence_confirmation','policies':'model_health_policy','reminders':'archive_reminder','preferences':'vehicle_reminder_preference','archives':'vehicle_archive','payments':'payment'}
    actual={key:int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key,table in tables.items()}
    if actual!={'structured':3,'observations':3,'history':7,'confirmations':1,'policies':1,'reminders':3,'preferences':1,'archives':5,'payments':0}:raise ValueError('Health/structured/reminder rows missing or duplicated: '+str(actual))
    checks={
        'structured_clear_keeps_revision':mysql.text("SELECT CONCAT(revision,':',JSON_LENGTH(fields_json)) FROM archive_structured_detail WHERE archive_id=1;")=='2:0',
        'current_revisions':mysql.text("SELECT GROUP_CONCAT(CONCAT(metric,':',revision) ORDER BY metric) FROM vehicle_health_observation;")=='AUX_BATTERY:1,COOLANT:2,OIL:4',
        'policy_withdrawn':mysql.text("SELECT CONCAT(version,':',IF(rule_json IS NULL,1,0)) FROM model_health_policy;")=='2:1',
        'confirmation_not_rebound':mysql.text("SELECT CONCAT(revision,':',observation_revision) FROM health_evidence_confirmation;")=='3:2',
        'reminder_revisions':mysql.text("SELECT GROUP_CONCAT(CONCAT(archive_type,':',revision,':',active) ORDER BY archive_type) FROM archive_reminder;")=='1:3:0,3:1:1,7:2:0',
        'other_owner_untouched':mysql.text('SELECT COUNT(*) FROM vehicle_health_observation WHERE user_id=2;')=='0',
        'private_values_not_in_audit':mysql.text("SELECT COUNT(*) FROM audit_log WHERE actor_type='user' AND (after_state LIKE '%SYNTHETIC_PRIVATE_PROVIDER%' OR after_state LIKE '%SYNTHETIC_PRIVATE_METHOD%' OR after_state LIKE '%SYNTHETIC_PRIVATE_POLICY%');")=='0',
    }
    if not all(checks.values()):raise ValueError('Health source/version/privacy invariant failed')
    return {'row_counts':actual,'checks':checks,'rule_scope':'synthetic protocol values, no production thresholds or official verification'}


if __name__=='__main__':
    collection,manifest=assets()
    for path,data in [(OUTPUT,collection),(MANIFEST,manifest)]:path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Health API cases:',manifest['cases'],'assertions:',sum(x['assertions'] for x in manifest['items']))
