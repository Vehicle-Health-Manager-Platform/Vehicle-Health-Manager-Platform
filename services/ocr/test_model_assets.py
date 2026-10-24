import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from model_assets import prepare_models, verify_models
import runtime_check

class ModelAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'prepared'
        self.files = {'inference.yml': b'fixture', 'inference.json': b'{}', 'inference.pdiparams': b'test-only'}
        self.record = {'name': 'fixture', 'files': {name: {'bytes': len(value), 'sha256': hashlib.sha256(value).hexdigest()} for name, value in self.files.items()}}
        self.archive = self.root / 'fixture.tar'
        self.make_archive()
        self.lock = patch('model_assets.model_lock', return_value=[self.record]); self.lock.start()
        self.addCleanup(self.lock.stop)

    def make_archive(self, mutation=None):
        with tarfile.open(self.archive, 'w') as tar:
            directory = tarfile.TarInfo('fixture_infer'); directory.type = tarfile.DIRTYPE; tar.addfile(directory)
            for name, value in self.files.items():
                member = tarfile.TarInfo('fixture_infer/' + name); member.size = len(value)
                if mutation: mutation(member)
                tar.addfile(member, io.BytesIO(value))
        self.record.update(bytes=self.archive.stat().st_size, sha256=hashlib.sha256(self.archive.read_bytes()).hexdigest())

    def test_prepare_then_verify_exact_files(self):
        result = prepare_models(self.root, self.output)
        self.assertEqual(str(self.output / 'fixture_infer'), result['fixture'])
        self.assertEqual(result, verify_models(self.output))

    def test_corrupt_archive_fails_before_publishing_output(self):
        data = bytearray(self.archive.read_bytes()); data[100] ^= 1; self.archive.write_bytes(data)
        with self.assertRaises(ValueError): prepare_models(self.root, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.root.glob('ocr-assets-*')))

    def test_rejects_traversal_link_and_duplicate_members(self):
        for mutation in [lambda m: setattr(m, 'name', '../outside'),
                         lambda m: setattr(m, 'type', tarfile.SYMTYPE),
                         lambda m: setattr(m, 'name', 'fixture_infer/inference.yml')]:
            with self.subTest(mutation=mutation):
                self.make_archive(mutation)
                with self.assertRaises(ValueError): prepare_models(self.root, self.output)
                self.assertFalse(self.output.exists())
                self.assertFalse((self.root.parent / 'outside').exists())

    def test_bad_member_digest_does_not_publish_partial_models(self):
        self.record['files']['inference.json']['sha256'] = '0' * 64
        with self.assertRaises(ValueError): prepare_models(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_existing_output_never_overwritten(self):
        self.output.mkdir(); marker = self.output / 'keep'; marker.write_text('keep')
        with self.assertRaises(ValueError): prepare_models(self.root, self.output)
        self.assertEqual('keep', marker.read_text())

    def test_reverify_rejects_tampered_missing_and_extra_files(self):
        prepare_models(self.root, self.output)
        directory = self.output / 'fixture_infer'
        path = directory / 'inference.yml'; path.write_bytes(b'changed')
        with self.assertRaises(ValueError): verify_models(self.output)
        path.write_bytes(self.files[path.name]); path.unlink()
        with self.assertRaises(ValueError): verify_models(self.output)
        path.write_bytes(self.files[path.name]); (directory / 'unexpected').touch()
        with self.assertRaises(ValueError): verify_models(self.output)

    def test_missing_assets_fail_without_network(self):
        with self.assertRaises(ValueError): verify_models(self.output)
        self.archive.unlink()
        with self.assertRaises(ValueError): prepare_models(self.root, self.output)

    def test_runtime_version_mismatch_stops_before_loading_models(self):
        with patch.object(runtime_check, 'version', return_value='0'), patch.object(runtime_check, 'verify_models') as verify:
            with self.assertRaises(ValueError): runtime_check.run(self.output)
            verify.assert_not_called()

    def test_correct_versions_do_not_bypass_missing_model_validation(self):
        with patch.object(runtime_check, 'version', side_effect=lambda name: runtime_check.VERSIONS[name]):
            with self.assertRaises(ValueError): runtime_check.run(self.output)

if __name__ == '__main__': unittest.main()
