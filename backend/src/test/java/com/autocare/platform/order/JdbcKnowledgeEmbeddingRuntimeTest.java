package com.autocare.platform.order;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL authorization/lifecycle, explicitly synthetic vectors. */
class JdbcKnowledgeEmbeddingRuntimeTest extends JdbcLicensedKnowledgeTest {
 KnowledgeEmbeddingRuntime runtime;EmbeddingProfile profile=EmbeddingFoundationTest.profile();AtomicInteger calls=new AtomicInteger();
 EmbeddingProvider provider=(p,texts)->{calls.incrementAndGet();return new EmbeddingProvider.Output(p.fingerprint(),texts.stream().map(t->new double[]{1,0,0}).toList());};
 @AfterEach void closeRuntime(){if(runtime!=null)runtime.close();}
 ObjectNode allowed(){return config().put("embedding_allowed",true).put("embedding_policy_version",KnowledgeInput.EMBEDDING_POLICY);}
 long published(ObjectNode value){long id=createKnowledge(value);publishKnowledge(id);return id;}
 void start(boolean enabled,boolean cleanup){runtime=new KnowledgeEmbeddingRuntime(db,knowledge,enabled?profile:null,enabled?provider:null,enabled,cleanup);knowledge.embeddingRuntime(runtime);}
 Map<String,Object> status(long id){return runtime.status(jdbc.queryForMap("SELECT * FROM knowledge_document WHERE id=?",id));}
 String jobState(){return jdbc.queryForObject("SELECT state FROM knowledge_embedding_job LIMIT 1",String.class);}
 @Test void disabledDoesNotDiscoverCallOrMutateAndLeavesLegacyViewUnchanged(){published(allowed());start(false,false);runtime.tick();assertFalse(runtime.configured());assertEquals(0,calls.get());assertEquals(0,count("knowledge_embedding_job"));assertFalse(json.valueToTree(knowledge.admin(manager,1)).at("/items/0").has("embedding"));}
 @Test void discoveryIndexesOnlyTrustedPublishedExplicitlyLicensedSources(){
  long valid=published(allowed());published(config());createKnowledge(allowed());published(allowed().put("embedding_allowed",false));published(allowed().put("valid_to",clock.value.plusSeconds(1).toString()));long bad=published(allowed());jdbc.update("UPDATE knowledge_document SET payload=JSON_SET(payload,'$.title','tampered') WHERE id=?",bad);clock.value=clock.value.plusSeconds(2);start(true,false);runtime.tick();assertFalse(runtime.cycleFailed());assertEquals(1,calls.get());assertEquals(1,count("knowledge_embedding_job"));assertEquals("SUCCEEDED",status(valid).get("state"));
 }
 @Test void generatedStateIsRestrictedAndDoesNotChangeKnowledgeAuditOrReceipts(){
  long id=published(allowed());int audits=count("audit_log"),events=count("knowledge_document_event");String before=jdbc.queryForObject("SELECT payload FROM knowledge_document WHERE id=?",String.class,id);
  start(true,false);assertEquals("NOT_QUEUED",status(id).get("state"));runtime.tick();assertFalse(runtime.cycleFailed());assertEquals("SUCCEEDED",status(id).get("state"));assertEquals(1,calls.get());runtime.tick();assertEquals(1,calls.get());assertEquals(1,count("knowledge_embedding_job"));
  assertEquals(audits,count("audit_log"));assertEquals(events,count("knowledge_document_event"));assertEquals(before,jdbc.queryForObject("SELECT payload FROM knowledge_document WHERE id=?",String.class,id));assertEquals(403,code(()->knowledge.admin(first,1)));assertEquals(Set.of("state","error_code","profile_hash","updated_at"),status(id).keySet());assertFalse(db.json(status(id)).contains("冷却液"));
  assertEquals("KEYWORD",knowledge.search(owner,1,"冷却液",null).get("method"));
 }
 @Test void stoppedSourcesArePurgedWithoutWaitingForSuccessfulQueries(){long id=published(allowed());start(true,false);runtime.tick();assertEquals(1,count("knowledge_embedding_chunk"));knowledge.status(manager,id,key(),state(2,"STOPPED"));assertEquals("NOT_ALLOWED",status(id).get("state"));runtime.tick();assertEquals("CANCELLED",jobState());assertEquals(0,count("knowledge_embedding_chunk"));assertEquals(1,calls.get());}
 @Test void failuresRemainTerminalInsteadOfAnAutomaticRetryLoop(){published(allowed());provider=(p,t)->{calls.incrementAndGet();throw new EmbeddingProvider.Failed("UNAVAILABLE");};start(true,false);for(int i=0;i<6;i++)runtime.tick();assertEquals("FAILED",jobState());assertEquals(1,calls.get());assertEquals(0,count("knowledge_embedding_chunk"));}
 @Test void cleanupOnlyPurgesAllProfilesAndAuthorizedReenableCanRegenerate(){
  long id=published(allowed());try(var index=new KnowledgeVectorIndex(db,knowledge,profile,provider,true)){index.enqueue(id);index.workOne();}
  start(false,true);runtime.tick();assertEquals("CANCELLED",jobState());assertEquals(0,count("knowledge_embedding_chunk"));assertEquals("DISABLED",status(id).get("state"));runtime.close();
  runtime=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false);runtime.tick();assertEquals("SUCCEEDED",jobState());assertEquals(1,count("knowledge_embedding_chunk"));assertEquals(2,calls.get());
 }
 @Test void sourceChangedCancellationNeverResurrects(){long id=published(allowed());start(true,false);runtime.tick();knowledge.status(manager,id,key(),state(2,"STOPPED"));for(int i=0;i<5;i++)runtime.tick();assertEquals(1,calls.get());assertEquals("CANCELLED",jobState());}
 @Test void differentLiveProfilesDoNotCancelEachOther(){long id=published(allowed());try(var index=new KnowledgeVectorIndex(db,knowledge,profile,provider,true)){index.enqueue(id);index.workOne();}profile=new EmbeddingProfile(profile.model(),"b".repeat(40),3,"");start(true,false);runtime.tick();assertEquals(2,count("knowledge_embedding_job"));assertEquals(2,count("knowledge_embedding_chunk"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job WHERE state='SUCCEEDED'",Integer.class));}
 @Test void discoveryWrapsPastAnIneligiblePage(){for(int i=0;i<21;i++)createKnowledge(config());published(allowed());start(true,false);runtime.tick();assertEquals(0,calls.get());runtime.tick();assertEquals(1,calls.get());for(int i=0;i<4;i++)runtime.tick();assertEquals(1,calls.get());}
 @Test void overlappingTicksRefuseASecondProviderJob()throws Exception{
  published(allowed());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);provider=(p,t)->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException();}return new EmbeddingProvider.Output(p.fingerprint(),List.of(new double[]{1,0,0}));};start(true,false);
  var pool=Executors.newSingleThreadExecutor();try{var future=pool.submit(runtime::tick);assertTrue(entered.await(10,TimeUnit.SECONDS));runtime.tick();assertEquals(1,count("knowledge_embedding_job"));release.countDown();future.get(10,TimeUnit.SECONDS);assertEquals("SUCCEEDED",jobState());}finally{release.countDown();pool.shutdownNow();}
 }
 @Test void closedRuntimeNeverDiscoversOrCallsProvider(){published(allowed());start(true,false);runtime.close();runtime.tick();assertEquals(0,calls.get());assertEquals(0,count("knowledge_embedding_job"));}
 @Test void multipleInstancesShareOneDatabaseAdmissionSlot()throws Exception{
  published(allowed());published(allowed());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  provider=(p,t)->{calls.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException();}return new EmbeddingProvider.Output(p.fingerprint(),List.of(new double[]{1,0,0}));};start(true,false);
  var pool=Executors.newSingleThreadExecutor();try(var peer=new KnowledgeEmbeddingRuntime(db,knowledge,profile,provider,true,false)){
   var first=pool.submit(runtime::tick);assertTrue(entered.await(10,TimeUnit.SECONDS));peer.tick();assertFalse(peer.cycleFailed());assertEquals(1,calls.get());assertEquals(2,count("knowledge_embedding_job"));release.countDown();first.get(10,TimeUnit.SECONDS);peer.tick();assertEquals(2,calls.get());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job WHERE state='SUCCEEDED'",Integer.class));
  }finally{release.countDown();pool.shutdownNow();}
 }
 @Test void cleanupUsesBoundedKeysetPagesAcrossMoreThan100Profiles(){
  long id=published(allowed());for(int i=0;i<105;i++){var p=new EmbeddingProfile("fixture-"+i,"a".repeat(40),3,"");try(var index=new KnowledgeVectorIndex(db,knowledge,p,provider,true)){index.enqueue(id);}}
  start(false,true);runtime.tick();assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job WHERE state='CANCELLED'",Integer.class));runtime.tick();assertEquals(105,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job WHERE state='CANCELLED'",Integer.class));assertEquals(0,calls.get());
 }
 @Test void failedCleanupTransactionDoesNotAdvanceTheKeysetCheckpoint(){
  long id=published(allowed());try(var index=new KnowledgeVectorIndex(db,knowledge,profile,provider,true)){index.enqueue(id);}
  long job=jdbc.queryForObject("SELECT id FROM knowledge_embedding_job",Long.class);start(false,true);
  jdbc.execute("CREATE TRIGGER reject_embedding_cleanup BEFORE UPDATE ON knowledge_embedding_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic cleanup fault'");
  try{runtime.tick();assertTrue(runtime.cycleFailed());assertEquals("QUEUED",jobState());}finally{jdbc.execute("DROP TRIGGER reject_embedding_cleanup");}
  runtime.tick();assertFalse(runtime.cycleFailed());assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM knowledge_embedding_job WHERE id=?",String.class,job));
 }
}
