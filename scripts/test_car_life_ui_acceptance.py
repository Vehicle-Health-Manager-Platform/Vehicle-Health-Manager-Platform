import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import run_car_life_ui_acceptance as ui


class IsolatedUiFixtureTest(unittest.TestCase):
    def test_virtual_bridge_keeps_real_module_newline_and_never_changes_ordinary_config(self):
        with tempfile.TemporaryDirectory(dir=ui.ROOT/'.cache',prefix='ui-config-test-') as directory:
            folder=Path(directory);config=ui.bridge_config(folder)
            isolated=folder/'isolated.mjs';isolated.write_text('const original={plugins:[]};\n'+config.read_text(encoding='utf-8').split('\n',1)[1],encoding='utf-8')
            code='import assert from "node:assert/strict";const c=(await import('+json.dumps(isolated.as_uri())+')).default;const p=c.plugins[0];assert.ok(p.resolveId("virtual:synthetic-ui").startsWith("\\0"));const text=p.transform("const value=1;","/apps/miniapp/src/main.js");assert.ok(text.startsWith("import \'virtual:synthetic-ui\';\\n"));assert.ok(p.load(p.resolveId("virtual:synthetic-ui")).includes("非正式微信登录"));'
            result=subprocess.run(['node','--input-type=module','-e',code],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=20)
            self.assertEqual(0,result.returncode,result.stderr.decode('utf-8','replace')[:1000])
    def test_mismatched_source_or_jar_never_starts_a_fixture(self):
        with tempfile.TemporaryDirectory(dir=ui.ROOT/'.cache',prefix='ui-provenance-test-') as directory:
            root=Path(directory);pom=root/'backend/pom.xml';pom.parent.mkdir();pom.write_text('fixture source')
            jar=root/'fixture.jar';jar.write_bytes(b'fixture-jar');source=root/'report.json'
            proof={'passed':True,'implementation_sources_unchanged':True,'implementation_inputs_sha256':{'backend/pom.xml':hashlib.sha256(pom.read_bytes()).hexdigest()},'backend_jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
            source.write_text(json.dumps(proof))
            with patch.object(ui,'ROOT',root):
                self.assertEqual(1,len(ui.verify_artifact(jar,source)))
                pom.write_text('changed source')
                with self.assertRaises(ValueError):ui.verify_artifact(jar,source)
                pom.write_text('fixture source');jar.write_bytes(b'changed-jar')
                with self.assertRaises(ValueError):ui.verify_artifact(jar,source)


if __name__=='__main__':unittest.main()
