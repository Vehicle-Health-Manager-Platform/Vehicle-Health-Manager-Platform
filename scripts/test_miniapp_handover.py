import json
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from miniapp_handover import ROOT,OUTPUT,MANIFEST,GUIDES,build,validate_links


class HandoverTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT/'.cache');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        outputs=build();self.expected=outputs
        files=set(json.loads(outputs[MANIFEST])['inputs'])|{OUTPUT}
        for name in (*GUIDES.values(),'docs/delivery/REMAINING_ACCEPTANCE.md'):
            for target in re.findall(r'\]\(([^)]+)\)',(ROOT/name).read_text(encoding='utf-8')):
                if not target.startswith('http'):
                    files.add(((ROOT/name).parent/target).resolve().relative_to(ROOT).as_posix())
        for name in files:
            dest=self.root/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/name,dest)

    def pages(self,change):
        file=self.root/'apps/miniapp/src/pages.json';data=json.loads(file.read_text(encoding='utf-8'));change(data)
        file.write_text(json.dumps(data,ensure_ascii=False),encoding='utf-8')

    def test_tracked_outputs_and_line_endings_match(self):
        for name,content in self.expected.items():self.assertEqual(content,(ROOT/name).read_text(encoding='utf-8'))
        for file in self.root.rglob('*'):
            if file.is_file():file.write_bytes(file.read_bytes().replace(b'\r\n',b'\n').replace(b'\n',b'\r\n'))
        self.assertEqual(self.expected,build(self.root))
        self.assertFalse(json.loads(self.expected[MANIFEST])['runtime_acceptance_passed'])

    def test_duplicate_and_invalid_page_rejected(self):
        self.pages(lambda d:d['pages'].append(d['pages'][0]))
        with self.assertRaises(ValueError):build(self.root)

    def test_path_traversal_rejected_and_source_changes_update_inventory(self):
        file=self.root/'apps/miniapp/src/pages/index/index.vue'
        file.write_text(file.read_text(encoding='utf-8')+'\n<!-- synthetic change -->\n',encoding='utf-8')
        self.assertNotEqual(self.expected[MANIFEST],build(self.root)[MANIFEST])
        self.pages(lambda d:d['pages'].append({'path':'pages/../../outside','style':{'navigationBarTitleText':'invalid'}}))
        with self.assertRaises(ValueError):build(self.root)

    def test_missing_source_and_unregistered_tab_rejected(self):
        path=self.root/'apps/miniapp/src/pages/index/index.vue';original=path.read_bytes();path.unlink()
        with self.assertRaises(ValueError):build(self.root)
        path.write_bytes(original)
        self.pages(lambda d:d['tabBar']['list'].append({'pagePath':'pages/missing/missing'}))
        with self.assertRaises(ValueError):build(self.root)

    def test_duplicate_template_variable_rejected(self):
        file=self.root/'.env.example';file.write_text(file.read_text(encoding='utf-8')+'\nJWT_SECRET=placeholder\n',encoding='utf-8')
        with self.assertRaises(ValueError):build(self.root)

    def test_template_values_never_exported_and_change_is_detected(self):
        file=self.root/'.env.example';file.write_text(file.read_text(encoding='utf-8')+'\nEXAMPLE_SECRET=synthetic-sensitive-value\n',encoding='utf-8')
        outputs=build(self.root)
        self.assertNotEqual(self.expected[MANIFEST],outputs[MANIFEST])
        self.assertNotIn('synthetic-sensitive-value',''.join(outputs.values()))
        self.assertIn('EXAMPLE_SECRET',outputs[OUTPUT])

    def test_broken_link_or_guide_route_rejected(self):
        file=self.root/GUIDES['owner'];original=file.read_text(encoding='utf-8')
        file.write_text(original+'\n[missing](missing.md)\n',encoding='utf-8')
        with self.assertRaises(ValueError):build(self.root)
        file.write_text(original+'\npages/missing/missing\n',encoding='utf-8')
        with self.assertRaises(ValueError):build(self.root)

    def test_outside_link_rejected(self):
        with self.assertRaises(ValueError):validate_links(self.root,OUTPUT,'[outside](../../../../outside.md)')


if __name__=='__main__':unittest.main()
