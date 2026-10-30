import copy
import json
import unittest
from performance_linux_client import configuration


class LinuxClientConfigurationTest(unittest.TestCase):
    def config(self):
        return dict(host='autocare-perf-0123456789ab-backend',port=8080,concurrency=500,processes=10,tokens={'1':'SYNTHETIC'},mode='home',slot_base=1500)
    def test_reader_ids_are_restored_and_writer_distribution_stays_a_list(self):
        value=configuration(json.dumps(self.config()));self.assertEqual({1:'SYNTHETIC'},value['tokens']);self.assertEqual(500,value['concurrency'])
        value=self.config();value.update(mode='order',tokens=['SYNTHETIC']);self.assertEqual(['SYNTHETIC'],configuration(json.dumps(value))['tokens'])
    def test_public_or_foreign_targets_invalid_concurrency_and_extra_fields_are_refused(self):
        for change in [dict(host='example.org'),dict(host='127.0.0.1'),dict(host='autocare-perf-../../-backend'),dict(port=80),dict(mode='other'),dict(concurrency=True),dict(processes=0),dict(processes=3),dict(tokens={}),dict(tokens={'1':''}),dict(extra='secret'),dict(slot_base=1)]:
            value=self.config();value.update(change)
            with self.subTest(change=change),self.assertRaises(ValueError):configuration(json.dumps(value))


if __name__=='__main__':unittest.main()
