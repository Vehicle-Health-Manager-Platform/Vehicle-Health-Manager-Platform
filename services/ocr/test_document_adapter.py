"""Actual location contract with explicit Paddle library doubles, not model accuracy."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from recognition import PaddleEngine,DocumentLinesLimit


class DocumentAdapterTest(unittest.TestCase):
    def engine(self,prediction):
        engine=object.__new__(PaddleEngine);engine.model=SimpleNamespace(predict=lambda _:iter([prediction]));return engine
    def invoke(self,prediction):
        class Array:
            def __getitem__(self,key):return self
            def copy(self):return self
        with patch.dict(sys.modules,{'numpy':SimpleNamespace(asarray=lambda _:Array())}):return self.engine(prediction).recognize_document(None)
    def test_actual_model_polygon_is_preserved_without_invented_position(self):
        points=[[1,2],[30,2],[30,10],[1,10]]
        output=self.invoke({'rec_texts':['synthetic 123 km'],'rec_scores':[.91],'rec_polys':[points]})
        self.assertEqual(output,[{'text':'synthetic 123 km','confidence':.91,'points':points}])
    def test_missing_polygons_and_mismatched_rows_fail_closed(self):
        for prediction in ({'rec_texts':['x'],'rec_scores':[.9]}, {'rec_texts':['x'],'rec_scores':[.9],'rec_polys':[]}):
            with self.subTest(prediction=prediction),self.assertRaises((ValueError,KeyError)):self.invoke(prediction)
    def test_fake_numeric_confidence_is_rejected(self):
        for score in (True,'0.9'):
            with self.assertRaises(ValueError):self.invoke({'rec_texts':['x'],'rec_scores':[score],'rec_polys':[[[0,0],[1,0],[1,1],[0,1]]]})
    def test_line_budget_is_explicit_not_truncated(self):
        with self.assertRaises(DocumentLinesLimit):self.invoke({'rec_texts':['x']*81,'rec_scores':[.9]*81,'rec_polys':[[[0,0],[1,0],[1,1],[0,1]]]*81})


if __name__=='__main__':unittest.main()
