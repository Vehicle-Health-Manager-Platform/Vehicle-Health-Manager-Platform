import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import json
import xml.etree.ElementTree as ET
import run_document_core_acceptance as runner


class DocumentRunnerTest(unittest.TestCase):
    def test_external_model_opt_in_is_not_a_declared_coverage_suite(self):
        ns={'m':'http://maven.apache.org/POM/4.0.0'}
        pom=ET.parse(runner.ROOT/'backend/pom.xml').getroot()
        profile=next(p for p in pom.findall('m:profiles/m:profile',ns) if p.findtext('m:id',namespaces=ns)=='core-coverage')
        excludes=[p.text for p in profile.findall('.//m:excludes/m:exclude',ns)]
        self.assertEqual(excludes,['**/RealAsrModelJourneyTest.java'])
        scope=json.loads((runner.ROOT/'scripts/core_coverage_scope.json').read_text())
        self.assertNotIn('RealAsrModelJourneyTest',scope['local_tests'])

    def fixture(self):
        return {'HostConfig':{'Memory':2*1024**3,'MemorySwap':2*1024**3,'PidsLimit':128,'NanoCpus':2000000000,
            'ReadonlyRootfs':True,'NetworkMode':'none','PortBindings':{},'Privileged':False,'CapAdd':None,
            'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges:true'],'Tmpfs':{'/tmp':'rw,noexec,nosuid,size=256m,mode=1777'}},
            'Config':{'User':'10001:10001'},'Mounts':[{'Type':'bind','Destination':name,'RW':False}
                for name in ('/models','/fixtures','/document/tests')]}
    def test_exact_document_profile_and_readonly_binds_are_required(self):
        self.assertFalse(runner.resource_proof(self.fixture())['host_ports'])
        for kind in ('network','memory','root','writable','extra','ports'):
            item=self.fixture()
            if kind=='network':item['HostConfig']['NetworkMode']='bridge'
            elif kind=='memory':item['HostConfig']['Memory']=0
            elif kind=='root':item['Config']['User']='0'
            elif kind=='writable':item['Mounts'][0]['RW']=True
            elif kind=='extra':item['Mounts'].append({'Type':'bind','Destination':'/private','RW':False})
            else:item['HostConfig']['PortBindings']={'8090/tcp':[]}
            with self.subTest(kind=kind),self.assertRaises(ValueError):runner.resource_proof(item)

    def test_missing_opt_in_cannot_start_resource_preparation_or_container(self):
        with patch.object(runner.uuid,'uuid4') as uuid,patch.object(runner.subprocess,'run') as execute,contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):runner.main([])
            uuid.assert_not_called();execute.assert_not_called()

    def archive(self,root,missing=False,duplicate=False):
        path=root/'synthetic.jar'
        with zipfile.ZipFile(path,'w') as jar:
            for name in ('DocumentPdfMain','DocumentPdfPlan'):
                jar.writestr('BOOT-INF/classes/com/autocare/platform/recognition/'+name+'.class',b'not real bytecode; inventory test')
            for name in ('InputMaterialValidator','FileValidator','UploadHttpException','UploadException'):
                jar.writestr('BOOT-INF/classes/com/autocare/platform/file/'+name+'.class',b'synthetic bytecode placeholder')
            for name in ('pdfbox-3.0.5','pdfbox-io-3.0.5','fontbox-3.0.5','commons-logging-1.0','jackson-core-2.0','jackson-databind-2.0','jackson-annotations-2.0'):
                if missing and name.startswith('fontbox-'):continue
                jar.writestr('BOOT-INF/lib/'+name+'.jar',b'synthetic library placeholder')
            jar.writestr('BOOT-INF/classes/../../outside',b'never extracted')
            jar.writestr('BOOT-INF/classes/application.private.yml',b'never extracted')
            if duplicate:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore');jar.writestr('BOOT-INF/lib/pdfbox-3.0.5.jar',b'duplicate')
        return path

    def test_only_allowlisted_classes_and_libraries_are_copied(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);rows=runner.prepare_java(self.archive(root),root/'java')
            self.assertEqual(len(rows),13);self.assertFalse((root/'outside').exists())
            self.assertFalse((root/'java/classes/application.private.yml').exists())
            self.assertTrue(all(len(row['sha256'])==64 for row in rows))

    def test_missing_or_duplicate_java_artifacts_fail_before_runtime(self):
        for missing,duplicate in ((True,False),(False,True)):
            with tempfile.TemporaryDirectory() as folder:
                root=Path(folder)
                with self.assertRaises(ValueError):runner.prepare_java(self.archive(root,missing,duplicate),root/'java')


if __name__=='__main__':unittest.main()
