package com.autocare.platform.order;

import com.autocare.platform.file.SignedObjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real model, scheduler, Java HTTP/JWT/MySQL. Identity/CLEAN metadata/store are fixtures.
 * Explicitly opt in using the isolated runner; never external user audio or production. */
@EnabledIfEnvironmentVariable(named="ASR_REAL_ACCEPTANCE", matches="true")
@Import(RealAsrModelJourneyTest.Config.class)
class RealAsrModelJourneyTest extends IsolatedCoreJourneysTest {
    static JsonNode configuration() {
        try {
            return new ObjectMapper().readTree(Files.readAllBytes(Path.of(System.getenv("ASR_REAL_CONFIG_FILE"))));
        } catch (Exception e) { throw new IllegalStateException("Private ASR acceptance configuration unavailable"); }
    }
    @DynamicPropertySource static void asr(DynamicPropertyRegistry properties) {
        var config=configuration();
        properties.add("ASR_ENABLED",()->true);
        properties.add("ASR_URL",()->config.path("url").asText());
        properties.add("ASR_SERVICE_TOKEN",()->config.path("token").asText());
        properties.add("ASR_ALLOW_PRIVATE_HTTP",()->true);
        properties.add("ASR_JOB_INTERVAL_MS",()->100);
    }
    @TestConfiguration static class Config {
        @Bean @Primary SignedObjectStore syntheticAudioStore() throws Exception {
            byte[] audio=Files.readAllBytes(Path.of(configuration().path("audio_path").asText()));
            var store=mock(SignedObjectStore.class);when(store.isPrivate()).thenReturn(true);
            when(store.stat(startsWith("synthetic-real-asr/"))).thenReturn(new SignedObjectStore.StoredObject("audio/mpeg",audio.length));
            when(store.read(startsWith("synthetic-real-asr/"),eq(3145728))).thenReturn(audio);
            return store;
        }
    }
    String path(long id){return "/api/recognition/asr/jobs/"+id;}
    long create(int material,String purpose,String plate) throws Exception {
        long vehicle=vehicle(plate);var config=configuration();
        jdbc.update("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(?,1,?,?,?,'audio/mpeg',?,?,0,'CLEAN')",
            material,vehicle,purpose,"synthetic-real-asr/"+material,config.path("audio_bytes").asInt(),config.path("duration_ms").asInt());
        var body=Map.of("vehicle_id",vehicle,"material_id",material,"purpose",purpose,"consent",true,"consent_version","ASR_TRANSCRIBE_V1");
        String replay=key();var created=request("POST","/api/recognition/asr/jobs",owner,replay,body,200);
        assertEquals(created,request("POST","/api/recognition/asr/jobs",owner,replay,body,200));
        return created.path("data").path("job_id").asLong();
    }
    JsonNode awaitState(long id,String state) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(95).toNanos();
        do {var data=get(path(id),owner,200).path("data");if(state.equals(data.path("state").asText()))return data;
            assertFalse(Set.of("FAILED","EXPIRED").contains(data.path("state").asText()),"Unexpected safe task failure");Thread.sleep(100);
        }while(System.nanoTime()<deadline);
        fail("Real ASR task state deadline");return null;
    }
    String health()throws Exception{
        var config=configuration();var url=config.path("url").asText().replace("/transcribe","/health");
        var response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());return mapper.readTree(response.body()).path("recognition").asText();
    }
    void awaitHealth(boolean busy)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(95).toNanos();
        do{if(health().equals("BUSY")==busy)return;Thread.sleep(50);}while(System.nanoTime()<deadline);
        fail("Real provider health deadline");
    }
    String transcript(JsonNode result){
        var texts=new ArrayList<String>();result.path("segments").forEach(row->texts.add(row.path("text").asText()));
        String text=String.join("\n",texts);assertTrue(text.toLowerCase(Locale.ROOT).contains("engine"));assertTrue(text.toLowerCase(Locale.ROOT).contains("brakes"));return text;
    }
    @Test @Timeout(130) void scheduled_real_model_candidate_is_private_and_does_not_write_business()throws Exception{
        long id=create(9101,"AI","粤R10001");get(path(id),foreign,404);get(path(id),merchant,403);get(path(id),null,401);
        var data=awaitState(id,"SUCCEEDED");transcript(data.path("result"));long vehicle=data.path("vehicle_id").asLong();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle));
        var expectedHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(configuration().path("audio_path").asText()))));
        assertEquals(expectedHash,jdbc.queryForObject("SELECT content_sha256 FROM recognition_job WHERE id=?",String.class,id));
        String cancel=key();assertEquals(request("POST",path(id)+"/cancel",owner,cancel,Map.of(),200),request("POST",path(id)+"/cancel",owner,cancel,Map.of(),200));
        assertTrue(get(path(id),owner,200).path("data").path("result").isNull());
    }
    @Test @Timeout(130) void real_archive_transcript_requires_explicit_idempotent_save()throws Exception{
        long id=create(9102,"ARCHIVE","粤R10002");var data=awaitState(id,"SUCCEEDED");long vehicle=data.path("vehicle_id").asLong();String text=transcript(data.path("result"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle));
        var body=Map.of("vehicle_id",vehicle,"archive_type",1,"recorded_date",LocalDate.now(ZoneOffset.UTC).toString(),"mileage",100,"title","Synthetic real ASR acceptance","notes",text,"file_ids",List.of());
        String replay=key();var saved=request("POST","/api/archive/add",owner,replay,body,200);assertEquals(saved,request("POST","/api/archive/add",owner,replay,body,200));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=?",Integer.class,vehicle));
        assertEquals(text,mapper.readTree(jdbc.queryForObject("SELECT content FROM vehicle_archive WHERE vehicle_id=?",String.class,vehicle)).path("notes").asText());
    }
    @Test @Timeout(130) void cancellation_during_real_inference_discards_late_candidate()throws Exception{
        long id=create(9103,"AI","粤R10003");awaitHealth(true);
        request("POST",path(id)+"/cancel",owner,key(),Map.of(),200);awaitHealth(false);
        var data=get(path(id),owner,200).path("data");assertEquals("CANCELLED",data.path("state").asText());assertTrue(data.path("result").isNull());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM recognition_job WHERE id=? AND result_json IS NOT NULL",Integer.class,id));
    }
    @Test @Timeout(130) void revoked_original_session_discards_real_result_even_with_fresh_owner_session()throws Exception{
        long id=create(9104,"AI","粤R10004");awaitHealth(true);String original=owner;
        owner=token(1,"user","OWNER","isolated-journeys",null,null);
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",issuedSessions.get(original));
        get(path(id),original,401);awaitHealth(false);
        var data=awaitState(id,"CANCELLED");assertEquals("AUTH_REVOKED",data.path("error_code").asText());assertTrue(data.path("result").isNull());
    }
}
