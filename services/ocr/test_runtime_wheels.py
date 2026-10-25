import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import prepare_runtime_wheels as wheels


class RuntimeWheelsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.data=b'explicit synthetic wheel bytes'
        self.row={'filename':'synthetic-1.0-py3-none-any.whl','url':'https://files.pythonhosted.org/packages/test/synthetic-1.0-py3-none-any.whl',
            'bytes':len(self.data),'sha256':hashlib.sha256(self.data).hexdigest()}
        self.lock=self.root/'lock.json';self.lock.write_text(json.dumps({'wheels':[self.row]}))
        self.patch=patch.object(wheels,'LOCK',self.lock);self.patch.start();self.addCleanup(self.patch.stop)

    def directory(self):
        result=self.root/'ready';result.mkdir();(result/self.row['filename']).write_bytes(self.data);return result

    def test_locked_directory_verifies_without_network(self):
        root=self.directory()
        with patch.object(wheels,'urlopen') as request:self.assertEqual(1,wheels.prepare(root));request.assert_not_called()

    def test_wrong_sources_names_bounds_and_hashes_rejected(self):
        for key,value in [('url','http://files.pythonhosted.org/packages/test/'+self.row['filename']),
            ('url','https://files.pythonhosted.org.evil/packages/test/'+self.row['filename']),
            ('filename','../escape.whl'),('bytes',True),('bytes',300*1024*1024),('sha256','incorrect')]:
            row={**self.row,key:value};self.lock.write_text(json.dumps({'wheels':[row]}))
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):wheels.records()

    def test_corrupt_truncated_and_extra_files_fail(self):
        root=self.directory();target=root/self.row['filename']
        for value in [self.data[:-1],b'x'*len(self.data)]:
            target.write_bytes(value)
            with self.assertRaises(ValueError):wheels.verify_wheels(root)
        target.write_bytes(self.data);(root/'unexpected').write_bytes(b'')
        with self.assertRaises(ValueError):wheels.verify_wheels(root)

    def test_invalid_existing_output_is_preserved(self):
        root=self.directory();target=root/self.row['filename'];target.write_bytes(b'original-invalid')
        with self.assertRaises(ValueError):wheels.prepare(root)
        self.assertEqual(b'original-invalid',target.read_bytes())

    def response(self,status,raw):
        response=MagicMock();response.status=status
        response.headers={'Content-Range':f"bytes 0-{self.row['bytes']-1}/{self.row['bytes']}"}
        response.read=io.BytesIO(raw).read;response.__enter__.return_value=response
        return response

    def test_exact_byte_range_is_required(self):
        destination=self.root/'part';opener=MagicMock(return_value=self.response(206,self.data))
        wheels.download_part(self.row,0,len(self.data)-1,destination,opener)
        self.assertEqual(self.data,destination.read_bytes())
        self.assertEqual('bytes=0-'+str(len(self.data)-1),opener.call_args.args[0].get_header('Range'))

    def test_full_response_or_truncated_range_is_not_published(self):
        for status,raw in [(200,self.data),(206,self.data[:-1])]:
            destination=self.root/'rejected';opener=MagicMock(side_effect=lambda *a,**k:self.response(status,raw))
            with self.assertRaises(ValueError):wheels.download_part(self.row,0,len(self.data)-1,destination,opener)
            self.assertEqual(3,opener.call_count);self.assertFalse(destination.exists())

    def test_failed_hash_does_not_publish_partial_output(self):
        def bad(row,start,end,destination,opener):destination.write_bytes(b'x'*(end-start+1))
        output=self.root/'never-published'
        with patch.object(wheels,'download_part',side_effect=bad):
            with self.assertRaises(ValueError):wheels.prepare(output)
        self.assertFalse(output.exists())


if __name__=='__main__':unittest.main()
