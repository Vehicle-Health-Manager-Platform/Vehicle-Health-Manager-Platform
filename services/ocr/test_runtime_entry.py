import os
import unittest
from unittest.mock import patch
import runtime_entry


class RuntimeEntryTest(unittest.TestCase):
    def profile(self):
        return patch.multiple(runtime_entry.platform, system=lambda: 'Linux', machine=lambda: 'x86_64')

    def test_requires_explicit_enable_before_model_or_dependency_checks(self):
        with self.profile(), patch.dict(os.environ, {}, clear=True), patch.object(runtime_entry, 'verify_models') as models:
            with self.assertRaises(ValueError): runtime_entry.prepare()
            models.assert_not_called()

    def test_other_platforms_and_architectures_rejected(self):
        for system, machine in [('Windows','AMD64'),('Linux','aarch64')]:
            with patch.object(runtime_entry.platform,'system',return_value=system), patch.object(runtime_entry.platform,'machine',return_value=machine):
                with self.assertRaises(ValueError): runtime_entry.prepare()

    def test_versions_must_match_before_models_open(self):
        with self.profile(), patch.dict(os.environ, {'OCR_ENABLED':'true'}), patch.object(runtime_entry,'version',return_value='incorrect'), patch.object(runtime_entry,'verify_models') as models:
            with self.assertRaises(ValueError): runtime_entry.prepare()
            models.assert_not_called()

    def test_corrupt_or_missing_models_fail_before_policy_and_paths_are_published(self):
        versions={'paddleocr':'3.7.0','paddlepaddle':'3.3.1','Pillow':'12.3.0'}
        with self.profile(), patch.dict(os.environ, {'OCR_ENABLED':'true'}, clear=True), patch.object(runtime_entry,'version',side_effect=versions.get), \
             patch.object(runtime_entry,'verify_models',side_effect=ValueError('explicit asset mismatch')):
            with self.assertRaises(ValueError): runtime_entry.prepare()
            self.assertNotIn('OCR_WORKER_NETWORK_DISABLED',os.environ)
            self.assertNotIn('OCR_DETECTION_MODEL_DIR',os.environ)

    def test_verified_models_publish_fixed_paths_and_mandatory_policy(self):
        versions={'paddleocr':'3.7.0','paddlepaddle':'3.3.1','Pillow':'12.3.0'}
        models={'PP-OCRv5_mobile_det':'/models/verified-det','PP-OCRv5_mobile_rec':'/models/verified-rec'}
        with self.profile(), patch.dict(os.environ, {'OCR_ENABLED':'true'}, clear=True), patch.object(runtime_entry,'version',side_effect=versions.get), \
             patch.object(runtime_entry,'verify_models',return_value=models):
            runtime_entry.prepare()
            self.assertEqual('/models/verified-det',os.environ['OCR_DETECTION_MODEL_DIR'])
            self.assertEqual('true',os.environ['OCR_WORKER_NETWORK_DISABLED'])


if __name__ == '__main__': unittest.main()
