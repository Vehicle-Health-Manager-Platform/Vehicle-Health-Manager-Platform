package com.autocare.platform.order;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Explicit, bounded lifecycle. Never changes knowledge payloads, receipts or business rows. */
final class KnowledgeEmbeddingRuntime implements AutoCloseable {
 private final ReservationStore db;private final LicensedKnowledge knowledge;private final EmbeddingProfile profile;
 private final boolean enabled,cleanup;private final KnowledgeVectorIndex index;
 private final AtomicBoolean busy=new AtomicBoolean();private volatile boolean closed,cycleFailed;
 private long documentCursor,cleanupCursor;
 KnowledgeEmbeddingRuntime(ReservationStore db,LicensedKnowledge knowledge,EmbeddingProfile profile,EmbeddingProvider provider,boolean enabled,boolean cleanup){
  if(enabled&&(profile==null||provider==null))throw new IllegalArgumentException("Embedding runtime not configured");
  if(enabled&&cleanup)throw new IllegalArgumentException("Cleanup-only maintenance cannot run with indexing");
  this.db=db;this.knowledge=knowledge;this.profile=profile;this.enabled=enabled;this.cleanup=cleanup||enabled;
  index=enabled?new KnowledgeVectorIndex(db,knowledge,profile,provider,true):null;
 }
 private void guard(){db.one("SELECT id FROM knowledge_control WHERE id=1 FOR UPDATE");}
 boolean configured(){return enabled||cleanup;}
 boolean cycleFailed(){return cycleFailed;}
 void tick(){
  if(closed||!configured()||!busy.compareAndSet(false,true))return;
  try{purge();if(enabled&&!closed){discover();if(!closed)index.workOne();}cycleFailed=false;}
  catch(RuntimeException ignored){cycleFailed=true;/* Only safe state; no provider diagnostics or document data. */}
  finally{busy.set(false);}
 }
 private void purge(){Long next=db.transactions.execute(t->{
  guard();if(closed)return null;
  var rows=db.jdbc.queryForList("SELECT j.* FROM knowledge_embedding_job j WHERE j.id>? AND (j.state<>'CANCELLED' OR EXISTS(SELECT 1 FROM knowledge_embedding_chunk c WHERE c.job_id=j.id)) ORDER BY j.id LIMIT 100 FOR UPDATE",cleanupCursor);
  if(rows.isEmpty())return 0L;
  for(var job:rows){long id=number(job,"id");
   // Different live profiles must never cancel each other's work. All-profile purge
   // is a separate maintenance mode after indexing instances have been stopped.
   String reason=!enabled?"FEATURE_DISABLED":profile.fingerprint().equals(job.get("profile_hash"))&&!index.same(job)?"SOURCE_CHANGED":null;
   if(reason!=null){db.jdbc.update("DELETE FROM knowledge_embedding_chunk WHERE job_id=?",id);db.jdbc.update("UPDATE knowledge_embedding_job SET state='CANCELLED',error_code=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",reason,time(db.now()),id);}
  }
  return number(rows.get(rows.size()-1),"id");
 });if(next!=null)cleanupCursor=next;}
 private void discover(){
  var documents=db.reads.execute(t->db.jdbc.queryForList("SELECT id FROM knowledge_document WHERE id>? ORDER BY id LIMIT 20",documentCursor));
  if(documents.isEmpty()){documentCursor=0;return;}
  for(var row:documents){if(closed)return;long id=number(row,"id");
   // enqueue owns its REQUIRES_NEW transaction/control lock. Never nest it under
   // another lock-holding transaction; authorization and re-enable are atomic there.
   try{index.enqueue(id,true);}catch(ResponseStatusException error){if(error.getStatusCode().value()!=404)throw error;}
   documentCursor=id;
  }
 }
 Map<String,Object> status(Map<String,Object> source){
  var out=new LinkedHashMap<String,Object>();out.put("state",enabled&&!closed?"NOT_QUEUED":"DISABLED");out.put("error_code",null);out.put("profile_hash",enabled&&!closed?profile.fingerprint():null);out.put("updated_at",null);
  if(!enabled||closed)return out;
  try{knowledge.embeddingSource(number(source,"id"));}catch(ResponseStatusException error){if(error.getStatusCode().value()!=404)throw error;out.put("state","NOT_ALLOWED");return out;}
  var rows=db.jdbc.queryForList("SELECT state,error_code,updated_at FROM knowledge_embedding_job WHERE document_id=? AND document_revision=? AND source_hash=? AND profile_hash=? AND profile_json=CAST(? AS JSON)",source.get("id"),source.get("revision"),source.get("source_hash"),profile.fingerprint(),profile.json());
  if(rows.size()==1){var row=rows.get(0);String state=Objects.toString(row.get("state"),"");String code=(String)row.get("error_code");
   if(!Set.of("QUEUED","RUNNING","SUCCEEDED","FAILED","CANCELLED").contains(state)||code!=null&&!Set.of("WORKER_LOST","UNAVAILABLE","INVALID_VECTOR","LIMIT_EXCEEDED","SOURCE_CHANGED","FEATURE_DISABLED").contains(code))throw new IllegalStateException("Embedding state unavailable");
   out.put("state",state);out.put("error_code",code);out.put("updated_at",iso(row.get("updated_at")));
  }
  return out;
 }
 @Override public void close(){closed=true;if(index!=null)index.close();}
}
