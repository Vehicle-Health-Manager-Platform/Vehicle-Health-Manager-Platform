import contextlib
import copy
import io
import json
import subprocess
import unittest
from unittest.mock import Mock, patch
import run_linux_ocr_acceptance as runner


class LinuxOcrProofTest(unittest.TestCase):
    def test_worker_classifier_excludes_its_own_diagnostic_command(self):
        self.assertTrue(runner.worker_command([b'python',b'-u',b'/app/worker.py',b'--child',b'--detection-dir']))
        diagnostic=[b'python',b'-c',b"probe searches /app/worker.py and --child"]
        self.assertIn(b'/app/worker.py',b' '.join(diagnostic))
        self.assertFalse(runner.worker_command(diagnostic))
        self.assertFalse(runner.worker_command([b'python',b'-u',b'/other/worker.py',b'--child']))
        self.assertFalse(runner.worker_command([b'python',b'-u',b'/app/worker.py']))

    def test_inspection_cannot_confuse_a_same_named_network_with_container(self):
        self.assertEqual(['container','inspect','same-owned-name'],runner.inspection_arguments('same-owned-name'))
        self.assertEqual(['network','inspect','same-owned-name'],runner.inspection_arguments('same-owned-name',True))

    def fixture(self):
        return {'HostConfig':{'Memory':1536*1024*1024,'MemorySwap':1536*1024*1024,'ShmSize':16*1024*1024,'NanoCpus':2000000000,'PidsLimit':128,'ReadonlyRootfs':True,
            'CapDrop':['ALL'],'CapAdd':None,'Privileged':False,'SecurityOpt':['no-new-privileges:true'],'PortBindings':{}},
            'Config':{'User':'10001:10001'},'NetworkSettings':{'Networks':{'owned-private':{}}},
            'Mounts':[{'Destination':'/models','RW':False}]}

    def test_complete_inspected_profile_is_accepted(self):
        proof=runner.resource_proof(self.fixture(),{'Internal':True,'EnableIPv6':False},'owned-private')
        self.assertTrue(proof['internal_network_only']);self.assertFalse(proof['host_port_published'])

    def test_each_resource_limit_and_type_is_required(self):
        for key,value in [('Memory',0),('MemorySwap',-1),('ShmSize',0),('NanoCpus',0),('PidsLimit',0),('ReadonlyRootfs',False),('ReadonlyRootfs',1)]:
            item=self.fixture();item['HostConfig'][key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):
                runner.resource_proof(item,{'Internal':True,'EnableIPv6':False},'owned-private')

    def test_privilege_network_port_and_model_bypasses_are_rejected(self):
        for kind in ['root','capabilities','privilege','privileged','cap_add','extra_network','external','ipv6','port','models']:
            item=self.fixture();network={'Internal':True,'EnableIPv6':False}
            if kind=='root':item['Config']['User']='0:0'
            elif kind=='capabilities':item['HostConfig']['CapDrop']=[]
            elif kind=='privilege':item['HostConfig']['SecurityOpt']=[]
            elif kind=='privileged':item['HostConfig']['Privileged']=True
            elif kind=='cap_add':item['HostConfig']['CapAdd']=['SYS_ADMIN']
            elif kind=='extra_network':item['NetworkSettings']['Networks']['outside']={}
            elif kind=='external':network['Internal']=False
            elif kind=='ipv6':network['EnableIPv6']=True
            elif kind=='port':item['HostConfig']['PortBindings']={'8090/tcp':[{'HostPort':'8090'}]}
            else:item['Mounts'][0]['RW']=True
            with self.subTest(kind=kind),self.assertRaises(ValueError):runner.resource_proof(item,network,'owned-private')

    def test_cleanup_requires_exact_run_label(self):
        container={'Config':{'Labels':{runner.OWNER:'owned'}}};network={'Labels':{runner.OWNER:'owned'}}
        self.assertTrue(runner.owned(container,'owned'));self.assertTrue(runner.owned(network,'owned',True))
        self.assertFalse(runner.owned(container,'other'));self.assertFalse(runner.owned({},'owned'))

    def test_missing_explicit_flag_stops_before_models_and_run_directory(self):
        with patch('sys.argv',['runner']),patch.object(runner.uuid,'uuid4') as create,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):runner.main()
            create.assert_not_called()


class DockerInspectionTest(unittest.TestCase):
    def result(self, returncode=0, stdout='', stderr=''):
        return subprocess.CompletedProcess(['docker', 'container', 'inspect', 'owned'], returncode, stdout, stderr)

    def test_success_requires_one_dictionary(self):
        item={'Config':{'Labels':{runner.OWNER:'run'}}}
        self.assertEqual(item,runner.inspected_object(self.result(stdout=json.dumps([item])),'owned'))

    def test_malformed_success_is_not_a_missing_resource(self):
        for stdout in ['', 'not-json', 'null', '{}', '[]', '[{},{}]', '[null]', '["owned"]']:
            with self.subTest(stdout=stdout),self.assertRaises((RuntimeError,ValueError)):
                runner.inspected_object(self.result(stdout=stdout),'owned')

    def test_only_explicit_missing_resource_errors_are_accepted(self):
        for network in [False,True]:
            kind='network' if network else 'container'
            errors=[f'Error response from daemon: No such {kind}: owned',f'Error: No such {kind}: owned']
            if network:errors.append('Error response from daemon: network owned not found')
            for stderr in errors:
                with self.subTest(network=network,stderr=stderr):
                    self.assertIsNone(runner.inspected_object(self.result(1,stderr=stderr+'\n'),'owned',network))

    def test_daemon_and_permission_failures_are_not_missing_resources(self):
        for stderr in ['Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?',
                'permission denied while trying to connect to the Docker daemon socket',
                'Error response from daemon: authorization denied']:
            for network in [False,True]:
                with self.subTest(stderr=stderr,network=network),self.assertRaises(RuntimeError):
                    runner.inspected_object(self.result(1,stderr=stderr),'owned',network)

    def test_other_names_resource_types_and_mixed_errors_are_rejected(self):
        for network in [False,True]:
            kind='network' if network else 'container'
            other='container' if network else 'network'
            errors=[f'Error response from daemon: No such {kind}: someone-else',
                f'Error response from daemon: No such {other}: owned',
                f'Error response from daemon: No such {kind}: owned-extra',
                f'Error response from daemon: No such {kind}: owned\npermission denied',
                f'Cannot connect to daemon\nError response from daemon: No such {kind}: owned',
                'Error response from daemon: network someone-else not found']
            if not network:errors.append('Error response from daemon: network owned not found')
            for stderr in errors:
                with self.subTest(network=network,stderr=stderr),self.assertRaises(RuntimeError):
                    runner.inspected_object(self.result(1,stderr=stderr),'owned',network)


class OwnedCleanupTest(unittest.TestCase):
    def item(self, network=False, run='run'):
        labels={'Labels':{runner.OWNER:run}}
        return labels if network else {'Config':labels}

    def test_all_explicitly_missing_resources_need_no_removal(self):
        inspect=Mock(return_value=None);remove=Mock()
        self.assertTrue(runner.cleanup_owned('run',['container'],['network'],inspect,remove))
        remove.assert_not_called()

    def test_owned_containers_and_networks_are_removed_and_rechecked(self):
        state={(False,'same-name'):self.item(),(True,'same-name'):self.item(True)};removed=[]
        def inspect(name,network=False):return state.get((network,name))
        def remove(name,network=False):removed.append((network,name));state.pop((network,name))
        self.assertTrue(runner.cleanup_owned('run',['same-name'],['same-name'],inspect,remove))
        self.assertEqual([(False,'same-name'),(True,'same-name')],removed)
        self.assertEqual({},state)

    def test_foreign_resources_are_preserved_and_other_resources_still_cleaned(self):
        state={(False,'foreign'):self.item(run='someone-else'),(True,'owned'):self.item(True)};removed=[]
        def inspect(name,network=False):return state.get((network,name))
        def remove(name,network=False):removed.append((network,name));state.pop((network,name))
        self.assertFalse(runner.cleanup_owned('run',['foreign'],['owned'],inspect,remove))
        self.assertEqual([(True,'owned')],removed)
        self.assertIn((False,'foreign'),state)

    def test_inspection_failure_cannot_pass_and_does_not_stop_other_cleanup(self):
        state={(True,'owned'):self.item(True)};removed=[]
        def inspect(name,network=False):
            if name=='unavailable':raise RuntimeError('Cannot connect to the Docker daemon')
            return state.get((network,name))
        def remove(name,network=False):removed.append((network,name));state.pop((network,name))
        self.assertFalse(runner.cleanup_owned('run',['unavailable'],['owned'],inspect,remove))
        self.assertEqual([(True,'owned')],removed)

    def test_removal_failure_cannot_pass_and_does_not_stop_other_cleanup(self):
        state={(False,'unavailable'):self.item(),(True,'owned'):self.item(True)};removed=[]
        def inspect(name,network=False):return state.get((network,name))
        def remove(name,network=False):
            if name=='unavailable':raise RuntimeError('permission denied')
            removed.append((network,name));state.pop((network,name))
        self.assertFalse(runner.cleanup_owned('run',['unavailable'],['owned'],inspect,remove))
        self.assertEqual([(True,'owned')],removed)
        self.assertIn((False,'unavailable'),state)

    def test_surviving_or_replaced_resource_cannot_pass(self):
        for surviving in [self.item(),self.item(run='someone-else')]:
            state={(False,'surviving'):self.item(),(True,'owned'):self.item(True)};removed=[]
            def inspect(name,network=False):return state.get((network,name))
            def remove(name,network=False):
                removed.append((network,name))
                if name=='surviving':state[(network,name)]=surviving
                else:state.pop((network,name))
            with self.subTest(surviving=surviving):
                self.assertFalse(runner.cleanup_owned('run',['surviving'],['owned'],inspect,remove))
                self.assertEqual([(False,'surviving'),(True,'owned')],removed)


if __name__=='__main__':unittest.main()
