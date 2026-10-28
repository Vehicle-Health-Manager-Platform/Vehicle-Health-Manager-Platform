import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_business_api_acceptance as runner
from summarize_api_coverage import journey_evidence

CAT = {'one': dict(method='GET', path='/api/one', protected=True),
       'two': dict(method='POST', path='/api/two', protected=False)}


def row(name='community', origin='EXECUTED'):
    return dict(kind='business', name=name, passed=True, origin=origin, requests=4,
                assertions=8, anonymous_requests=2, anonymous_assertions=6,
                anonymous_operations=['one'], coverage={'one':list(runner.KINDS)})


class CurrentApiAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.work=self.root/'work';self.work.mkdir()

    def test_default_plan_has_all_29_business_and_seven_independent_journeys(self):
        items=runner.plan(list(runner.SUITES),'run')
        self.assertEqual(29,len(runner.SUITES));self.assertEqual(36,len(items))
        self.assertEqual(36,len(set(items)))
        self.assertEqual([('business','owner')],runner.plan(['owner'],'none'))
        for names in ([],['owner','owner'],['../owner']):
            with self.assertRaises(ValueError):runner.plan(names,'none')

    def test_failed_suite_continues_without_exposing_exception_text(self):
        called=[];results=[]
        def execute(kind,name):
            called.append(name)
            if name=='owner':raise ValueError('private-token-never-printed')
            return row(name)
        runner.run_plan([('business','owner'),('business','community')],execute,results.append)
        self.assertEqual(['owner','community'],called)
        self.assertFalse(results[0]['passed']);self.assertTrue(results[1]['passed'])
        self.assertNotIn('private-token',json.dumps(results))

    def test_counts_deduplicate_operations_and_distinguish_audit_from_execution(self):
        first=row();second=row('other','AUDIT_EXISTING')
        failed=dict(row('failed'),passed=False,requests=9000)
        result=runner.summarize([first,second,failed],CAT,{'two':'blocked-external'})
        self.assertEqual(1,result['unique_four_kind_operations_count'])
        self.assertEqual(1,result['unique_anonymous_operations'])
        self.assertEqual(4,result['anonymous_request_executions'])
        self.assertEqual(4,result['executed_business_requests'])
        self.assertEqual(4,result['audited_existing_business_requests'])
        self.assertEqual('blocked-external',result['missing'][0]['conclusion'])
        self.assertFalse(result['full_api_acceptance_passed'])
        self.assertEqual('not-covered-in-this-run',runner.summarize([],CAT,{})['missing'][0]['conclusion'])

    def test_unknown_operations_categories_and_public_anonymous_probes_are_rejected(self):
        for change in ({'coverage':{'unknown':['normal']}},{'coverage':{'one':['invented']}},
                       {'origin':'HISTORICAL_RELABELLED'}, {'anonymous_operations':['two']}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                runner.summarize([dict(row(),**change)],CAT,{})

    def test_partial_kinds_do_not_establish_full_operation_coverage(self):
        a=dict(row(),coverage={'one':['normal','boundary']})
        self.assertEqual(0,runner.summarize([a],CAT,{})['unique_four_kind_operations_count'])
        b=dict(row(),coverage={'one':['error','unauthorized']})
        self.assertEqual(1,runner.summarize([a,b],CAT,{})['unique_four_kind_operations_count'])

    def test_lock_is_exclusive_and_does_not_remove_a_different_owner(self):
        path=self.root/'locks/run.json';a=runner.RunLock(path);b=runner.RunLock(path)
        a.acquire()
        with self.assertRaises(FileExistsError):b.acquire()
        self.assertFalse(b.release());self.assertTrue(path.exists())
        self.assertTrue(a.release());self.assertFalse(path.exists())

    def test_native_bridges_are_disabled_in_child_environment_only(self):
        with patch.dict('os.environ',{'AUTOCARE_FINANCE_NATIVE_QA':'1','AUTOCARE_DELIVERY_NATIVE_QA':'1',
                                     'AUTOCARE_PICKUP_SCAN_NATIVE_QA':'1','AUTOCARE_NATIVE_MATERIAL_RUN_ID':'deadbeef',
                                     'AUTOCARE_NATIVE_UPLOAD_KIND':'image'}):
            env=runner.child_environment()
            self.assertEqual('',env['AUTOCARE_NATIVE_MATERIAL_RUN_ID'])
            self.assertEqual('pdf',env['AUTOCARE_NATIVE_UPLOAD_KIND'])
            self.assertTrue(all(env[n]=='0' for n in ('AUTOCARE_FINANCE_NATIVE_QA','AUTOCARE_DELIVERY_NATIVE_QA','AUTOCARE_PICKUP_SCAN_NATIVE_QA')))
            self.assertEqual('1',runner.os.environ['AUTOCARE_FINANCE_NATIVE_QA'])

    def test_business_requires_exactly_one_new_report_and_a_successful_child(self):
        cache=self.root/'.cache';cache.mkdir();(cache/'api-acceptance-old').mkdir()
        for count,exit_code in ((0,0),(2,0),(1,1)):
            with self.subTest(count=count,exit_code=exit_code):
                def child(*args):
                    for i in range(count):(cache/('api-acceptance-new-'+str(count)+str(exit_code)+str(i))).mkdir()
                    return exit_code
                with patch.object(runner,'ROOT',self.root),patch.object(runner,'run_child',side_effect=child),patch.object(runner,'audit_business') as audit:
                    with self.assertRaises(ValueError):runner.execute_business('owner',self.work,'source',CAT,{})
                    audit.assert_not_called()

    def test_journey_run_backs_up_and_removes_all_old_outputs(self):
        target=self.root/'backend/target';target.mkdir(parents=True)
        module=SimpleNamespace(OUTPUT=target/'report.json',JUNIT=target/'test.xml',EVIDENCE=target/'raw.json')
        for p in (module.OUTPUT,module.JUNIT,module.EVIDENCE):p.write_text('old proof',encoding='utf-8')
        with patch.object(runner,'ROOT',self.root):runner.clear_journey_outputs(module,self.work,'upload')
        for p in (module.OUTPUT,module.JUNIT,module.EVIDENCE):
            self.assertFalse(p.exists())
            self.assertEqual('old proof',(self.work/'previous-upload'/p.name).read_text())
        module.OUTPUT=self.root/'outside.json'
        with patch.object(runner,'ROOT',self.root),self.assertRaises(ValueError):runner.clear_journey_outputs(module,self.work,'other')

    def test_zero_exit_without_new_journey_output_cannot_reuse_old_evidence(self):
        target=self.root/'backend/target';target.mkdir(parents=True)
        module=SimpleNamespace(OUTPUT=target/'report.json',JUNIT=target/'test.xml',EVIDENCE=target/'raw.json',__file__=str(self.root/'child.py'))
        module.OUTPUT.write_text('{"passed":true}',encoding='utf-8')
        with patch.object(runner,'ROOT',self.root),patch.object(runner.importlib,'import_module',return_value=module),patch.object(runner,'run_child',return_value=0):
            with self.assertRaises(FileNotFoundError):runner.execute_journey('upload','run',self.work,CAT,{})

    def test_existing_journey_audit_never_spawns_http_or_maven_and_keeps_origin(self):
        inventory=[dict(name=k,operation='/api/one',kind=k,role='owner',method='GET',path='/api/one',status=200,passed=True) for k in runner.KINDS]
        evidence=self.work/'raw.json';evidence.write_text('{}');junit=self.work/'test.xml';junit.write_text('<testsuite/>')
        module=SimpleNamespace(audit=lambda *a:dict(requests=4,checks={'db':True},cleanup={'mysql':True}),
                               inventory=lambda:inventory,JUNIT=junit,EVIDENCE=evidence,source_digest=lambda:'current-source')
        with patch.object(runner.importlib,'import_module',return_value=module),patch.object(runner,'run_child') as child:
            proof=runner.execute_journey('upload','audit',self.work,CAT,{})
        child.assert_not_called();self.assertEqual('AUDIT_EXISTING',proof['origin'])
        self.assertEqual(4,proof['requests']);self.assertEqual(set(runner.KINDS),set(proof['coverage']['one']))

    def test_database_failure_is_not_hidden_by_other_counts(self):
        self.assertTrue(runner.checks_passed({'counts':{'x':0},'checks':{'safe':True}}))
        self.assertFalse(runner.checks_passed({'nested':{'read_checks':{'safe':False}}}))
        self.assertFalse(runner.checks_passed({'checks':{}}))

    def run_main(self,executor,digests=('before','before')):
        with patch.object(runner,'ROOT',self.root),patch.object(runner,'input_digest',side_effect=digests),patch.object(runner,'source_hash',return_value='source'),patch.object(runner,'catalog',return_value=CAT),patch.object(runner,'verdicts',return_value={}),patch.object(runner,'execute_business',side_effect=executor),contextlib.redirect_stdout(io.StringIO()):
            code=runner.main(['--suites','community','--journeys','none'])
        reports=list((self.root/'.cache').glob('business-api-acceptance-*/report.json'))
        return code,json.loads(reports[0].read_text(encoding='utf-8'))

    def test_main_publishes_scope_without_production_or_full_backend_claim(self):
        code,report=self.run_main(lambda *a:row())
        self.assertEqual(0,code);self.assertTrue(report['passed']);self.assertTrue(report['lock_released'])
        self.assertFalse(report['in_progress'])
        for key in ('production_acceptance_passed','full_api_acceptance_passed','full_backend_revalidated','real_upstream_verified','native_ui_verified'):self.assertFalse(report[key])

    def test_source_change_invalidates_an_otherwise_passing_run(self):
        code,report=self.run_main(lambda *a:row(),('before','after'))
        self.assertEqual(1,code);self.assertFalse(report['passed']);self.assertFalse(report['sources_unchanged'])

    def test_interruption_does_not_claim_completed_business_or_hold_finished_child_lock(self):
        def interrupt(*args):raise KeyboardInterrupt()
        code,report=self.run_main(interrupt)
        self.assertEqual(130,code);self.assertFalse(report['passed']);self.assertEqual([],report['suites'])
        self.assertTrue(report['lock_released']);self.assertIn('INTERRUPTED',report['reason'])

    def test_unfinished_child_retains_lock_for_resource_verification(self):
        def interrupt(*args):raise runner.ChildStillRunning(123)
        code,report=self.run_main(interrupt)
        self.assertEqual(130,code);self.assertFalse(report['passed']);self.assertFalse(report['lock_released'])
        self.assertTrue((self.root/'.cache/business-api-acceptance.lock').exists())
        self.assertEqual(123,report['child_pid'])

    def test_existing_lock_refuses_second_run_without_erasing_owner(self):
        lock=runner.RunLock(self.root/'.cache/business-api-acceptance.lock');lock.acquire()
        with patch.object(runner,'ROOT',self.root),patch.object(runner,'input_digest') as stamp,contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(2,runner.main(['--suites','owner','--journeys','none']))
        stamp.assert_not_called();self.assertTrue(lock.release())

    def test_lost_lock_never_removes_replacement_or_returns_success(self):
        def execute(*args):
            (self.root/'.cache/business-api-acceptance.lock').write_text('{"token":"new-owner"}')
            return row()
        code,report=self.run_main(execute)
        self.assertEqual(1,code);self.assertFalse(report['passed']);self.assertFalse(report['lock_released'])
        self.assertEqual('new-owner',json.loads((self.root/'.cache/business-api-acceptance.lock').read_text())['token'])

    def test_child_interrupt_waits_for_cleanup_and_retains_pid_if_not_finished(self):
        child=SimpleNamespace(pid=73)
        for outcomes,exception in (([KeyboardInterrupt(),0],KeyboardInterrupt),
                                   ([KeyboardInterrupt(),subprocess.TimeoutExpired('child',30)],runner.ChildStillRunning)):
            with self.subTest(exception=exception):
                from unittest.mock import Mock
                child.wait=Mock(side_effect=outcomes)
                with patch.object(runner.subprocess,'Popen',return_value=child),self.assertRaises(exception):
                    runner.run_child(['child.py'],self.work/'log',{})
                self.assertEqual(30,child.wait.call_args.kwargs['timeout'])


class BusinessEvidenceAuditTest(unittest.TestCase):
    def setUp(self):
        import community_api_cases as module
        self.module=module;self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.work=Path(self.temp.name)
        collection,manifest=module.assets();self.cat=runner.catalog()
        definitions,_=runner.newman_evidence('community',manifest,collection,runner.collection_digests(module.OUTPUT),None,self.cat)
        self.actual=dict(requests=len(manifest['items']),assertions=sum(c['assertions'] for c in manifest['items']),failures=0,coverage=definitions,
                         cases=[{k:c[k] for k in ('name','operation_id','kind','status')}|{'passed':True} for c in manifest['items']])
        self.anonymous=dict(requests=348,assertions=1044,failures=0,operations=[k for k,v in self.cat.items() if v['protected']])
        self.report=dict(version=1,passed=True,sources_unchanged=True,source_sha256='a'*64,newman_exit_code=0,
                         prepared_default_disabled_verified=True,community_default_disabled_verified=True,business_table_counts_unchanged=True,
                         community_newman_exit_code=0,community_collection_sha256=runner.digest(module.OUTPUT),
                         collection_sha256=runner.digest(runner.OUTPUT/'anonymous.postman_collection.json'),
                         evidence=self.anonymous,community_evidence=self.actual,community_database={'checks':{'sql':True}},
                         cleanup=[dict(role=role,passed=True) for role in ('backend','mysql','network')])
        (self.work/'community-environment.private.json').write_text(json.dumps({'values':[dict(key='owner_token',value='secret-not-exported',enabled=True)]}))

    def audit(self):
        path=self.work/'report.json';path.write_text(json.dumps(self.report),encoding='utf-8')
        with patch.object(runner,'audit_newman',return_value=self.anonymous),patch.object(self.module,'audit',return_value=self.actual,create=True):
            return runner.audit_business('community',path,'a'*64,self.cat)

    def test_reaudits_real_case_definitions_and_exports_no_private_context(self):
        proof=self.audit();self.assertTrue(proof['passed']);self.assertTrue(proof['private_raw_evidence_reaudited'])
        self.assertNotIn('secret-not-exported',json.dumps(proof));self.assertNotIn('cases',proof)

    def test_stale_missing_failed_or_native_evidence_is_rejected(self):
        pristine=copy.deepcopy(self.report)
        mutations=[lambda r:r.update(source_sha256='old'),lambda r:r.update(sources_unchanged=False),
                   lambda r:r.update(newman_exit_code=1),lambda r:r.update(prepared_default_disabled_verified=False),
                   lambda r:r.update(community_default_disabled_verified=False),lambda r:r.update(business_table_counts_unchanged=False),
                   lambda r:r.update(cleanup=[]),lambda r:r.update(community_collection_sha256='bad'),
                   lambda r:r['community_evidence']['cases'].pop(),lambda r:r['community_database']['checks'].update(sql=False),
                   lambda r:r.update(community_ui={'executed':True})]
        for index,change in enumerate(mutations):
            with self.subTest(index=index):
                self.report=copy.deepcopy(pristine);self.actual=copy.deepcopy(pristine['community_evidence']);change(self.report)
                with self.assertRaises(ValueError):self.audit()



class RecognitionIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);(self.root/'.cache').mkdir();self.work=self.root/'work';self.work.mkdir()
        self.proof=dict(passed=True,test_class='FixtureTest',tests=6,requests=4,coverage={'GET /api/one':list(runner.KINDS)})
        self.report=dict(passed=True,sources_unchanged=True,source_sha256='source',execution_mode='EXECUTED',maven_exit_code=0,owned_databases_removed=True,suites=[dict(name='fixture',**self.proof)])
        self.module=SimpleNamespace(__file__=str(self.root/'helper.py'),SPECS={'fixture':('FixtureTest','test','/api/one')},source_digest=lambda:'source',audit=lambda *a:self.proof)
    def execute(self,mode='run',exit_code=0,count=1):
        def child(*args):
            for i in range(count):
                path=self.root/'.cache'/('recognition-http-'+str(i));path.mkdir();(path/'report.json').write_text(json.dumps(self.report))
            return exit_code
        with patch.object(runner,'ROOT',self.root),patch.object(runner.importlib,'import_module',return_value=self.module),patch.object(runner,'run_child',side_effect=child):return runner.execute_recognition(mode,self.work,CAT,{})
    def test_optional_plan_keeps_default_scope_and_adds_only_one_batch(self):
        self.assertEqual(36,len(runner.plan(list(runner.SUITES),'run')))
        self.assertEqual([('business','owner'),('recognition','recognition')],runner.plan(['owner'],'none','run'))
    def test_executed_results_are_reaudited_and_mapped_to_contract(self):
        row=self.execute();self.assertEqual({'one':list(runner.KINDS)},row['coverage']);self.assertEqual(4,row['requests']);self.assertEqual('EXECUTED',row['origin'])
    def test_audit_is_never_labelled_new_execution_or_cleanup(self):
        self.report.update(execution_mode='AUDIT_EXISTING');self.report.pop('owned_databases_removed')
        row=self.execute('audit');self.assertEqual('AUDIT_EXISTING',row['origin']);self.assertFalse(row['owned_databases_removed'])
    def test_stale_failed_wrong_mode_or_missing_cleanup_is_rejected(self):
        original=copy.deepcopy(self.report)
        for change in (dict(source_sha256='old'),dict(passed=False),dict(sources_unchanged=False),dict(execution_mode='AUDIT_EXISTING'),dict(owned_databases_removed=False),dict(suites=[])):
            with self.subTest(change=change):
                for p in (self.root/'.cache').glob('recognition-http-*'):(p/'report.json').unlink();p.rmdir()
                self.report=copy.deepcopy(original);self.report.update(change)
                with self.assertRaises(ValueError):self.execute()
    def test_unknown_operation_or_ambiguous_reports_are_rejected(self):
        self.proof['coverage']={'GET /unknown':list(runner.KINDS)};self.report['suites']=[dict(name='fixture',**self.proof)]
        with self.assertRaises(ValueError):self.execute()
        for p in (self.root/'.cache').glob('recognition-http-*'):(p/'report.json').unlink();p.rmdir()
        with self.assertRaises(ValueError):self.execute(count=2)
if __name__=='__main__':unittest.main()
