"""Real HTTP + scheduled invitation worker, synthetic upstream identity results only."""
import argparse,hashlib,json,platform,re,subprocess,time,uuid
from datetime import datetime,timezone
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CLASS='com.autocare.platform.RealInvitationRewardJourneyTest'
JUNIT=ROOT/'backend/target/surefire-reports'/('TEST-'+CLASS+'.xml')
EVIDENCE=ROOT/'backend/target/invitation-reward-evidence.json'
OUTPUT=ROOT/'backend/target/invitation-reward-acceptance.json'
CLEANUP_ROLES=frozenset({'mysql'})
CHECKS={'missing_policy_is_terminal','audit_failure_rolls_back_entire_pair','policy_snapshot_frozen','retry_uses_old_snapshot_once','insufficient_stock_never_partially_issues','staff_reward_is_invitee_only','test_unknown_and_unverified_phone_have_no_rewards','stopped_source_does_not_issue','recipient_sees_only_own_coupon_id','staff_owner_id_overlap_does_not_leak_coupon','unknown_policy_not_backfilled','final_inventory_and_audit_unique','no_money_or_points','policy_stops_not_undone','completed_batches_never_issue_twice','public_projection_has_no_identity_evidence','reads_never_issue_rewards'}


def inventory():
    cases=[];p='/api/admin/invite/reward-policies'
    def add(name,kind,role,method,path,status=200):cases.append(dict(name=name,operation=re.sub(r'/\d+','/{id}',path.split('?')[0]),kind=kind,role=role,method=method,path=path,status=status,passed=True))
    def write(name,method,path,role):
        add(name,'normal',role,method,path);add(name+'_replay','boundary',role,method,path)
        add(name+'_invalid','error',role,method,path,400);add(name+'_anonymous','unauthorized','anonymous',method,path,401)
        add(name+'_wrong_role','unauthorized','merchant' if role=='owner' else 'owner',method,path,403)
    def read(name,path,role,paged=True):
        add(name,'normal',role,'GET',path);add(name+'_boundary','boundary',role,'GET',path+'?page=1000000' if paged else path.replace('scope=OWNER','scope=STAFF'))
        add(name+'_invalid','error',role,'GET',path+('&' if '?' in path else '?')+'user_id=2',400)
        add(name+'_anonymous','unauthorized','anonymous','GET',path,401);add(name+'_wrong_role','unauthorized','merchant' if role=='owner' else 'owner','GET',path,403)
    write('owner_code','POST','/api/invite/code','owner');write('staff_code','POST','/api/merchant/invite/code','merchant')
    read('empty_policies',p,'invite');read('empty_sources','/api/admin/invite/reward-coupons','invite');read('empty_current','/api/invite/reward-policy?scope=OWNER','owner',False)
    for i in [1,2]:
        write('coupon_'+str(i),'POST','/api/merchant/coupons','merchant');write('coupon_publish_'+str(i),'PUT',f'/api/merchant/coupons/{i}/status','merchant');write('coupon_pool_'+str(i),'PUT',f'/api/admin/coupons/{i}/pool','coupon')
    add('selected_sources','normal','invite','GET','/api/admin/invite/reward-coupons')
    write('policy_owner','POST',p,'invite');write('policy_edit','PUT',p+'/1','invite')
    add('stale_policy','boundary','invite','PUT',p+'/1',409);add('missing_policy','boundary','invite','PUT',p+'/999999',404);add('coupon_cannot_manage','unauthorized','coupon','POST',p,403)
    write('owner_publish','PUT',p+'/1/status','invite');add('configured_owner','normal','owner','GET','/api/invite/reward-policy?scope=OWNER')
    write('policy_replacement','POST',p,'invite');add('unique_active_policy','boundary','invite','PUT',p+'/2/status',409)
    write('owner_stop','PUT',p+'/1/status','invite');add('old_publish_receipt','boundary','invite','PUT',p+'/1/status');add('stopped_cannot_publish','boundary','invite','PUT',p+'/1/status',409)
    write('replacement_publish','PUT',p+'/2/status','invite');write('policy_staff','POST',p,'invite');write('staff_publish','PUT',p+'/3/status','invite')
    write('stop_service_source','PUT','/api/merchant/coupons/2/status','merchant');add('current_source_unavailable','boundary','owner','GET','/api/invite/reward-policy?scope=OWNER')
    write('replacement_stop','PUT',p+'/2/status','invite');write('staff_stop','PUT',p+'/3/status','invite')
    read('owner_rewards','/api/invite/rewards','owner');add('recipient_rewards','normal','recipient','GET','/api/invite/rewards');read('staff_rewards','/api/merchant/invite/rewards','merchant')
    add('foreign_owner_rewards','unauthorized','other','GET','/api/invite/rewards');add('foreign_shop_rewards','unauthorized','other_merchant','GET','/api/merchant/invite/rewards')
    read('owner_coupon','/api/coupon/mine','owner');add('recipient_coupon','normal','recipient','GET','/api/coupon/mine');add('staff_recipient_coupon','normal','staff_recipient','GET','/api/coupon/mine');add('waiting_coupon','boundary','waiting','GET','/api/coupon/mine')
    add('final_policies','normal','invite','GET',p);add('current_after_stop','boundary','owner','GET','/api/invite/reward-policy?scope=OWNER');add('duplicate_scope','error','owner','GET','/api/invite/reward-policy?scope=OWNER&scope=STAFF',400);add('duplicate_page','error','owner','GET','/api/invite/rewards?page=1&page=2',400)
    return cases


def source_digest():
    paths=[p for p in (ROOT/'backend/src/main').rglob('*') if p.is_file()]+list((ROOT/'backend/src/test').rglob('*.java'))+list((ROOT/'docs/sql/migrations').glob('*.sql'))+[ROOT/'backend/pom.xml',Path(__file__)]
    h=hashlib.sha256()
    for p in sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix()):h.update(p.relative_to(ROOT).as_posix().encode());h.update(p.read_bytes())
    return h.hexdigest()


def audit(junit,evidence):
    r=ET.parse(junit).getroot()
    if r.get('name')!=CLASS or {k:int(r.get(k,'-1')) for k in ['tests','failures','errors','skipped']}!={'tests':1,'failures':0,'errors':0,'skipped':0}:raise ValueError('Reward journey missing, failed or skipped')
    tests=r.findall('testcase')
    if len(tests)!=1 or tests[0].get('classname')!=CLASS or tests[0].get('name')!='realHttpQualificationFreezeAndScheduledAtomicRewardDelivery' or any(tests[0].find(k) is not None for k in ['failure','error','skipped']):raise ValueError('Unexpected reward execution test')
    d=json.loads(evidence.read_text(encoding='utf-8'))
    if d.get('source_sha256')!=source_digest():raise ValueError('Reward proof belongs to different source content')
    if d.get('passed') is not True or d.get('cases')!=inventory():raise ValueError('Actual reward HTTP method/path/query/role/status changed')
    if set(d.get('checks',{}))!=CHECKS or not all(v is True for v in d['checks'].values()):raise ValueError('Reward freeze/atomicity/worker/privacy checks missing')
    cleanup=d.get('cleanup',{})
    if not isinstance(cleanup,dict) or set(cleanup)!=CLEANUP_ROLES or not all(v is True for v in cleanup.values()):raise ValueError('Owned reward database not removed')
    coverage={}
    for c in d['cases']:coverage.setdefault(c['method']+' '+c['operation'],set()).add(c['kind'])
    if len(coverage)!=14 or any(v!={'normal','boundary','error','unauthorized'} for v in coverage.values()):raise ValueError('Reward HTTP four-category coverage incomplete')
    return dict(requests=len(d['cases']),operations=len(coverage),coverage={k:sorted(v) for k,v in coverage.items()},checks=d['checks'],cleanup=d['cleanup'],cases=d['cases'])


def main():
    parser=argparse.ArgumentParser(description=__doc__);mode=parser.add_mutually_exclusive_group();mode.add_argument('--docker',action='store_true');mode.add_argument('--check-only',action='store_true');args=parser.parse_args()
    OUTPUT.unlink(missing_ok=True);start=time.monotonic();before=source_digest();code=0;error=None;proof=None
    if not args.check_only:
        JUNIT.unlink(missing_ok=True);EVIDENCE.unlink(missing_ok=True)
        command=['mvn','-B','-Dtest=RealInvitationRewardJourneyTest,InvitePolicyHttpTest,InvitationRewardHttpTest','test']
        if args.docker:
            host=['-e','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'] if platform.system() in ['Windows','Darwin'] else []
            command=['docker','run','--rm','--name','autocare-reward-journey-'+uuid.uuid4().hex[:8],'--mount',f'type=bind,source={ROOT},target=/workspace','--mount','type=volume,source=autocare-maven-cache,target=/root/.m2','--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',*host,'-e','JAVA_TOOL_OPTIONS=-Dapi.version=1.44','-w','/workspace/backend','maven:3.9-eclipse-temurin-17',*command]
        (ROOT/'.cache').mkdir(exist_ok=True)
        try:
            with (ROOT/'.cache/reward-journey.private.log').open('wb') as log:code=subprocess.run(command,cwd=ROOT/'backend',stdout=log,stderr=subprocess.STDOUT,timeout=1200).returncode
        except (OSError,subprocess.TimeoutExpired):code=127
    try:proof=audit(JUNIT,EVIDENCE)
    except Exception as e:error=str(e) if isinstance(e,ValueError) else type(e).__name__
    unchanged=before==source_digest();passed=code==0 and error is None and unchanged
    report=dict(version=1,passed=passed,full_api_acceptance_passed=False,finished_at=datetime.now(timezone.utc).isoformat(),duration_seconds=round(time.monotonic()-start,3),source_sha256=before,sources_unchanged=unchanged,maven_exit_code=None if args.check_only else code,execution_mode='ci-report-check' if args.check_only else 'local-execution',proof=proof,error=error,boundaries=['synthetic upstream registration/phone results; real HTTP/MySQL/production qualification freeze and scheduled worker','test resets next_attempt_at only to simulate retry deadline; production backoff unchanged','no real identity provider, external supplier, money or device acceptance'])
    OUTPUT.parent.mkdir(parents=True,exist_ok=True);OUTPUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('Invitation reward acceptance: '+('PASS' if passed else 'FAIL; inspect .cache/reward-journey.private.log and backend/target/invitation-reward-acceptance.json'));return 0 if passed else 1


if __name__=='__main__':raise SystemExit(main())
