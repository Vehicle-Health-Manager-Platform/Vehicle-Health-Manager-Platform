import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch,Mock
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_model import BgeEngine
from embedding_protocol import EmbeddingError

class ModelBudgetTest(unittest.TestCase):
    def test_exact_token_budget_includes_special_tokens_and_precedes_torch_import(self):
        # Explicit tokenizer fixture; this proves control flow, not real tokenization/model accuracy.
        engine=object.__new__(BgeEngine);engine.tokenizer=Mock();engine.tokenizer.encode_batch.return_value=[Mock(ids=list(range(513)))];engine.model=None
        with patch.dict(sys.modules,{'torch':None}),self.assertRaises(EmbeddingError) as error:engine.embed(['synthetic'])
        self.assertEqual(error.exception.code,'LIMIT_EXCEEDED');engine.tokenizer.encode_batch.assert_called_once_with(['synthetic'],add_special_tokens=True)
    def test_entire_batch_rejected_if_any_row_exceeds_token_budget(self):
        engine=object.__new__(BgeEngine);engine.tokenizer=Mock();engine.tokenizer.encode_batch.return_value=[Mock(ids=[101,102]),Mock(ids=list(range(513)))];engine.model=None
        with patch.dict(sys.modules,{'torch':None}),self.assertRaises(EmbeddingError):engine.embed(['short','synthetic long'])
if __name__=='__main__':unittest.main()
