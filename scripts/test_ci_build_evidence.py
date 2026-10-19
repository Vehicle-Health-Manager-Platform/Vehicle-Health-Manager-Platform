"""Build provenance follows content and a fresh artifact, never file timestamps."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import run_api_acceptance as acceptance


class BuildEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='ci-build-evidence-',dir=acceptance.ROOT/'.cache')
        self.addCleanup(self.cleanup)
        self.root=Path(self.temp.name)
        self.source=self.root/'backend/src/main/Example.java';self.source.parent.mkdir(parents=True);self.source.write_text('original',encoding='utf-8')
        (self.root/'backend/pom.xml').write_text('synthetic pom',encoding='utf-8')
        self.jar=self.root/'backend/target/test.jar';self.jar.parent.mkdir()
        self.addCleanup(patch.stopall)
        patch.object(acceptance,'ROOT',self.root).start()
        patch.object(acceptance,'JAR',self.jar).start()
        patch.object(acceptance,'CI_INPUT',self.root/'.cache/proof.json').start()
        patch.dict(os.environ,{'CI':'true'}).start()

    def cleanup(self):
        path=Path(self.temp.name).resolve()
        assert path.is_relative_to((Path(__file__).resolve().parent.parent/'.cache').resolve()) and path.name.startswith('ci-build-evidence-')
        self.temp.cleanup()

    def test_touching_source_after_build_does_not_change_content_proof(self):
        acceptance.record_ci_sources();self.jar.write_bytes(b'synthetic fresh artifact')
        digest=acceptance.verify_ci_jar()
        os.utime(self.source,(2000000000,2000000000))
        self.assertEqual(digest,acceptance.verify_ci_jar())

    def test_changed_source_even_with_old_timestamp_rejected(self):
        acceptance.record_ci_sources();self.jar.write_bytes(b'synthetic fresh artifact')
        self.source.write_text('changed',encoding='utf-8');os.utime(self.source,(1,1))
        with self.assertRaisesRegex(ValueError,'content changed'):acceptance.verify_ci_jar()

    def test_preexisting_artifact_cannot_be_sealed_as_new_build(self):
        self.jar.write_bytes(b'synthetic old artifact')
        with self.assertRaisesRegex(ValueError,'before any jar'):acceptance.record_ci_sources()

    def test_existing_artifact_without_prebuild_proof_rejected(self):
        self.jar.write_bytes(b'synthetic artifact')
        with self.assertRaisesRegex(ValueError,'Missing pre-build'):acceptance.verify_ci_jar()

    def test_missing_artifact_rejected(self):
        acceptance.record_ci_sources()
        with self.assertRaises(ValueError):acceptance.verify_ci_jar()

    def test_local_reuse_cannot_claim_ci_provenance(self):
        os.environ['CI']='false'
        with self.assertRaises(ValueError):acceptance.record_ci_sources()
        with self.assertRaises(ValueError):acceptance.verify_ci_jar()


if __name__=='__main__':unittest.main()
