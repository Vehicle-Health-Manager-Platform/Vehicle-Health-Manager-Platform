import contextlib
import copy
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_real_asr_journey as runner


class RealAsrRunnerTest(unittest.TestCase):
    def fixture(self):
        return {'HostConfig':{'Memory':2*1024**3,'MemorySwap':2*1024**3,'NanoCpus':2000000000,'PidsLimit':128,
            'ShmSize':16*1024**2,'ReadonlyRootfs':True,'NetworkMode':'owned-private','Privileged':False,
            'CapAdd':None,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],'PortBindings':{},
            'Tmpfs':{'/tmp':'rw,noexec,nosuid,size=256m,mode=1777'}},
            'Config':{'User':'10001:10001'},'NetworkSettings':{'Networks':{'owned-private':{}}},
            'Mounts':[{'Type':'bind','Destination':name,'RW':False}
                for name in ('/models','/workspace/services/asr','/fixtures/synthetic-english.wav')]}

    def test_actual_network_proof_does_not_claim_network_none_or_host_ports(self):
        item=self.fixture();proof=runner.network_proof(item,{'Internal':True,'EnableIPv6':False},'owned-private')
        self.assertEqual(proof['NetworkMode'],'owned-private');self.assertFalse(proof['host_ports'])
        self.assertEqual(item['HostConfig']['NetworkMode'],'owned-private')

    def test_model_port_or_extra_network_and_privilege_bypasses_rejected(self):
        for kind in ('port','extra_network','external','ipv6','writable','root','unbounded'):
            item=self.fixture();network={'Internal':True,'EnableIPv6':False}
            if kind=='port':item['HostConfig']['PortBindings']['8091/tcp']=[{'HostIp':'127.0.0.1','HostPort':'0'}]
            elif kind=='extra_network':item['NetworkSettings']['Networks']['outside']={}
            elif kind=='external':network['Internal']=False
            elif kind=='ipv6':network['EnableIPv6']=True
            elif kind=='writable':item['Mounts'][0]['RW']=True
            elif kind=='root':item['Config']['User']='0'
            else:item['HostConfig']['Memory']=0
            with self.subTest(kind=kind),self.assertRaises(ValueError):runner.network_proof(item,network,'owned-private')

    def test_relay_requires_loopback_port_and_exact_networks(self):
        item=self.fixture();item['HostConfig'].update(Memory=64*1024**2,MemorySwap=64*1024**2,PidsLimit=32,NanoCpus=500000000,PortBindings={'8092/tcp':[{'HostIp':'127.0.0.1','HostPort':'0'}]})
        item['NetworkSettings']['Networks']['outside']={}
        self.assertTrue(runner.relay_proof(item,'owned-private','outside')['host_port_loopback_only'])
        changed=copy.deepcopy(item);changed['HostConfig']['PortBindings']['8092/tcp'][0]['HostIp']='0.0.0.0'
        with self.assertRaises(ValueError):runner.relay_proof(changed,'owned-private','outside')
        changed=copy.deepcopy(item);changed['NetworkSettings']['Networks']['unexpected']={}
        with self.assertRaises(ValueError):runner.relay_proof(changed,'owned-private','outside')

    def test_relay_config_rejects_nonprivate_or_injected_destinations(self):
        self.assertIn('proxy_next_upstream off',runner.relay_configuration('172.20.0.2'))
        for address in ('8.8.8.8','172.20.0.2; return 200;','http://private'):
            with self.subTest(address=address),self.assertRaises(ValueError):runner.relay_configuration(address)

    def test_missing_opt_in_stops_before_resource_access(self):
        with patch.object(runner.uuid,'uuid4') as uuid,patch.object(runner.subprocess,'run') as run,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):runner.main(['--models-root','not-read','--wheelhouse','not-read','--synthetic-english-wav','not-read'])
            uuid.assert_not_called();run.assert_not_called()

    def test_maven_evidence_rejects_skips_errors_or_missing_reports(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);path=root/'TEST-com.autocare.platform.order.RealAsrModelJourneyTest.xml'
            path.write_text('<testsuite tests="7" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(runner.suites(root,['RealAsrModelJourneyTest'])[0]['tests'],7)
            for attr in ('failures','errors','skipped'):
                path.write_text(f'<testsuite tests="7" failures="{int(attr=="failures")}" errors="{int(attr=="errors")}" skipped="{int(attr=="skipped")}"/>')
                with self.subTest(attr=attr),self.assertRaises(ValueError):runner.suites(root,['RealAsrModelJourneyTest'])
            with self.assertRaises(FileNotFoundError):runner.suites(root,['MissingSuite'])


if __name__=='__main__':unittest.main()
