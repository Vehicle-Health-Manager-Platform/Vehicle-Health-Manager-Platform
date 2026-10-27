package com.autocare.platform.order;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Internal R5a foundation. No Spring bean, public route, scheduler or default provider. */
final class KnowledgeVectorIndex implements AutoCloseable {
 record Hit(long documentId,String reference,long revision,int ordinal,int start,int end,double score){}
 private final ReservationStore db;private final LicensedKnowledge knowledge;private final EmbeddingProfile profile;private final String profileHash,profileJson;private final boolean enabled;private final EmbeddingExecutor executor;
 KnowledgeVectorIndex(ReservationStore db,LicensedKnowledge knowledge,EmbeddingProfile profile,EmbeddingProvider provider,boolean enabled){this(db,knowledge,profile,provider,enabled,Duration.ofSeconds(30));}
 KnowledgeVectorIndex(ReservationStore db,LicensedKnowledge knowledge,EmbeddingProfile profile,EmbeddingProvider provider,boolean enabled,Duration deadline){this.db=db;this.knowledge=knowledge;this.profile=Objects.requireNonNull(profile);this.profileHash=profile.fingerprint();this.profileJson=profile.json();this.enabled=enabled;this.executor=provider==null?null:new EmbeddingExecutor(profile,provider,deadline);}
 private void guard(){db.one("SELECT id FROM knowledge_control WHERE id=1 FOR UPDATE");}
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"向量索引基础未启用或暂不可用；现有检索仍为关键词");}
 private void ready(){if(!enabled||executor==null)throw unavailable();}
 private boolean same(Map<String,Object> job){
  try{return matches(job,knowledge.embeddingSource(number(job,"document_id")));}catch(ResponseStatusException e){return false;}
 }
 private boolean matches(Map<String,Object> job,Map<String,Object> source){return profileHash.equals(job.get("profile_hash"))&&db.parse(job.get("profile_json")).equals(db.parse(profileJson))&&number(source,"revision")==number(job,"document_revision")&&Objects.equals(source.get("source_hash"),job.get("source_hash"));}
 private void invalidate(long id,String code){db.jdbc.update("DELETE FROM knowledge_embedding_chunk WHERE job_id=?",id);db.jdbc.update("UPDATE knowledge_embedding_job SET state='CANCELLED',error_code=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",code,time(db.now()),id);}
 long enqueue(long document){ready();return db.transactions.execute(t->{
  guard();var source=knowledge.embeddingSource(document);
  db.jdbc.update("INSERT IGNORE INTO knowledge_embedding_job(document_id,document_revision,source_hash,profile_hash,profile_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",document,source.get("revision"),source.get("source_hash"),profileHash,profileJson,time(db.now()),time(db.now()));
  return db.jdbc.queryForObject("SELECT id FROM knowledge_embedding_job WHERE document_id=? AND document_revision=? AND source_hash=? AND profile_hash=?",Long.class,document,source.get("revision"),source.get("source_hash"),profileHash);
 });}
 void cleanup(){db.transactions.executeWithoutResult(t->{guard();
  var jobs=db.jdbc.queryForList("SELECT * FROM knowledge_embedding_job WHERE profile_hash=? AND state<>'CANCELLED' ORDER BY updated_at,id LIMIT 100 FOR UPDATE",profileHash);
  for(var job:jobs){long id=number(job,"id");if(!enabled||!same(job))invalidate(id,enabled?"SOURCE_CHANGED":"FEATURE_DISABLED");else if("RUNNING".equals(job.get("state"))&&!instant(job.get("lease_until")).isAfter(db.now()))db.jdbc.update("UPDATE knowledge_embedding_job SET state='FAILED',error_code='WORKER_LOST',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",time(db.now()),id);else db.jdbc.update("UPDATE knowledge_embedding_job SET updated_at=? WHERE id=?",time(db.now()),id);}
 });}
 Map<String,Object> claim(){if(!enabled||executor==null)return null;cleanup();return db.transactions.execute(t->{guard();
  var rows=db.jdbc.queryForList("SELECT * FROM knowledge_embedding_job WHERE profile_hash=? AND state='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",profileHash);if(rows.isEmpty())return null;
  var job=rows.get(0);if(!same(job)){invalidate(number(job,"id"),"SOURCE_CHANGED");return null;}
  String lease=UUID.randomUUID().toString();db.jdbc.update("UPDATE knowledge_embedding_job SET state='RUNNING',lease_token=?,lease_until=?,updated_at=? WHERE id=?",lease,time(db.now().plusSeconds(180)),time(db.now()),job.get("id"));return db.jdbc.queryForMap("SELECT * FROM knowledge_embedding_job WHERE id=?",job.get("id"));
 });}
 boolean workOne(){var job=claim();if(job==null)return false;List<KnowledgeChunks.Chunk> chunks=null;List<double[]> vectors=null;String failure="UNAVAILABLE";
  try{
   String body=db.transactions.execute(t->{guard();if(!same(job))throw unavailable();return db.parse(knowledge.embeddingSource(number(job,"document_id")).get("payload")).path("body").asText();});
   chunks=KnowledgeChunks.split(body);vectors=executor.call(chunks.stream().map(KnowledgeChunks.Chunk::text).toList());
  }catch(EmbeddingExecutor.Failed e){failure=e.code;}catch(IllegalArgumentException e){failure="INVALID_VECTOR";}catch(RuntimeException ignored){/* No source, query, model output or provider exception in logs. */}
  final var savedChunks=chunks;final var savedVectors=vectors;final String error=failure;
  db.transactions.executeWithoutResult(t->{guard();var current=db.one("SELECT * FROM knowledge_embedding_job WHERE id=? FOR UPDATE",job.get("id"));
   if(!"RUNNING".equals(current.get("state"))||!Objects.equals(current.get("lease_token"),job.get("lease_token")))return;
   if(!enabled||!same(current)){invalidate(number(current,"id"),enabled?"SOURCE_CHANGED":"FEATURE_DISABLED");return;}
   if(!instant(current.get("lease_until")).isAfter(db.now())){db.jdbc.update("UPDATE knowledge_embedding_job SET state='FAILED',error_code='WORKER_LOST',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",time(db.now()),job.get("id"));return;}
   if(savedVectors!=null){for(int i=0;i<savedChunks.size();i++){var c=savedChunks.get(i);db.jdbc.update("INSERT INTO knowledge_embedding_chunk(job_id,ordinal,start_offset,end_offset,chunk_hash,content_text,dimension,vector_json) VALUES(?,?,?,?,?,?,?,?)",job.get("id"),c.ordinal(),c.start(),c.end(),c.hash(),c.text(),profile.dimension(),db.json(savedVectors.get(i)));}}
   db.jdbc.update("UPDATE knowledge_embedding_job SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",savedVectors==null?"FAILED":"SUCCEEDED",savedVectors==null?error:null,time(db.now()),job.get("id"));
  });return true;
 }
 List<Hit> rank(VehicleOwner owner,long vehicle,String query,int limit){
  ready();if(limit<1||limit>10)throw new IllegalArgumentException("Invalid vector result budget");KnowledgeInput.text(db.mapper.getNodeFactory().textNode(query),80,false);
  Object before=db.reads.execute(t->knowledge.embeddingModel(owner,vehicle));if(before==null)return List.of();
  double[] question;try{question=executor.call(List.of(profile.queryPrefix()+query)).get(0);}catch(EmbeddingExecutor.Failed e){throw unavailable();}
  return db.transactions.execute(t->{guard();Object model=knowledge.embeddingModel(owner,vehicle);if(!Objects.equals(before,model))throw unavailable();
   var rows=db.jdbc.queryForList("SELECT c.*,j.document_id,j.document_revision,j.source_hash,j.profile_hash,j.profile_json FROM knowledge_embedding_job j LEFT JOIN knowledge_embedding_chunk c ON c.job_id=j.id JOIN knowledge_document d ON d.id=j.document_id WHERE j.profile_hash=? AND j.state='SUCCEEDED' AND d.model_id=? ORDER BY j.document_id DESC,c.ordinal LIMIT 513",profileHash,model);
   var valid=new ArrayList<Map<String,Object>>();var sources=new HashMap<Long,Map<String,Object>>();var hidden=new HashSet<Long>();
   // Invalid/revoked jobs never become usable, even if cleanup has not run.
   for(var row:rows){long document=number(row,"document_id");if(!sources.containsKey(document)&&!hidden.contains(document))try{sources.put(document,knowledge.embeddingSource(document));}catch(ResponseStatusException e){hidden.add(document);}var source=sources.get(document);if(source!=null&&matches(row,source))valid.add(row);}
   if(rows.size()>512)throw unavailable();
   var expected=new HashMap<Long,List<KnowledgeChunks.Chunk>>();var best=new HashMap<Long,Hit>();
   try{for(var row:valid){
    long document=number(row,"document_id");var source=sources.get(document);var parts=expected.computeIfAbsent(document,id->KnowledgeChunks.split(db.parse(source.get("payload")).path("body").asText()));if(row.get("ordinal")==null)throw unavailable();int ordinal=((Number)row.get("ordinal")).intValue();
    if(ordinal<0||ordinal>=parts.size())throw unavailable();var chunk=parts.get(ordinal);
    if(number(row,"dimension")!=profile.dimension()||number(row,"start_offset")!=chunk.start()||number(row,"end_offset")!=chunk.end()||!Objects.equals(row.get("chunk_hash"),chunk.hash())||!Objects.equals(row.get("content_text"),chunk.text()))throw unavailable();
    JsonNode raw=db.parse(row.get("vector_json"));if(!raw.isArray()||raw.size()!=profile.dimension())throw unavailable();double[] vector=new double[raw.size()];for(int i=0;i<raw.size();i++){if(!raw.get(i).isNumber())throw unavailable();vector[i]=raw.get(i).doubleValue();}
    var hit=new Hit(document,source.get("public_id").toString(),number(source,"revision"),ordinal,chunk.start(),chunk.end(),EmbeddingVectors.cosine(question,vector));var old=best.get(document);if(old==null||hit.score()>old.score())best.put(document,hit);
   }}catch(IllegalArgumentException e){throw unavailable();}
   for(var entry:expected.entrySet())if(valid.stream().filter(r->number(r,"document_id")==entry.getKey()).count()!=entry.getValue().size())throw unavailable();
   return best.values().stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Comparator.comparingLong(Hit::documentId).reversed()).thenComparingInt(Hit::ordinal)).limit(limit).toList();
  });
 }
 public void close(){if(executor!=null)executor.close();}
}
