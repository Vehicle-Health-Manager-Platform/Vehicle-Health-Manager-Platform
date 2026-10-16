package com.autocare.platform.order;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcServiceNotificationsTest extends JdbcOwnerServiceEvidenceTest {
 ServiceNotifications notices;
 @BeforeEach void notifications(){jdbc.execute("DROP TRIGGER IF EXISTS reject_r7c_notice");jdbc.update("DELETE FROM notification");jdbc.update("DELETE FROM service_notification_outbox");notices=new ServiceNotifications(db,"test-app");}
 @AfterEach void cleanupNotificationTrigger(){jdbc.execute("DROP TRIGGER IF EXISTS reject_r7c_notice");}
 void emit(String kind){db.transactions.executeWithoutResult(tx->ServiceNotifications.enqueue(db,1,kind));}
 com.fasterxml.jackson.databind.JsonNode inbox(Object actor){return db.mapper.valueToTree(notices.list(actor,1));}
 long notice(Object actor){return inbox(actor).path("notifications").path("items").get(0).path("notification_id").asLong();}
 @Test void eventAndBusinessChangesRollBackTogetherAndReadsNeverProduceEvents(){
  assertThrows(RuntimeException.class,()->db.transactions.executeWithoutResult(tx->{jdbc.update("UPDATE `order` SET status='PENDING_VERIFY' WHERE id=1");ServiceNotifications.enqueue(db,1,"ORDER_SERVICE_FINISH");throw new IllegalStateException("synthetic rollback");}));assertEquals("IN_SERVICE",state());assertEquals(0,count("service_notification_outbox"));assertEquals(0,inbox(owner).path("unread_count").asInt());assertEquals(0,count("notification"));assertThrows(IllegalStateException.class,()->ServiceNotifications.enqueue(db,1,"ORDER_ASSIGN"));
 }
 @Test void repeatedSourcesSurviveRestartAndRefundsExcludeTechnicians(){
  db.transactions.executeWithoutResult(tx->{ServiceNotifications.enqueue(db,1,"REFUND_REQUESTED","refund:7:1");ServiceNotifications.enqueue(db,1,"REFUND_REQUESTED","refund:7:1");ServiceNotifications.enqueue(db,1,"REFUND_REQUESTED","refund:7:3");ServiceNotifications.enqueue(db,1,"DISPUTE_HANDLED","record:11");ServiceNotifications.enqueue(db,1,"DISPUTE_HANDLED","record:12");});
  assertEquals(4,count("service_notification_outbox"));new ServiceNotifications(db,"test-app").recover();assertEquals(10,count("notification"));assertEquals(4,inbox(owner).path("unread_count").asInt());assertEquals(4,inbox(shop).path("unread_count").asInt());assertEquals(2,inbox(tech).path("unread_count").asInt());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM service_notification_outbox WHERE kind LIKE 'REFUND_%' AND technician_id IS NOT NULL",Integer.class));
 }
 @Test void recipientsUseCurrentRowsAfterAnEarlierTransactionReadView(){
  var pool=Executors.newSingleThreadExecutor();try{db.transactions.executeWithoutResult(tx->{
   assertEquals(99,jdbc.queryForObject("SELECT user_id FROM `order` WHERE id=1",Integer.class));assertEquals(12,jdbc.queryForObject("SELECT technician_id FROM technician_assignment WHERE order_id=1",Integer.class));
   try{pool.submit(()->{jdbc.update("UPDATE `order` SET user_id=100 WHERE id=1");jdbc.update("UPDATE technician_assignment SET technician_id=13 WHERE order_id=1");}).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
   ServiceNotifications.enqueue(db,1,"DISPUTE_HANDLED","record:current");
  });}finally{pool.shutdownNow();}
  assertEquals(100,jdbc.queryForObject("SELECT owner_id FROM service_notification_outbox",Integer.class));assertEquals(13,jdbc.queryForObject("SELECT technician_id FROM service_notification_outbox",Integer.class));notices.recover();assertEquals(0,inbox(owner).path("unread_count").asInt());assertEquals(1,inbox(outsider).path("unread_count").asInt());assertEquals(0,inbox(tech).path("unread_count").asInt());assertEquals(1,inbox(peer).path("unread_count").asInt());
 }
 @Test void migrationReplayPreservesLegacyEventsAndSourceKeys()throws Exception{
  emit("ORDER_ASSIGN");String original=jdbc.queryForObject("SELECT event_id FROM service_notification_outbox",String.class);
  jdbc.execute("ALTER TABLE service_notification_outbox DROP INDEX uk_service_notification_source, DROP COLUMN source_key, ADD UNIQUE KEY uk_service_notification_source(kind,order_id)");
  try(var c=jdbc.getDataSource().getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of("..","docs","sql","migrations","V047__order_notification_sources.sql")));}
  assertEquals(original,jdbc.queryForObject("SELECT event_id FROM service_notification_outbox",String.class));assertEquals("ONCE",jdbc.queryForObject("SELECT source_key FROM service_notification_outbox",String.class));
  db.transactions.executeWithoutResult(tx->ServiceNotifications.enqueue(db,1,"REFUND_REQUESTED","refund:7:3"));
  var before=jdbc.queryForList("SELECT event_id,kind,owner_id,merchant_id,technician_id,source_key FROM service_notification_outbox ORDER BY id");
  try(var c=jdbc.getDataSource().getConnection()){for(int i=0;i<2;i++)org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of("..","docs","sql","migrations","V047__order_notification_sources.sql")));}
  assertEquals(before,jdbc.queryForList("SELECT event_id,kind,owner_id,merchant_id,technician_id,source_key FROM service_notification_outbox ORDER BY id"));emit("ORDER_ASSIGN");assertEquals(2,count("service_notification_outbox"));
 }
 @Test void newEventFailureRollsBackBusinessFactAndRepeatableKindsNeedSources(){
  jdbc.execute("CREATE TRIGGER reject_r7c_notice BEFORE INSERT ON service_notification_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
  try{assertThrows(RuntimeException.class,()->db.transactions.executeWithoutResult(tx->{jdbc.update("UPDATE `order` SET status='CLOSED' WHERE id=1");ServiceNotifications.enqueue(db,1,"ORDER_CLOSED");}));assertEquals("IN_SERVICE",state());assertEquals(0,count("service_notification_outbox"));}finally{jdbc.execute("DROP TRIGGER reject_r7c_notice");}
  assertThrows(IllegalArgumentException.class,()->emit("REFUND_REQUESTED"));
 }
 @Test void actualAssignmentAcceptanceAndSignatureProduceEventsOnlyOnce(){
  jdbc.update("DELETE FROM technician_assignment");jdbc.update("UPDATE `order` SET status='RECEIVED',assigned_at=NULL WHERE id=1");String k=key();service.assign(shop,k,1,12);service.assign(shop,k,1,12);assertEquals(1,count("service_notification_outbox"));service.accept(tech,key(),1);assertEquals(2,count("service_notification_outbox"));submitted();String sign=key();work.sign(tech,sign,signature());work.sign(tech,sign,signature());assertEquals(3,count("service_notification_outbox"));notices.recover();assertEquals(9,count("notification"));
 }
 @Test void uniqueSourceFanoutAndLegacyIsolation(){
  emit("ORDER_ASSIGN");emit("ORDER_ASSIGN");assertEquals(1,count("service_notification_outbox"));notices.recover();notices.recover();assertEquals(3,count("notification"));assertEquals(1,inbox(owner).path("unread_count").asInt());assertEquals(1,inbox(shop).path("unread_count").asInt());assertEquals(1,inbox(tech).path("unread_count").asInt());
  jdbc.update("INSERT INTO notification(event_id,recipient_type,recipient_id,template_key,payload) VALUES('legacy','user',99,'ORDER_ASSIGN',JSON_OBJECT('phone','private'))");assertEquals(1,inbox(owner).path("notifications").path("total").asInt());for(String secret:List.of("user_id","payload","phone","merchant_id","technician_id","vehicle_id","event_id"))assertFalse(inbox(owner).toString().contains("\""+secret+"\""));
 }
 @Test void concurrentWorkersDeliverOneAtomicFanout()throws Exception{
  emit("ORDER_ASSIGN");long id=jdbc.queryForObject("SELECT id FROM service_notification_outbox",Long.class);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var one=pool.submit(()->{start.await();return notices.deliver(id);});var two=pool.submit(()->{start.await();return new ServiceNotifications(db,"test-app").deliver(id);});start.countDown();assertNotEquals(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS));assertEquals(3,count("notification"));}finally{pool.shutdownNow();}
 }
 @Test void failedFanoutRollsBackAndRestartRecoversWithoutBlockingLaterEvents(){
  emit("ORDER_ASSIGN");emit("ORDER_TECH_ACCEPT");jdbc.execute("CREATE TRIGGER reject_r7c_notice BEFORE INSERT ON notification FOR EACH ROW BEGIN IF NEW.template_key='ORDER_ASSIGN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'; END IF; END");notices.recover();assertEquals(3,count("notification"));assertEquals(1,jdbc.queryForObject("SELECT attempts FROM service_notification_outbox WHERE kind='ORDER_ASSIGN'",Integer.class));assertEquals("DELIVERY_UNAVAILABLE",jdbc.queryForObject("SELECT last_error FROM service_notification_outbox WHERE kind='ORDER_ASSIGN'",String.class));
  jdbc.execute("DROP TRIGGER reject_r7c_notice");jdbc.update("UPDATE service_notification_outbox SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");new ServiceNotifications(db,"test-app").recover();assertEquals(6,count("notification"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM service_notification_outbox WHERE status='DONE'",Integer.class));
 }
 @Test void scopesAreRevalidatedForListAndReadIncludingCachedWrites(){
  emit("ORDER_ASSIGN");notices.recover();long id=notice(owner);String k=key();notices.read(owner,id,k);jdbc.update("UPDATE `order` SET user_id=100 WHERE id=1");assertEquals(0,inbox(owner).path("unread_count").asInt());assertEquals(0,inbox(outsider).path("notifications").path("total").asInt());assertEquals(404,status(()->notices.read(owner,id,k)));assertEquals(0,inbox(peer).path("notifications").path("total").asInt());assertEquals(0,inbox(other).path("notifications").path("total").asInt());jdbc.update("UPDATE technician_assignment SET technician_id=13");assertEquals(0,inbox(tech).path("notifications").path("total").asInt());assertEquals(0,inbox(peer).path("notifications").path("total").asInt());
 }
 @Test void disabledSessionsAndBindingsDenyHistoryAndRead(){
  emit("ORDER_ASSIGN");notices.recover();long id=notice(tech);jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED',unbound_at=UTC_TIMESTAMP() WHERE id=112");assertEquals(401,status(()->notices.list(tech,1)));assertEquals(401,status(()->notices.read(tech,id,key())));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->notices.list(owner,1)));
 }
 @Test void readIsIdempotentAuditedAndCannotAcknowledgeAnotherRecipient(){
  emit("ORDER_ASSIGN");notices.recover();long id=notice(owner);String k=key();int audits=count("audit_log");var receipt=notices.read(owner,id,k);assertEquals(receipt,notices.read(owner,id,k));String at=jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",String.class,id);notices.read(owner,id,key());assertEquals(at,jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",String.class,id));assertEquals(audits+1,count("audit_log"));assertEquals(0,inbox(owner).path("unread_count").asInt());assertEquals(404,status(()->notices.read(shop,id,key())));assertEquals(404,status(()->notices.read(outsider,id,key())));
 }
 @Test void auditFailureRollsBackReadTimestamp(){
  emit("ORDER_ASSIGN");notices.recover();long id=notice(owner);jdbc.execute("CREATE TRIGGER reject_r7c_notice BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");assertEquals(503,status(()->notices.read(owner,id,key())));assertNull(jdbc.queryForObject("SELECT read_at FROM notification WHERE id=?",java.sql.Timestamp.class,id));assertEquals(1,inbox(owner).path("unread_count").asInt());
 }
 @Test void paginationAndClosedOrderFactsRemainMinimal(){
  for(int i=2;i<=22;i++){jdbc.update("INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status) VALUES(?,?,99,99,1,10,10,'COMPLETED')",i,"synthetic-notice-"+i);final int id=i;db.transactions.executeWithoutResult(tx->ServiceNotifications.enqueue(db,id,"ORDER_COMPLETE"));}notices.recover();notices.recover();var first=inbox(owner);assertEquals(21,first.path("notifications").path("total").asInt());assertEquals(20,first.path("notifications").path("items").size());assertEquals(1,db.mapper.valueToTree(notices.list(owner,2)).path("notifications").path("items").size());jdbc.update("UPDATE `order` SET is_deleted=1 WHERE id=22");assertEquals(20,inbox(owner).path("notifications").path("total").asInt());
 }
}
