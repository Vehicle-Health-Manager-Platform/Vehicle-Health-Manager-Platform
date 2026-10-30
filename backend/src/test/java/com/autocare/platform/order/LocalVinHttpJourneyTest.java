package com.autocare.platform.order;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual configured local provider, TCP/controller/JWT/MySQL; synthetic data only. */
@TestPropertySource(properties={"VIN_DECODE_ENABLED=true","VIN_DECODER_PROVIDER=LOCAL_LICENSED","VIN_LOCAL_ALLOW_SYNTHETIC=true"})
class LocalVinHttpJourneyTest extends IsolatedCoreJourneysTest {
    static final Path source=fixture();
    static Path fixture(){try{var p=Files.createTempFile("autocare-local-vin-",".json");Files.copy(Path.of("../docs/testing/fixtures/vin-local-synthetic.json"),p,StandardCopyOption.REPLACE_EXISTING);return p;}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception {
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        r.add("VIN_LOCAL_DATA_PATH",()->source.toString());r.add("VIN_LOCAL_DATA_SHA256",()->sha);
    }
    @AfterAll static void removeFixture()throws Exception{Files.deleteIfExists(source);}
    @Test void local_vin_four_kind_http_evidence()throws Exception {
        String path="/api/vehicle/vin-decode";var e=new RecognitionHttpEvidence(this,path);
        String actor=token(1,"user","OWNER","isolated-journeys",null,null);
        var body=Map.of("vin","LTEST123456789012","consent",true,"consent_version","VIN_LOCAL_V1");
        jdbc.update("INSERT INTO brand(id,name) VALUES(901,'合成测试品牌')");jdbc.update("INSERT INTO series(id,brand_id,name) VALUES(901,901,'合成测试车系')");
        jdbc.update("INSERT INTO model(id,series_id,year,config_name) VALUES(901,901,'2026','合成配置A')");
        int vehicles=jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class);
        try {
            var answer=e.send("local_candidate","normal","owner",path,actor,body,200).path("data");
            assertEquals("LOCAL_SYNTHETIC",answer.path("source").asText());assertTrue(answer.path("synthetic").asBoolean());assertEquals(901,answer.path("catalog_candidates").get(0).path("model_id").asInt());
            jdbc.update("UPDATE model SET status=0 WHERE id=901");
            assertTrue(e.send("disabled_catalog","boundary","owner",path,actor,body,200).path("data").path("catalog_candidates").isEmpty());
            e.send("wrong_consent_source","error","owner",path,actor,Map.of("vin","LTEST123456789012","consent",true,"consent_version","VIN_VPIC_V1"),400);
            e.send("query_refused","error","owner",path+"?unexpected=1",actor,body,400);
            e.send("anonymous","unauthorized","anonymous",path,null,body,401);e.send("wrong_role","unauthorized","merchant",path,merchant,body,403);
            Files.writeString(source,"{}");e.send("tampered_source","error","owner",path,actor,body,503);
            assertEquals(vehicles,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle",Integer.class));
            e.finish("local-vin-http-evidence.json",Map.of("synthetic_only",true,"no_vehicle_write",true,"tamper_fails_closed",true));
        } finally {jdbc.update("DELETE FROM model WHERE id=901");jdbc.update("DELETE FROM series WHERE id=901");jdbc.update("DELETE FROM brand WHERE id=901");}
    }
}
