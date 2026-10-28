package com.autocare.platform.order;
import com.autocare.platform.file.SignedObjectStore;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.gateway.identity.AuthTokens;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real TCP/JWT/MySQL; synthetic document/provider/store, no actual document/model accuracy claim. */
@Import(DocumentHttpJourneyTest.Config.class)
@TestPropertySource(properties="DOCUMENT_ENABLED=false")
class DocumentHttpJourneyTest extends IsolatedCoreJourneysTest {
 @Autowired DocumentJobs jobs;@Autowired ReservationStore db;
 @Autowired @Qualifier("documentJobs") DocumentJobs disabledJobs;@Autowired AuthTokens authTokens;
 @TestConfiguration static class Config{@Bean @Primary DocumentJobs syntheticDocumentJobs(ReservationStore db){return new DocumentJobs(db,true);}}
 @Test void owner_creates_polls_confirms_and_cancels_without_automatic_business_write()throws Exception{
  long vehicle=vehicle("粤S12345");jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(91,1,?,'AI','synthetic-document-http','application/pdf',6480,0,1,'CLEAN')",vehicle);
  String path="/api/recognition/documents/jobs";var body=Map.of("vehicle_id",vehicle,"material_id",91,"purpose","AI","consent",true,"consent_version","DOCUMENT_EXTRACT_V1");
  request("POST",path,null,key(),body,401);request("POST",path,merchant,key(),body,403);request("POST",path+"?bad=1",owner,key(),body,400);
  request("POST",path,authTokens.binding("synthetic-document-binding","isolated-journeys"),key(),body,403);request("POST",path,owner,null,body,400);
  var disabled=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->disabledJobs.create(new VehicleOwner(1,issuedSessions.get(owner),Instant.now().plusSeconds(600)),mapper.valueToTree(body),key()));assertEquals(503,disabled.getStatusCode().value());
  String key=key();var created=request("POST",path,owner,key,body,200);assertEquals(created,request("POST",path,owner,key,body,200));long id=created.path("data").path("job_id").asLong();
  assertEquals("QUEUED",get(path+"/"+id,owner,200).path("data").path("state").asText());get(path+"/"+id,foreign,404);
  get(path+"/"+id+"?bad=1",owner,400);get(path+"/"+id,merchant,403);request("POST",path+"/"+id+"/cancel",foreign,key(),Map.of(),404);request("POST",path+"/"+id+"/cancel",owner,key(),Map.of("extra",1),400);
  var store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-document-http")).thenReturn(new SignedObjectStore.StoredObject("application/pdf",6480));when(store.read("synthetic-document-http",10485760)).thenReturn(JdbcDocumentJobsTest.audio());
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class);
  try(var worker=new DocumentJobWorker(jobs,store,(bytes,type,duration)->JdbcDocumentJobsTest.answer(mapper,duration),true)){assertTrue(worker.workOne());}
  var result=get(path+"/"+id,owner,200).path("data");assertEquals("SUCCEEDED",result.path("state").asText());assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
  request("POST",path+"/"+id+"/cancel",owner,key(),Map.of(),200);assertTrue(get(path+"/"+id,owner,200).path("data").path("result").isNull());
 }
 @Test void archive_candidate_is_only_saved_by_original_archive_endpoint()throws Exception{
  long vehicle=vehicle("粤S54321");jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(92,1,?,'ARCHIVE','synthetic-document-archive','application/pdf',6480,0,1,'CLEAN')",vehicle);
  String path="/api/recognition/documents/jobs";long id=request("POST",path,owner,key(),Map.of("vehicle_id",vehicle,"material_id",92,"purpose","ARCHIVE","consent",true,"consent_version","DOCUMENT_EXTRACT_V1"),200).path("data").path("job_id").asLong();
  var store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);when(store.stat("synthetic-document-archive")).thenReturn(new SignedObjectStore.StoredObject("application/pdf",6480));when(store.read("synthetic-document-archive",10485760)).thenReturn(JdbcDocumentJobsTest.audio());
  int before=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle);
  try(var worker=new DocumentJobWorker(jobs,store,(bytes,type,duration)->JdbcDocumentJobsTest.answer(mapper,duration),true)){assertTrue(worker.workOne());}
  var result=get(path+"/"+id,owner,200).path("data").path("result");assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle));
  var body=Map.of("vehicle_id",vehicle,"archive_type",1,"recorded_date",LocalDate.now(ZoneOffset.UTC).toString(),"mileage",100,"title","合成转写档案","notes",result.path("pages").get(0).path("text").asText(),"file_ids",List.of());String save=key();var added=request("POST","/api/archive/add",owner,save,body,200);assertEquals(added,request("POST","/api/archive/add",owner,save,body,200));assertEquals(before+1,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle));
 }

 @Test void document_four_kind_http_evidence()throws Exception{
  long vehicle=vehicle("粤S98765");
  jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(93,1,?,'AI','synthetic-document-evidence','application/pdf',6480,0,1,'CLEAN')",vehicle);
  String base="/api/recognition/documents/jobs",readOp=base+"/{id}",cancelOp=base+"/{id}/cancel";
  var e=new RecognitionHttpEvidence(this,base);String actor=token(1,"user","OWNER","isolated-journeys",null,null),createKey=key();
  var body=Map.of("vehicle_id",vehicle,"material_id",93,"purpose","AI","consent",true,"consent_version","DOCUMENT_EXTRACT_V1");
  int vehicles=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class),archives=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class);
  var created=e.send("create","normal","owner","POST",base,base,actor,createKey,body,200).path("data");
  long id=created.path("job_id").asLong();assertTrue(id>0);assertEquals(vehicle,created.path("vehicle_id").asLong());assertEquals(93,created.path("material_id").asInt());
  assertEquals(created,e.send("create_repeat","boundary","owner","POST",base,base,actor,createKey,body,200).path("data"));
  var noConsent=new HashMap<String,Object>(body);noConsent.put("consent",false);
  e.send("create_no_consent","error","owner","POST",base,base,actor,key(),noConsent,400);
  e.send("create_query","error","owner","POST",base,base+"?unexpected=1",actor,key(),body,400);
  e.send("create_anonymous","unauthorized","anonymous","POST",base,base,null,key(),body,401);
  e.send("create_role","unauthorized","merchant","POST",base,base,merchant,key(),body,403);
  e.send("create_foreign","unauthorized","foreign","POST",base,base,foreign,key(),body,404);
  String path=base+"/"+id;
  assertEquals("QUEUED",e.send("read","normal","owner","GET",readOp,path,actor,null,null,200).path("data").path("state").asText());
  e.send("read_missing","boundary","owner","GET",readOp,base+"/999999999",actor,null,null,404);
  e.send("read_query","error","owner","GET",readOp,path+"?unexpected=1",actor,null,null,400);
  e.send("read_anonymous","unauthorized","anonymous","GET",readOp,path,null,null,null,401);
  e.send("read_role","unauthorized","merchant","GET",readOp,path,merchant,null,null,403);
  e.send("read_foreign","unauthorized","foreign","GET",readOp,path,foreign,null,null,404);
  String cancel=path+"/cancel";
  e.send("cancel_body","error","owner","POST",cancelOp,cancel,actor,key(),Map.of("extra",1),400);
  e.send("cancel_query","error","owner","POST",cancelOp,cancel+"?unexpected=1",actor,key(),Map.of(),400);
  e.send("cancel_anonymous","unauthorized","anonymous","POST",cancelOp,cancel,null,key(),Map.of(),401);
  e.send("cancel_role","unauthorized","merchant","POST",cancelOp,cancel,merchant,key(),Map.of(),403);
  e.send("cancel_foreign","unauthorized","foreign","POST",cancelOp,cancel,foreign,key(),Map.of(),404);
  String cancelKey=key();var cancelled=e.send("cancel","normal","owner","POST",cancelOp,cancel,actor,cancelKey,Map.of(),200).path("data");
  assertEquals(cancelled,e.send("cancel_repeat","boundary","owner","POST",cancelOp,cancel,actor,cancelKey,Map.of(),200).path("data"));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job WHERE id=? AND owner_id=1 AND vehicle_id=? AND material_id=93 AND state='CANCELLED'",Integer.class,id,vehicle));
  assertTrue(get(path,actor,200).path("data").path("result").isNull());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job WHERE owner_id=1 AND vehicle_id=? AND material_id=93",Integer.class,vehicle));
  assertEquals(vehicles,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));assertEquals(archives,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive",Integer.class));
  e.finish("document-http-evidence.json",Map.of("no_vehicle_write",true,"no_archive_write",true,"job_binding_verified",true,"cancelled_result_withheld",true,"idempotent_replay_verified",true),Map.of("job_id",id));
 }

 @org.junit.jupiter.api.AfterEach void clear_failed_evidence_fixture(){
  // This synthetic material belongs only to the evidence method. A failed
  // assertion must not leave its QUEUED job for another scenario's worker.
  jdbc.update("UPDATE recognition_job SET state='CANCELLED',error_code='USER_CANCELLED',lease_token=NULL,lease_until=NULL,result_json=NULL WHERE material_id=93 AND state='QUEUED'");
 }
}
