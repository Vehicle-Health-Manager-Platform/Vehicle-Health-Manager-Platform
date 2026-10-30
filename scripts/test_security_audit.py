import json
from pathlib import Path
import tempfile
import unittest
from run_security_audit import python_bom


class RuntimeInventoryTest(unittest.TestCase):
    def test_three_locked_profiles_remain_attributable_after_shared_package_merge(self):
        bom=python_bom();refs=[c['bom-ref'] for c in bom['components']]
        self.assertEqual(len(refs),len(set(refs)));self.assertGreater(len(refs),70)
        self.assertEqual({'ocr','asr','embedding'},{p['value'] for c in bom['components'] for p in c['properties']})
        pillow=next(c for c in bom['components'] if c['name']=='pillow')
        self.assertEqual('13.0.0',pillow['version']);self.assertTrue(all(h['alg']=='SHA-256' for h in pillow['hashes']))
    def test_malformed_component_hash_never_produces_a_trusted_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for profile in ('ocr','asr','embedding'):
                path=root/f'services/{profile}/runtime-wheels-linux.json';path.parent.mkdir(parents=True)
                path.write_text(json.dumps({'files':[{'name':'fixture','version':'1.0','sha256':'invalid'}]}))
            with self.assertRaises(ValueError):python_bom(root)


if __name__=='__main__':unittest.main()
