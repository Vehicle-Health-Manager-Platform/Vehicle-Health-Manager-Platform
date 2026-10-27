from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parent/'src'))
import linux_fault_acceptance as fault


class FaultProofTest(unittest.TestCase):
    def test_proc_identity_parses_parentheses_without_shifting_starttime(self):
        stat='123 (python (server)) S '+' '.join(str(i) for i in range(50))
        with patch.object(Path,'read_text',return_value=stat),patch.object(Path,'read_bytes',return_value=b'python\0-u\0/app/asr_process.py\0'):
            item=fault.identity(123)
        self.assertEqual(item,{'pid':123,'start':'18','arguments':['python','-u','/app/asr_process.py']})

    def test_only_exact_owned_real_model_command_can_be_fault_target(self):
        item={'pid':123,'start':'fixed','arguments':['python','-u','/app/asr_process.py','--child','/models','/tmp/asr-manifest.json']}
        with patch.object(fault,'identity',return_value=item):self.assertEqual(fault.real_child(123),item)
        for args in (['python','-c','search /app/asr_process.py --child'],['python','-u','/app/asr_process.py','--child','/other','/tmp/asr-manifest.json']):
            with patch.object(fault,'identity',return_value={**item,'arguments':args}),self.assertRaises(RuntimeError):fault.real_child(123)

    def test_worker_seccomp_filter_must_be_installed_before_fault(self):
        for count,expected in ((0,False),(2,False),(3,True)):
            with patch.object(fault,'filter_count',side_effect=[count,2]):
                self.assertEqual(fault.guard_ready(123),expected)

    def test_pidfd_never_signals_a_replaced_process_and_always_closes(self):
        item={'pid':123,'start':'fixed','arguments':['python']}
        with patch.object(fault.os,'pidfd_open',return_value=42,create=True),patch.object(fault.os,'close') as close,patch.object(fault.signal,'SIGKILL',9,create=True),patch.object(fault.signal,'pidfd_send_signal',create=True) as send:
            with patch.object(fault,'identity',return_value={**item,'start':'reused'}),self.assertRaises(RuntimeError):fault.kill_verified(item)
            send.assert_not_called();close.assert_called_once_with(42)
            close.reset_mock()
            with patch.object(fault,'identity',return_value=item):fault.kill_verified(item)
            send.assert_called_once_with(42,fault.signal.SIGKILL);close.assert_called_once_with(42)


if __name__=='__main__':unittest.main()
