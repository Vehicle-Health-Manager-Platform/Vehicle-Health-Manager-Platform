import contextlib
import copy
import io
import unittest
from unittest.mock import patch
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


if __name__=='__main__':unittest.main()
