import copy
import contextlib
import io
import unittest
from unittest.mock import patch

import run_linux_embedding_acceptance as runner


class LinuxEmbeddingProofTest(unittest.TestCase):
    def fixture(self):
        return {'HostConfig': {'Memory':2*1024**3,'MemorySwap':2*1024**3,'NanoCpus':2000000000,
            'PidsLimit':128,'ShmSize':16*1024**2,'ReadonlyRootfs':True,'NetworkMode':'none',
            'Privileged':False,'CapAdd':None,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],
            'PortBindings':{},'Tmpfs':{'/tmp':'rw,noexec,nosuid,size=256m,mode=1777'}},
            'Config':{'User':'10001:10001'},'Mounts':[{'Type':'bind','Destination':name,'RW':False}
                for name in ('/models','/workspace/services/embedding')]}

    def test_complete_runtime_profile_accepted(self):
        self.assertTrue(runner.resource_proof(self.fixture())['resource_binds_readonly'])

    def test_every_resource_limit_and_network_change_rejected(self):
        for key in ('Memory','MemorySwap','NanoCpus','PidsLimit','ShmSize','ReadonlyRootfs','NetworkMode'):
            item=self.fixture();item['HostConfig'][key]=0
            with self.subTest(key=key),self.assertRaises(ValueError):runner.resource_proof(item)

    def test_privilege_port_and_mount_bypass_rejected(self):
        for kind in ('root','privileged','caps','addcaps','security','ports','writable','extra','tmpfs','tmpfs_unbounded'):
            item=self.fixture();host=item['HostConfig']
            if kind=='root':item['Config']['User']='0:0'
            elif kind=='privileged':host['Privileged']=True
            elif kind=='caps':host['CapDrop']=[]
            elif kind=='addcaps':host['CapAdd']=['SYS_ADMIN']
            elif kind=='security':host['SecurityOpt']=[]
            elif kind=='ports':host['PortBindings']={'8091/tcp':[{'HostPort':'8091'}]}
            elif kind=='writable':item['Mounts'][0]['RW']=True
            elif kind=='extra':item['Mounts'].append({'Type':'bind','Destination':'/host','RW':False})
            elif kind=='tmpfs':host['Tmpfs']['/another']='rw'
            else:host['Tmpfs']['/tmp']='rw'
            with self.subTest(kind=kind),self.assertRaises(ValueError):runner.resource_proof(item)

    def test_cleanup_ownership_requires_exact_run_label(self):
        item={'Config':{'Labels':{runner.OWNER:'run-a'}}}
        self.assertTrue(runner.owned(item,'run-a'))
        self.assertFalse(runner.owned(item,'run-b'))
        self.assertFalse(runner.owned({},'run-a'))

    def test_missing_explicit_flag_fails_before_resource_or_docker_access(self):
        with patch.object(runner.subprocess,'run') as docker,patch.object(runner.uuid,'uuid4') as uuid,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):runner.main(['--models-root','not-read','--wheelhouse','not-read'])
            docker.assert_not_called();uuid.assert_not_called()


if __name__=='__main__':unittest.main()
