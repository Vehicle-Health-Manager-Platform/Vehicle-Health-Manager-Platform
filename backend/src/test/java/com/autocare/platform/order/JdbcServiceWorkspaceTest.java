package com.autocare.platform.order;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcServiceWorkspaceTest extends JdbcOwnerServiceEvidenceTest {
 ServiceTaskScope taskScope;ServiceWorkbench bench;OrderArchiveGrants grants;
 @BeforeEach void workspace(){
  for(String table:List.of("order_archive_grant","archive_structured_detail","vehicle_archive_file","vehicle_archive","vehicle"))jdbc.update("DELETE FROM `"+table+"`");
  jdbc.update("INSERT INTO vehicle(id,user_id,plate_no,current_mileage) VALUES(99,99,'synthetic-r7',1234)");
  jdbc.update("INSERT INTO vehicle_archive(id,vehicle_id,archive_type,input_type,content,recorded_at) VALUES(901,99,1,1,?,CURRENT_DATE)","{\"version\":1,\"title\":\"保养\",\"notes\":\"车主选择的记录\",\"mileage\":1000}");
  jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(601,'user',99,'synthetic-private-archive','image/png',100,'CLEAN')");
  jdbc.update("INSERT INTO vehicle_archive_file(archive_id,file_id,position) VALUES(901,601,0)");
  jdbc.update("UPDATE pickup_check SET mileage=1200 WHERE order_id=1");
  taskScope=new ServiceTaskScope(db,service,"test-app");bench=new ServiceWorkbench(taskScope);grants=new OrderArchiveGrants(taskScope);
 }
 @AfterEach void cleanAudit(){jdbc.execute("DROP TRIGGER IF EXISTS reject_r7b_audit");}
 com.fasterxml.jackson.databind.JsonNode candidates(){return db.mapper.valueToTree(grants.candidates(owner,1,1));}
 String version(){return candidates().path("records").path("items").get(0).path("source_version").asText();}
 com.fasterxml.jackson.databind.JsonNode set(String status,long revision,String source){return OrderArchiveGrants.input(db.json(status.equals("ACTIVE")?Map.of("status",status,"expected_revision",revision,"source_version",source):Map.of("status",status,"expected_revision",revision)));}
 void allow(){grants.set(owner,1,901,key(),set("ACTIVE",0,version()));}
 com.fasterxml.jackson.databind.JsonNode context(Object actor){return db.mapper.valueToTree(grants.context(actor,1,1));}
 long amount(Object actor,String bucket){return ((Number)((Map<?,?>)bench.read(actor,null,1).get("counts")).get(bucket)).longValue();}
 @Test void workbenchBucketsComeFromCurrentFactsAndExcludeOtherScopes(){
  assertEquals(1,amount(shop,"SERVICE"));assertEquals(1,amount(tech,"SERVICE"));assertEquals(0,amount(peer,"SERVICE"));assertEquals(0,amount(other,"SERVICE"));
  jdbc.update("UPDATE `order` SET status='PAID' WHERE id=1");assertEquals(1,amount(shop,"RECEIVE"));
  jdbc.update("UPDATE `order` SET status='RECEIVED',owner_confirmed_at=NULL WHERE id=1");assertEquals(1,amount(shop,"CONFIRM"));
  jdbc.update("UPDATE `order` SET owner_confirmed_at=UTC_TIMESTAMP() WHERE id=1");jdbc.update("UPDATE technician_assignment SET status='ASSIGNED',accepted_at=NULL WHERE order_id=1");assertEquals(1,amount(shop,"WAIT_ACCEPT"));
  jdbc.update("DELETE FROM technician_assignment");jdbc.update("UPDATE `order` SET assigned_at=NULL WHERE id=1");assertEquals(1,amount(shop,"ASSIGN"));
  jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");assertEquals(1,amount(shop,"DISPUTE"));jdbc.update("UPDATE `order` SET status='COMPLETED' WHERE id=1");assertEquals(0,((Map<?,?>)bench.read(shop,null,1).get("counts")).values().stream().mapToLong(v->((Number)v).longValue()).sum());
 }
 @Test void workbenchUsesBoundedPaginationAndMinimalTaskFields(){
  for(int i=2;i<=22;i++)jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(?,?,99,99,1,10,10,'PAID')",i,"synthetic-task-"+i);
  var first=db.mapper.valueToTree(bench.read(shop,null,1));assertEquals(22,first.path("tasks").path("total").asInt());assertEquals(20,first.path("tasks").path("items").size());assertEquals(2,db.mapper.valueToTree(bench.read(shop,null,2)).path("tasks").path("items").size());
  assertEquals(21,db.mapper.valueToTree(bench.read(shop,"RECEIVE",1)).path("tasks").path("total").asInt());for(String forbidden:List.of("user_id","phone","vin","vehicle_id"))assertFalse(first.toString().contains("\""+forbidden+"\""));
 }
 @Test void noDefaultSharingAndTechnicianMustCurrentlyHaveAcceptedTask(){
  assertEquals(0,context(shop).path("archives").path("total").asInt());assertEquals(1200,context(tech).path("arrival_mileage").asInt());assertFalse(context(tech).path("vehicle").has("current_mileage"));assertEquals(0,count("order_archive_grant"));
  assertEquals(404,status(()->grants.context(peer,1,1)));assertEquals(404,status(()->grants.context(other,1,1)));
  jdbc.update("UPDATE technician_assignment SET status='ASSIGNED',accepted_at=NULL");assertEquals(404,status(()->grants.context(tech,1,1)));
 }
 @Test void perRecordGrantIsAuditedIdempotentAndRevocableWithoutLeakingOwnerIdentity(){
  String key=key();var body=set("ACTIVE",0,version());int audit=count("audit_log");var receipt=grants.set(owner,1,901,key,body);assertEquals(receipt,grants.set(owner,1,901,key,body));assertEquals(audit+1,count("audit_log"));assertEquals(1,count("order_archive_grant"));
  assertEquals(1,context(shop).path("archives").path("total").asInt());assertEquals(99,grants.fileOwner(tech,1,901,601).id());
  String text=context(tech).toString();for(String forbidden:List.of("user_id","owner_id","phone","vin","plate_number","object_key","source_version"))assertFalse(text.contains("\""+forbidden+"\""));
  grants.set(owner,1,901,key(),set("REVOKED",1,null));assertEquals(0,context(shop).path("archives").path("total").asInt());assertEquals(404,status(()->grants.fileOwner(shop,1,901,601)));
  assertEquals(409,status(()->grants.set(owner,1,901,key(),body)));grants.set(owner,1,901,key(),set("ACTIVE",2,version()));assertEquals(1,context(shop).path("archives").path("total").asInt());
 }
 @Test void changedSourceNeedsNewOwnerConfirmationAndPreviewDoesNotGrant(){
  assertEquals(99,grants.ownerPreview(owner,1,901,601).id());assertEquals(0,count("order_archive_grant"));String old=version();allow();
  jdbc.update("UPDATE vehicle_archive SET content=JSON_SET(content,'$.notes','new content') WHERE id=901");assertEquals("SOURCE_CHANGED",candidates().path("records").path("items").get(0).path("grant_state").asText());
  assertTrue(context(shop).path("archives").path("items").get(0).path("record").isNull());assertEquals(404,status(()->grants.fileOwner(shop,1,901,601)));assertEquals(409,status(()->grants.set(owner,1,901,key(),set("ACTIVE",1,old))));
  grants.set(owner,1,901,key(),set("ACTIVE",1,version()));assertEquals("new content",context(shop).path("archives").path("items").get(0).path("record").path("notes").asText());
 }
 @Test void crossOwnerSensitiveTypesAndForeignFilesNeverGrant(){
  assertEquals(404,status(()->grants.candidates(outsider,1,1)));assertEquals(404,status(()->grants.set(outsider,1,901,key(),set("ACTIVE",0,version()))));
  assertEquals(404,status(()->grants.ownerPreview(owner,1,901,102)));jdbc.update("UPDATE vehicle_archive SET archive_type=3 WHERE id=901");assertEquals(0,candidates().path("records").path("total").asInt());assertEquals(404,status(()->grants.set(owner,1,901,key(),set("ACTIVE",0,"a".repeat(64)))));
 }
 @Test void sourceFileAccountTaskAndVehicleChangesImmediatelyDenyNewAccess(){
  allow();jdbc.update("UPDATE file_object SET scan_status='INFECTED' WHERE id=601");assertEquals(404,status(()->grants.fileOwner(shop,1,901,601)));jdbc.update("UPDATE file_object SET scan_status='CLEAN' WHERE id=601");
  jdbc.update("UPDATE technician_assignment SET technician_id=13");assertEquals(404,status(()->grants.context(tech,1,1)));jdbc.update("UPDATE technician_assignment SET technician_id=12");
  jdbc.update("UPDATE vehicle SET user_id=100 WHERE id=99");assertEquals(404,status(()->grants.context(shop,1,1)));jdbc.update("UPDATE vehicle SET user_id=99 WHERE id=99");
  jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=1");assertEquals(404,status(()->grants.context(shop,1,1)));jdbc.update("UPDATE `order` SET status='CLOSED' WHERE id=1");assertEquals(404,status(()->grants.context(tech,1,1)));
  grants.set(owner,1,901,key(),set("REVOKED",1,null));assertEquals("REVOKED",candidates().path("records").path("items").get(0).path("grant_state").asText());
 }
 @Test void permissionsAndAuditFailureAreCheckedOnEveryWriteIncludingReplay(){
  String key=key();var body=set("ACTIVE",0,version());jdbc.execute("CREATE TRIGGER reject_r7b_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
  assertEquals(503,status(()->grants.set(owner,1,901,key,body)));assertEquals(0,count("order_archive_grant"));jdbc.execute("DROP TRIGGER reject_r7b_audit");grants.set(owner,1,901,key,body);
  jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->grants.set(owner,1,901,key,body)));
 }
 @Test void concurrentConsentHasOneRevisionWinner()throws Exception{
  var body=set("ACTIVE",0,version());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
  try{var jobs=new ArrayList<java.util.concurrent.Future<Integer>>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{start.await();try{grants.set(owner,1,901,key(),body);return 200;}catch(org.springframework.web.server.ResponseStatusException e){return e.getStatusCode().value();}}));start.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(30,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,409),codes);assertEquals(1,count("order_archive_grant"));assertEquals(1,jdbc.queryForObject("SELECT revision FROM order_archive_grant",Integer.class));}finally{pool.shutdownNow();}
 }
 @Test void structuredChangeOrDeletionRequiresConsentAgain(){
  jdbc.update("INSERT INTO archive_structured_detail(user_id,vehicle_id,archive_id,archive_type,fields_json) VALUES(99,99,901,1,?)","{\"service_items\":[\"换油\"],\"provider_name\":\"private source\"}");allow();
  var fields=context(shop).path("archives").path("items").get(0).path("record").path("fields");assertEquals("换油",fields.path("service_items").get(0).asText());assertFalse(fields.has("provider_name"));
  jdbc.update("UPDATE archive_structured_detail SET revision=revision+1,fields_json=JSON_SET(fields_json,'$.service_items',JSON_ARRAY('new service')) WHERE archive_id=901");assertEquals("SOURCE_UNAVAILABLE",context(shop).path("archives").path("items").get(0).path("state").asText());
  grants.set(owner,1,901,key(),set("ACTIVE",1,version()));jdbc.update("UPDATE archive_structured_detail SET is_deleted=1 WHERE archive_id=901");assertEquals(404,status(()->grants.fileOwner(shop,1,901,601)));
 }
 @Test void automaticArchivePhotosRequireCompletedOriginalJobSignedReportAndOwner(){
  jdbc.update("DELETE FROM service_archive_job");submitted();work.sign(tech,key(),signature());long report=jdbc.queryForObject("SELECT id FROM technician_report WHERE order_id=1",Long.class);
  jdbc.update("UPDATE vehicle_archive SET input_type=4,content=? WHERE id=901",db.json(Map.of("title","自动归档","source",Map.of("user_id",99,"report_id",report))));
  jdbc.update("DELETE FROM vehicle_archive_file WHERE archive_id=901");jdbc.update("INSERT INTO vehicle_archive_file(archive_id,file_id,position) VALUES(901,102,0)");
  jdbc.update("INSERT INTO service_archive_job(order_id,status,archive_id) VALUES(1,'DONE',901)");
  jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(2,'synthetic-next-service',99,99,1,10,10,'RECEIVED')");
  var candidate=db.mapper.valueToTree(grants.candidates(owner,2,1));String source=candidate.path("records").path("items").get(0).path("source_version").asText();grants.set(owner,2,901,key(),set("ACTIVE",0,source));assertEquals(12,grants.fileOwner(shop,2,901,102).id());
  jdbc.update("UPDATE service_archive_job SET status='PENDING' WHERE order_id=1");assertEquals(404,status(()->grants.fileOwner(shop,2,901,102)));jdbc.update("UPDATE service_archive_job SET status='DONE' WHERE order_id=1");
  jdbc.update("UPDATE technician_report SET signed_at=NULL WHERE id=?",report);assertEquals(404,status(()->grants.fileOwner(shop,2,901,102)));jdbc.update("UPDATE technician_report SET signed_at=UTC_TIMESTAMP() WHERE id=?",report);
  jdbc.update("UPDATE `order` SET user_id=100 WHERE id=1");assertEquals(404,status(()->grants.fileOwner(shop,2,901,102)));
 }
}
