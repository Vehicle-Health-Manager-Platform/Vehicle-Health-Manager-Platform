package com.autocare.platform.order;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.file.SignedObjectStore;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class JdbcDocumentJobsTest {
 @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
 static JdbcTemplate jdbc;final ObjectMapper mapper=new ObjectMapper();VehicleOwner owner;DocumentJobs jobs;ReservationStore db;SignedObjectStore store;
 static final String RAW="{\"vehicle_id\":101,\"material_id\":1,\"purpose\":\"AI\",\"consent\":true,\"consent_version\":\"DOCUMENT_EXTRACT_V1\"}";
 // Deliberately synthetic bytes/provider: this suite exercises lifecycle, not PDF/model accuracy.
 static byte[] audio(){return new byte[6480];}
 static JsonNode answer(ObjectMapper mapper,int pages){
  var r=mapper.createObjectNode().put("version",1).put("task","DOCUMENT").put("source","PDFBOX_PADDLE").put("status","EXTRACTED").put("content_type","application/pdf").put("content_sha256",com.autocare.platform.gateway.identity.AuthTokens.sha256(new String(audio(),java.nio.charset.StandardCharsets.UTF_8))).put("page_count",1).put("requires_confirmation",true).put("image_recognition_performed",false).put("all_pages_processed",true);
  var page=r.putArray("pages").addObject().put("page",1).put("method","NATIVE_TEXT").put("text","合成转写内容").putNull("raster_size");page.putArray("blocks").addObject().put("text","合成转写内容").putNull("confidence").putObject("source").put("kind","TEXT_OFFSET").put("start",0).put("end",6);return r;
 }
 static class MutableClock extends Clock {Instant value=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return value;}}
 MutableClock clock;
 @BeforeAll static void schema()throws Exception{var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);try(var c=ds.getConnection()){for(String n:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V062__input_material.sql","V063__recognition_job.sql","V064__document_candidate_jobs.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs/sql/migrations",n)));}}
 @BeforeEach void setup(){
  for(String table:List.of("recognition_job","input_material","auth_rate_limit","audit_log","idempotency_record","auth_session","vehicle","user"))jdbc.update("DELETE FROM "+table);
  jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-document-owner',1),(2,'synthetic-document-other',1)");jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES(101,1,100),(201,2,200)");
  owner=session(1);jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(1,1,101,'AI','synthetic-document-key','application/pdf',6480,0,1,'CLEAN')");
  clock=new MutableClock();var tx=new DataSourceTransactionManager(jdbc.getDataSource());db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,tx),clock,tx);jobs=new DocumentJobs(db,true);
  store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-document-key")).thenReturn(new SignedObjectStore.StoredObject("application/pdf",6480));when(store.read("synthetic-document-key",10485760)).thenReturn(audio());
 }
 VehicleOwner session(long id){var actor=new VehicleOwner(id,UUID.randomUUID().toString(),Instant.now().plusSeconds(3600));jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','asr-test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",actor.session(),id,UUID.randomUUID().toString());return actor;}
 String key(){return UUID.randomUUID().toString();}
 JsonNode create(String key){return jobs.create(owner,DocumentJobs.input(RAW),key);}
 long create(){return create(key()).path("data").path("job_id").longValue();}
 int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
 String state(long id){return jdbc.queryForObject("SELECT state FROM recognition_job WHERE id=?",String.class,id);}
 @Test void strict_body_consent_scope_duplicate_and_trailing_refused(){for(String raw:List.of("{}",RAW+"{}",RAW.replace("true","false"),RAW.replace("DOCUMENT_EXTRACT_V1","OLD"),RAW.replace("\"AI\"","\"OTHER\""),RAW.replace("101","-1"),RAW.replace("}",",\"vehicle_id\":101}"),RAW.replace("}",",\"url\":\"x\"}")))assertEquals(400,status(()->DocumentJobs.input(raw)));assertEquals(400,status(()->DocumentJobs.cancelInput("{\"extra\":1}")));}
 @Test void create_and_replay_have_one_task_one_counter_and_no_transcript_audit(){String key=key();var first=create(key);assertEquals(first,create(key));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='document_create'",Integer.class));assertFalse(jdbc.queryForList("SELECT * FROM recognition_job").toString().contains("synthetic-document-key"));assertFalse(jdbc.queryForList("SELECT * FROM audit_log").toString().contains("合成转写内容"));}
 @Test void same_key_different_authorized_body_refused(){String key=key();create(key);jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(2,1,101,'AI','synthetic-second-key','application/pdf',6480,0,1,'CLEAN')");assertEquals(400,status(()->jobs.create(owner,DocumentJobs.input(RAW.replace("\"material_id\":1","\"material_id\":2")),key)));}
 @Test void disabled_has_no_task_and_no_external_io(){jobs=new DocumentJobs(db,false);assertEquals(503,status(this::create));assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));verifyNoInteractions(store);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job",Integer.class));}
 @Test void other_owner_scope_and_revoked_current_session_refused(){var other=session(2);long id=create();assertEquals(404,status(()->jobs.get(other,id)));assertEquals(404,status(()->jobs.cancel(other,id,key())));assertEquals(404,status(()->jobs.create(owner,DocumentJobs.input(RAW.replace("101","201")),key())));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->jobs.get(owner,id)));}
 @Test void two_active_per_owner_and_replay_does_not_consume_limit(){long first=create();String key=key();create(key);assertEquals(429,status(this::create));assertDoesNotThrow(()->create(key));jobs.cancel(owner,first,key());assertTrue(create()>0);}
 @Test void per_minute_limit_shared_after_each_cancel(){for(int i=0;i<5;i++){long id=create();jobs.cancel(owner,id,key());}assertEquals(429,status(this::create));assertEquals(5,jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='document_create'",Integer.class));}
 @Test void success_persists_candidate_only_and_provider_has_no_open_transaction(){long id=create();assertTrue(jobs.workOne(store,(bytes,type,duration)->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertArrayEquals(audio(),bytes);return answer(mapper,duration);}));var result=jobs.get(owner,id);assertEquals("SUCCEEDED",result.path("state").asText());assertEquals("合成转写内容",result.path("result").path("pages").get(0).path("text").asText());assertEquals(100,jdbc.queryForObject("SELECT current_mileage FROM vehicle WHERE id=101",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));assertEquals(64,jdbc.queryForObject("SELECT LENGTH(content_sha256) FROM recognition_job WHERE id=?",Integer.class,id));}
 @Test void cancel_before_inference_and_after_success_withholds_text(){long id=create();jobs.cancel(owner,id,key());assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));assertEquals("CANCELLED",jobs.get(owner,id).path("state").asText());assertTrue(jobs.get(owner,id).path("result").isNull());long next=create();jobs.workOne(store,(b,t,d)->answer(mapper,d));jobs.cancel(owner,next,key());assertTrue(jobs.get(owner,next).path("result").isNull());}
 @Test void cancel_during_inference_wins_completion(){long id=create();jobs.workOne(store,(b,t,d)->{jobs.cancel(owner,id,key());return answer(mapper,d);});assertEquals("CANCELLED",state(id));assertTrue(jobs.get(owner,id).path("result").isNull());}
 @Test void original_session_revoked_even_with_new_login_blocks_delivery(){long id=create();var old=owner;owner=session(1);jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",old.session());assertEquals("AUTH_REVOKED",jobs.get(owner,id).path("error_code").asText());assertEquals("CANCELLED",state(id));}
 @Test void revoked_during_provider_failure_is_not_generic_upstream_success(){long id=create();var original=owner;var fresh=session(1);jobs.workOne(store,(b,t,d)->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",original.session());throw new IllegalStateException("private transcript");});assertEquals("AUTH_REVOKED",jobs.get(fresh,id).path("error_code").asText());assertTrue(jobs.get(fresh,id).path("result").isNull());}
 @Test void material_changes_or_transfer_block_stale_candidate(){long id=create();jobs.workOne(store,(b,t,d)->{jdbc.update("UPDATE input_material SET object_key='changed-key' WHERE id=1");return answer(mapper,d);});assertEquals("SOURCE_CHANGED",jobs.get(owner,id).path("error_code").asText());jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");assertEquals(404,status(()->jobs.get(owner,id)));}
 @Test void source_invalid_before_claim_never_reaches_store(){long id=create();jdbc.update("UPDATE input_material SET is_deleted=1 WHERE id=1");assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));assertEquals("FAILED",state(id));verifyNoInteractions(store);}
 @Test void expiry_purges_success_text_and_old_lease_cannot_publish(){long id=create();jobs.workOne(store,(b,t,d)->answer(mapper,d));clock.value=clock.value.plusSeconds(601);jobs.cleanup();assertEquals("EXPIRED",state(id));assertTrue(jobs.get(owner,id).path("result").isNull());clock.value=Instant.now();long lost=create();jobs.workOne(store,(b,t,d)->{clock.value=clock.value.plusSeconds(181);jobs.cleanup();return answer(mapper,d);});assertEquals("WORKER_LOST",jobs.get(owner,lost).path("error_code").asText());}
 @Test void bad_provider_and_store_metadata_fail_without_leaking(){long id=create();jobs.workOne(store,(b,t,d)->mapper.createObjectNode().put("private","secret"));assertEquals("INVALID_RESULT",jobs.get(owner,id).path("error_code").asText());long next=create();when(store.stat("synthetic-document-key")).thenReturn(new SignedObjectStore.StoredObject("application/pdf",6481));var calls=new AtomicInteger();jobs.workOne(store,(b,t,d)->{calls.incrementAndGet();return answer(mapper,d);});assertEquals("UNAVAILABLE",jobs.get(owner,next).path("error_code").asText());assertEquals(0,calls.get());}
 @Test void restart_cleanup_marks_lost_worker_without_requeue(){long id=create();assertNotNull(jobs.claim());clock.value=clock.value.plusSeconds(181);new DocumentJobs(db,true).cleanup();assertEquals("FAILED",state(id));assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));}
 @Test void concurrent_same_key_replays_single_committed_job()throws Exception{String key=key();var a=CompletableFuture.supplyAsync(()->create(key));var b=CompletableFuture.supplyAsync(()->create(key));assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job",Integer.class));}
 @Test void different_keys_concurrently_enforce_two_active_tasks_across_instances()throws Exception{
  var services=List.of(jobs,new DocumentJobs(db,true),new DocumentJobs(db,true));var ready=new CountDownLatch(3);var start=new CountDownLatch(1);var attempts=new ArrayList<CompletableFuture<Integer>>();
  for(var service:services)attempts.add(CompletableFuture.supplyAsync(()->{ready.countDown();try{if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException();service.create(owner,DocumentJobs.input(RAW),key());return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
  assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();var results=new ArrayList<Integer>();for(var task:attempts)results.add(task.get(10,TimeUnit.SECONDS));assertEquals(2,Collections.frequency(results,200));assertEquals(1,Collections.frequency(results,429));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job",Integer.class));
 }
 @Test void old_claim_cannot_overwrite_new_lease_and_recovery_does_not_replay(){
  long id=create();jobs.workOne(store,(b,t,d)->{jdbc.update("UPDATE recognition_job SET lease_token=? WHERE id=?",UUID.randomUUID().toString(),id);return answer(mapper,d);});
  assertEquals("RUNNING",state(id));assertNull(jdbc.queryForObject("SELECT result_json FROM recognition_job WHERE id=?",String.class,id));clock.value=clock.value.plusSeconds(181);jobs.cleanup();assertEquals("WORKER_LOST",jobs.get(owner,id).path("error_code").asText());assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));
 }
 @Test void disabling_existing_task_and_expired_origin_token_never_deliver_candidate(){
  long id=create();jobs.workOne(store,(b,t,d)->answer(mapper,d));assertEquals("FEATURE_DISABLED",new DocumentJobs(db,false).get(owner,id).path("error_code").asText());assertTrue(jobs.get(owner,id).path("result").isNull());
  owner=new VehicleOwner(owner.id(),owner.session(),clock.value.plusSeconds(60));long shortLived=create();clock.value=clock.value.plusSeconds(61);assertEquals("AUTH_REVOKED",jobs.get(owner,shortLived).path("error_code").asText());assertEquals("CANCELLED",state(shortLived));
 }
 @Test void corrupted_stored_result_is_purged_and_schema_rejects_unknown_public_error(){
  long id=create();jobs.workOne(store,(b,t,d)->answer(mapper,d));jdbc.update("UPDATE recognition_job SET result_json='{}' WHERE id=?",id);assertEquals("INVALID_RESULT",jobs.get(owner,id).path("error_code").asText());assertNull(jdbc.queryForObject("SELECT result_json FROM recognition_job WHERE id=?",String.class,id));
  for(String value:List.of("'private transcript'","NULL")){
   var rejected=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE recognition_job SET state='FAILED',error_code="+value+" WHERE id=?",id));
   assertEquals(3819,assertInstanceOf(java.sql.SQLException.class,rejected.getMostSpecificCause()).getErrorCode());
  }
 }
 @Test void worker_tick_returns_without_waiting_and_never_queues_extra_inference()throws Exception{
  long first=create(),second=create();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
  try(var worker=new DocumentJobWorker(jobs,store,(b,t,d)->{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return answer(mapper,d);},true)){
   long start=System.nanoTime();worker.tick();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);assertTrue(entered.await(5,TimeUnit.SECONDS));worker.tick();assertFalse(worker.workOne());assertEquals(1,calls.get());assertEquals("QUEUED",state(second));release.countDown();
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(state(first).equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(10);assertEquals("SUCCEEDED",state(first));assertEquals(1,calls.get());
  }finally{release.countDown();}
 }
 @Test void safe_document_error_and_cancel_keeps_original_material(){
  long id=create();jobs.workOne(store,(b,t,p)->{throw new DocumentProvider.Failed("INVALID_DOCUMENT");});assertEquals("INVALID_DOCUMENT",jobs.get(owner,id).path("error_code").asText());
  String key=key();assertEquals(jobs.cancel(owner,id,key),jobs.cancel(owner,id,key));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE id=1 AND is_deleted=0",Integer.class));verify(store,never()).delete(anyString());
 }
 @Test void cross_type_claim_get_cancel_and_cleanup_are_isolated(){
  long id=create();var asr=new AsrJobs(db,true);assertNull(asr.claim());assertEquals(404,status(()->asr.get(owner,id)));assertEquals(404,status(()->asr.cancel(owner,id,key())));
  clock.value=clock.value.plusSeconds(601);asr.cleanup();assertEquals("QUEUED",state(id));jobs.cleanup();assertEquals("EXPIRED",state(id));
 }
 @Test void page_limit_fails_without_provider_and_image_scope_is_supported(){
  jdbc.update("UPDATE input_material SET pages=5 WHERE id=1");long id=create();jobs.workOne(store,(b,t,p)->{fail();return null;});assertEquals("LIMIT_EXCEEDED",jobs.get(owner,id).path("error_code").asText());
  jdbc.update("UPDATE input_material SET content_type='image/png',pages=1 WHERE id=1");assertTrue(create()>0);
 }
 @Test void source_hash_type_and_geometry_mismatch_fail_closed(){
  long id=create();jobs.workOne(store,(b,t,p)->{var value=answer(mapper,p);((com.fasterxml.jackson.databind.node.ObjectNode)value).put("content_sha256","a".repeat(64));return value;});assertEquals("INVALID_RESULT",jobs.get(owner,id).path("error_code").asText());
 }
 @Test void migration_reapplication_preserves_existing_tasks_and_enforces_new_constraints()throws Exception{
  long id=create();try(var connection=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations/V064__document_candidate_jobs.sql")));}assertEquals("QUEUED",state(id));assertEquals("DOCUMENT",jdbc.queryForObject("SELECT task FROM recognition_job WHERE id=?",String.class,id));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE input_material SET pages=2,content_type='image/png' WHERE id=1"));
 }
 @Test void owner_can_erase_own_candidate_after_vehicle_transfer_but_cannot_read_it(){
  long id=create();jobs.workOne(store,(b,t,d)->answer(mapper,d));jdbc.update("UPDATE vehicle SET user_id=2 WHERE id=101");assertEquals(404,status(()->jobs.get(owner,id)));assertEquals(id,jobs.cancel(owner,id,key()).path("data").path("job_id").longValue());assertEquals("CANCELLED",state(id));assertNull(jdbc.queryForObject("SELECT result_json FROM recognition_job WHERE id=?",String.class,id));
 }
 @Test void purpose_change_is_not_reused_as_same_material_and_never_reaches_inference(){
  long id=create();jdbc.update("UPDATE input_material SET purpose='ARCHIVE' WHERE id=1");assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));assertEquals("SOURCE_CHANGED",jobs.get(owner,id).path("error_code").asText());verifyNoInteractions(store);
 }
 @Test void get_invalidates_expired_running_lease_without_waiting_for_periodic_cleanup(){
  long id=create();assertNotNull(jobs.claim());clock.value=clock.value.plusSeconds(181);var result=jobs.get(owner,id);assertEquals("FAILED",result.path("state").asText());assertEquals("WORKER_LOST",result.path("error_code").asText());assertTrue(result.path("result").isNull());assertNull(jdbc.queryForObject("SELECT lease_token FROM recognition_job WHERE id=?",String.class,id));assertFalse(jobs.workOne(store,(b,t,d)->{fail();return null;}));verifyNoInteractions(store);
 }
}
