"""Actual pinned BGE CPU encoder. No pickle, AutoModel, remote code or hub download."""
import json
from pathlib import Path
from embedding_assets import verify,verify_installed,profile,fingerprint
from embedding_protocol import EmbeddingError,result

class BgeEngine:
    def __init__(self,root):
        self.root=Path(root);verify(self.root);verify_installed();self.profile=profile();self.profile_hash=fingerprint(self.profile)
        config=json.loads((self.root/'config.json').read_text())
        expected={'model_type':'bert','hidden_size':512,'max_position_embeddings':512,'vocab_size':21128,'num_hidden_layers':4,'num_attention_heads':8,'pad_token_id':0}
        if any(config.get(k)!=v for k,v in expected.items()) or config.get('auto_map'):raise EmbeddingError('UNAVAILABLE')
        from tokenizers import Tokenizer
        self.tokenizer=Tokenizer.from_file(str(self.root/'tokenizer.json'))
        self.tokenizer.no_truncation();self.tokenizer.no_padding();self.model=None
        if [self.tokenizer.token_to_id(v) for v in ('[PAD]','[CLS]','[SEP]')]!=[0,101,102]:raise EmbeddingError('UNAVAILABLE')
    def embed(self,texts):
        encoded=self.tokenizer.encode_batch(texts,add_special_tokens=True)
        # Budget before importing/loading the inference engine. Never silently trim.
        if any(not 2<=len(row.ids)<=512 for row in encoded):raise EmbeddingError('LIMIT_EXCEEDED')
        import torch
        if self.model is None:
            from transformers import BertModel
            torch.set_num_threads(2);torch.set_num_interop_threads(1)
            self.model=BertModel.from_pretrained(str(self.root),local_files_only=True,use_safetensors=True,torch_dtype=torch.float32,attn_implementation='eager').eval()
        width=max(len(row.ids) for row in encoded)
        ids=torch.zeros((len(texts),width),dtype=torch.long);mask=torch.zeros_like(ids);types=torch.zeros_like(ids)
        for index,row in enumerate(encoded):
            ids[index,:len(row.ids)]=torch.tensor(row.ids,dtype=torch.long);mask[index,:len(row.ids)]=1
            types[index,:len(row.ids)]=torch.tensor(row.type_ids,dtype=torch.long)
        with torch.no_grad():
            vectors=self.model(input_ids=ids,attention_mask=mask,token_type_ids=types,return_dict=True).last_hidden_state[:,0,:]
            vectors=torch.nn.functional.normalize(vectors,p=2,dim=1)
        return result({'version':1,'profile_hash':self.profile_hash,'vectors':vectors.tolist()},self.profile_hash,len(texts))
