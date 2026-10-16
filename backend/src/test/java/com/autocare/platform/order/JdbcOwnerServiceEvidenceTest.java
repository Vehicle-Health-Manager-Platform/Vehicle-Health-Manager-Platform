package com.autocare.platform.order;

import com.autocare.platform.vehicle.VehicleOwner;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOwnerServiceEvidenceTest extends JdbcServiceWorkTest {
    OwnerServiceEvidence evidence;VehicleOwner owner,outsider;
    @BeforeEach void owners(){
        jdbc.update("DELETE FROM `user`");jdbc.update("INSERT INTO `user`(id,openid) VALUES(99,'synthetic-owner'),(100,'synthetic-other')");
        owner=owner(99);outsider=owner(100);evidence=new OwnerServiceEvidence(db);
        jdbc.update("UPDATE pickup_check SET arrived_at=UTC_TIMESTAMP() WHERE order_id=1");
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(201,'staff_account',1,'synthetic-before','image/jpeg',100,'CLEAN')");
        jdbc.update("INSERT INTO pickup_check_file(pickup_check_id,photo_slot,file_id) SELECT id,'FRONT',201 FROM pickup_check WHERE order_id=1");
    }
    VehicleOwner owner(long id){String s=key();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',?,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",s,id,key());return new VehicleOwner(id,s,Instant.now().plusSeconds(3600));}
    com.fasterxml.jackson.databind.JsonNode view(){return db.mapper.valueToTree(evidence.detail(owner,1));}
    com.fasterxml.jackson.databind.JsonNode group(String kind){for(var g:view().path("groups"))if(g.path("kind").asText().equals(kind))return g;throw new AssertionError(kind);}
    @Test void ownerProjectionIsReadOnlyAndDoesNotDelegateIdentity(){
        submitted();work.sign(tech,key(),signature());int audits=count("audit_log"),writes=count("idempotency_record");var n=view();
        assertEquals("SIGNED",n.path("report_state").asText());assertEquals(6,n.path("groups").size());assertEquals(45,n.path("report").path("work_minutes").asInt());
        assertTrue(group("PICKUP").path("photos").get(0).path("available").asBoolean());
        assertEquals(12,evidence.fileOwner(owner,1,102).id());assertEquals(1,evidence.fileOwner(owner,1,201).id());
        for(String key:List.of("technician_id","staff_id","owner_id","user_id","object_key","openid","verify_code","phone","vin"))assertFalse(n.toString().contains("\""+key+"\""));
        assertEquals(audits,count("audit_log"));assertEquals(writes,count("idempotency_record"));
    }
    @Test void otherOwnerDeletedOrderAndCurrentOwnershipChangeRejectBothReads(){
        assertEquals(404,status(()->evidence.detail(outsider,1)));assertEquals(404,status(()->evidence.fileOwner(outsider,1,201)));
        jdbc.update("UPDATE `order` SET user_id=100 WHERE id=1");assertEquals(404,status(()->evidence.detail(owner,1)));assertEquals(404,status(()->evidence.fileOwner(owner,1,201)));
        assertEquals(1L,evidence.detail(outsider,1).get("order_id"));jdbc.update("UPDATE `order` SET is_deleted=1 WHERE id=1");assertEquals(404,status(()->evidence.detail(outsider,1)));
    }
    @Test void revokedSessionAndDisabledOwnerCannotReadHistoricalEvidence(){
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->evidence.fileOwner(owner,1,201)));
        owner=owner(99);jdbc.update("UPDATE `user` SET status=2 WHERE id=99");assertEquals(401,status(()->evidence.detail(owner,1)));
    }
    @Test void missingAndLegacyReportsNeverBecomeTrustedEvidence(){
        assertEquals("MISSING",view().path("report_state").asText());assertEquals("MISSING",group("PROCESS").path("state").asText());
        submitted();jdbc.update("DELETE FROM service_report_submission");assertEquals("UNKNOWN",view().path("report_state").asText());assertTrue(view().path("report").isNull());assertEquals(404,status(()->evidence.fileOwner(owner,1,102)));
    }
    @Test void eachPhotoRechecksCurrentScanOwnerMetadataAndRecordRelationship(){
        submitted();assertTrue(group("PROCESS").path("photos").get(0).path("available").asBoolean());
        for(String mutation:List.of("scan_status='INFECTED'","is_deleted=1","owner_id=14","content_type='application/pdf'","size_bytes=10485761")){
            jdbc.update("UPDATE file_object SET "+mutation+" WHERE id=102");assertFalse(group("PROCESS").path("photos").get(0).path("available").asBoolean());assertEquals(404,status(()->evidence.fileOwner(owner,1,102)));
            jdbc.update("UPDATE file_object SET scan_status='CLEAN',is_deleted=0,owner_id=12,content_type='image/png',size_bytes=100 WHERE id=102");
        }
        jdbc.update("UPDATE service_evidence_file SET record_id=999 WHERE file_id=102");assertEquals(404,status(()->evidence.fileOwner(owner,1,102)));
        assertEquals(404,status(()->evidence.fileOwner(owner,1,106)));
    }
    @Test void recordDeletionAndTamperedReportAreUnknownWithoutLosingPickup(){
        submitted();jdbc.update("UPDATE technician_report SET parts_used='[{\"name\":\"part\",\"model\":\"m\",\"brand\":\"b\",\"quantity\":1,\"phone\":\"private\"}]'");
        assertEquals("UNKNOWN",view().path("report_state").asText());assertTrue(view().path("report").isNull());assertEquals(1,evidence.fileOwner(owner,1,201).id());
        jdbc.update("UPDATE pickup_check SET is_deleted=1");assertEquals(404,status(()->evidence.fileOwner(owner,1,201)));
    }
    @Test void signatureRequiresSignedReportAndPngAndKeepsFactsAfterClosing(){
        submitted();assertEquals("MISSING",group("SIGNATURE").path("state").asText());work.sign(tech,key(),signature());assertEquals(12,evidence.fileOwner(owner,1,105).id());
        jdbc.update("UPDATE file_object SET content_type='image/jpeg' WHERE id=105");assertEquals(404,status(()->evidence.fileOwner(owner,1,105)));
        jdbc.update("UPDATE `order` SET status='CLOSED',closed_at=UTC_TIMESTAMP() WHERE id=1");assertEquals("SIGNED",view().path("report_state").asText());assertEquals(12,evidence.fileOwner(owner,1,104).id());
        jdbc.update("UPDATE technician_report SET is_deleted=1");assertEquals("UNKNOWN",view().path("report_state").asText());assertEquals(404,status(()->evidence.fileOwner(owner,1,104)));
    }
    @Test void protectionRequiresOneSourceAndOriginalShopUploader(){
        protectedOrder();assertTrue(group("PROTECTION").path("photos").get(0).path("available").asBoolean());
        jdbc.update("UPDATE staff_account SET merchant_id=2 WHERE id=1");assertEquals(404,status(()->evidence.fileOwner(owner,1,101)));
        jdbc.update("UPDATE staff_account SET merchant_id=1 WHERE id=1");
        jdbc.update("INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) SELECT 1,id,106,'PROTECTION' FROM repair_protection WHERE order_id=1");
        assertEquals("UNKNOWN",group("PROTECTION").path("state").asText());assertEquals(404,status(()->evidence.fileOwner(owner,1,101)));
    }
}
