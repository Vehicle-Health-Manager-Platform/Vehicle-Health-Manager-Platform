"""Docker auto-removal may lag stop; ownership and a bounded deadline remain mandatory."""
from types import SimpleNamespace
import json
from unittest.mock import patch, Mock
import unittest
from run_performance_baseline import Owned, Docker, PerformanceError


class OwnedCleanupTest(unittest.TestCase):
    def obj(self):
        obj=Owned('synthetic','mysql','synthetic-name',{'synthetic':'owned'})
        obj.id='a'*64;obj.guard=Mock();return obj

    def info(self,obj):
        return json.dumps({'Id':obj.id,'Name':'/'+obj.name,'Config':{'Labels':obj.labels}}).encode()

    def test_waits_for_auto_remove_without_repeating_stop(self):
        obj=self.obj();statuses=iter([0,0,1]);calls=[]
        def docker(args,**kwargs):
            calls.append(args)
            return SimpleNamespace(returncode=next(statuses) if args[0]=='inspect' else 0,stdout=self.info(obj))
        with patch.object(Docker,'run',side_effect=docker),patch('run_performance_baseline.time.sleep'):
            obj.close()
        self.assertIsNone(obj.id)
        self.assertEqual(1,sum(x[0]=='stop' for x in calls))
        # There is no second inspect/guard racing an asynchronously removed snapshot.
        self.assertEqual(1,obj.guard.call_count)

    def test_cleanup_deadline_still_fails_and_preserves_id(self):
        obj=self.obj()
        with patch.object(Docker,'run',return_value=SimpleNamespace(returncode=0,stdout=self.info(obj))),patch('run_performance_baseline.time.monotonic',side_effect=[0,0,11]),patch('run_performance_baseline.time.sleep'):
            with self.assertRaisesRegex(PerformanceError,'deadline'):obj.close()
        self.assertEqual('a'*64,obj.id)

    def test_changed_ownership_aborts_wait_without_more_mutations(self):
        obj=self.obj()
        with patch.object(Docker,'run',return_value=SimpleNamespace(returncode=0,stdout=b'{"Id":"foreign"}')) as docker:
            with self.assertRaisesRegex(PerformanceError,'ownership'):obj.close()
        self.assertEqual(['stop','inspect'],[x.args[0][0] for x in docker.call_args_list])


if __name__=='__main__':unittest.main()
