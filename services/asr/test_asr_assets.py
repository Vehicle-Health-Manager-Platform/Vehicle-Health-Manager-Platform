import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_assets as assets


class AssetTest(unittest.TestCase):
    def row(self, raw=b'model'):
        return {'filename': 'model.bin', 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(),
            'url': 'https://huggingface.co/Systran/faster-whisper-small/resolve/' + 'a'*40 + '/model.bin'}

    def test_committed_locks_have_exact_official_sources_and_complete_requirements(self):
        wheels = assets.records('wheels')
        model = assets.records('model')
        self.assertGreater(len(wheels), 7)
        self.assertTrue({'model.bin', 'config.json', 'tokenizer.json', 'vocabulary.txt'} <= {r['filename'] for r in model})
        actual = ''.join(line+'\n' for line in (assets.HERE/'requirements-runtime-linux.lock.txt').read_text().splitlines()
            if line and not line.startswith('#'))
        self.assertEqual(assets.wheel_lock(wheels), actual)
        self.assertIn('faster-whisper==1.2.1 ', actual)

    def test_duplicate_json_declarations_rejected(self):
        with self.assertRaises(ValueError):
            assets.unique([('files', []), ('files', [])])

    def test_model_inventory_tampering_and_extra_file_rejected(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(assets, 'records', return_value=[self.row()]):
            root = Path(folder)
            (root/'model.bin').write_bytes(b'model')
            self.assertEqual(assets.verify(root, 'model'), 1)
            (root/'model.bin').write_bytes(b'MODEL')
            with self.assertRaises(ValueError): assets.verify(root, 'model')
            (root/'model.bin').write_bytes(b'model')
            (root/'unexpected').write_bytes(b'x')
            with self.assertRaises(ValueError): assets.verify(root, 'model')

    def test_existing_invalid_assets_are_never_replaced_or_downloaded(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(assets, 'download') as download, \
                patch.object(assets, 'records', return_value=[self.row()]):
            with self.assertRaises(ValueError): assets.prepare(Path(folder), 'model')
            download.assert_not_called()
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_partial_download_is_not_published(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(assets, 'records', return_value=[self.row()]), \
                patch.object(assets, 'download', side_effect=TimeoutError):
            root = Path(folder)/'models'
            with self.assertRaises(TimeoutError): assets.prepare(root, 'model')
            self.assertFalse(root.exists())
            self.assertEqual(list(Path(folder).iterdir()), [])

    def test_successful_staged_resource_is_verified_before_publish(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(assets, 'records', return_value=[self.row()]), \
                patch.object(assets, 'download', side_effect=lambda row,path: path.write_bytes(b'model')):
            root = Path(folder)/'models'
            self.assertEqual(assets.prepare(root, 'model'), 1)
            self.assertEqual((root/'model.bin').read_bytes(), b'model')

    def test_download_size_hash_and_transport_fail_closed(self):
        class Response(io.BytesIO):
            status = 200
            def geturl(self): return 'https://cdn-lfs.hf.co/model.bin'
        with tempfile.TemporaryDirectory() as folder:
            for index, value in enumerate((b'mode', b'MODEL', b'model-extra')):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    assets.download(self.row(), Path(folder)/str(index), lambda *a,**kw: Response(value))
            with patch.object(Response, 'geturl', return_value='http://insecure/model.bin'), self.assertRaises(ValueError):
                assets.download(self.row(), Path(folder)/'insecure', lambda *a,**kw: Response(b'model'))
            assets.download(self.row(), Path(folder)/'valid', lambda *a,**kw: Response(b'model'))
            self.assertEqual((Path(folder)/'valid').read_bytes(), b'model')

    def test_wheel_installation_lock_must_match_declared_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)/'wheels';root.mkdir()
            row = {**self.row(b'wheel'), 'filename': 'package.whl', 'name': 'package', 'version': '1.0'}
            (root/'package.whl').write_bytes(b'wheel')
            (root/'requirements.lock').write_text(assets.wheel_lock([row]))
            (Path(folder)/'requirements-runtime-linux.lock.txt').write_text(assets.wheel_lock([row]))
            with patch.object(assets, 'records', return_value=[row]), patch.object(assets, 'HERE', Path(folder)):
                self.assertEqual(assets.verify(root, 'wheels'), 1)
                (root/'requirements.lock').write_text('package==2.0\n')
                with self.assertRaises(ValueError): assets.verify(root, 'wheels')

    def test_range_responses_must_match_offsets_and_retry_is_bounded(self):
        class Response(io.BytesIO):
            status=206
            headers={'Content-Range':'bytes 1-3/5'}
            def geturl(self):return 'https://cdn-lfs.hf.co/model.bin'
        with tempfile.TemporaryDirectory() as folder:
            calls=[]
            def opener(request,**kwargs):
                calls.append(request)
                self.assertEqual('bytes=1-3',request.get_header('Range'))
                return Response(b'ode')
            path=Path(folder)/'part'
            assets.download_part(self.row(),1,3,path,opener)
            self.assertEqual(path.read_bytes(),b'ode')
            self.assertEqual(len(calls),1)
            with patch.object(Response,'headers',{'Content-Range':'bytes 0-2/5'}),self.assertRaises(ValueError):
                assets.download_part(self.row(),1,3,Path(folder)/'invalid',opener)
            self.assertEqual(len(calls),4)
            self.assertFalse((Path(folder)/'invalid').exists())


if __name__ == '__main__':
    unittest.main()
