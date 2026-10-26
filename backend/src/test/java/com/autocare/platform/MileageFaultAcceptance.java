package com.autocare.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only, fixed host handshakes, real HTTP failures and real SQL checkpoints. */
final class MileageFaultAcceptance {
    static void control(String name)throws Exception{
        Path work=RealMileageAcceptance.work();Files.writeString(work.resolve("fault-"+name+".request"),name);
        Path answer=work.resolve("fault-"+name+".json");long end=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        while(!Files.exists(answer)&&System.nanoTime()<end)Thread.sleep(50);
        assertTrue(Files.exists(answer),"Fault controller timeout: "+name);
        assertTrue(RealMileageAcceptance.JSON.readTree(answer.toFile()).path("passed").asBoolean());
    }
    static void unchanged(JdbcTemplate db,long file){
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=991",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991",Integer.class));
        assertEquals(100,db.queryForObject("SELECT current_mileage FROM vehicle WHERE id=991",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM file_object WHERE id=? AND owner_id=991 AND scan_status='CLEAN' AND is_deleted=0",Integer.class,file));
    }
    static Map<String,Object> call(int port,String token,Map<String,Object> body,int expected)throws Exception{
        long start=System.nanoTime();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/vehicle/mileage-recognition"))
            .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(RealMileageAcceptance.JSON.writeValueAsBytes(body))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        double elapsed=(System.nanoTime()-start)/1e9;
        assertEquals(expected,response.statusCode());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        JsonNode data=RealMileageAcceptance.JSON.readTree(response.body());
        if(expected==503){assertEquals(50300,data.path("code").asInt());assertFalse(data.path("data").has("recognition"));}
        else{
            var candidate=data.path("data");assertEquals("CANDIDATE",candidate.path("review_status").asText());assertTrue(candidate.path("requires_confirmation").asBoolean());
            assertEquals("123456",candidate.path("recognition").path("fields").get(0).path("value").asText());
        }
        return Map.of("status",expected,"elapsed_seconds",elapsed,"passed",true);
    }
    static void run(JdbcTemplate db,int port,String token,Map<String,Object> body,long file)throws Exception{
        var rows=new LinkedHashMap<String,Object>();unchanged(db,file);
        control("stop-timeout");var timeout=call(port,token,body,503);
        double elapsed=(double)timeout.get("elapsed_seconds");assertTrue(elapsed>=7.5&&elapsed<13,"Production inference deadline");
        rows.put("timeout",timeout);unchanged(db,file);control("ready-timeout");
        rows.put("timeout_recovery",call(port,token,body,200));unchanged(db,file);
        control("stop-exit");ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            var active=executor.submit(()->call(port,token,body,503));control("busy-exit");
            var busy=call(port,token,body,503);assertTrue((double)busy.get("elapsed_seconds")<3);rows.put("busy",busy);unchanged(db,file);
            control("kill-exit");var exited=active.get(10,TimeUnit.SECONDS);assertTrue((double)exited.get("elapsed_seconds")<7.5);rows.put("exit",exited);
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(30,TimeUnit.SECONDS));}
        unchanged(db,file);control("ready-exit");rows.put("exit_recovery",call(port,token,body,200));unchanged(db,file);
        control("pause-transport");
        try{
            var transport=call(port,token,body,503);double seconds=(double)transport.get("elapsed_seconds");
            assertTrue(seconds>=14.5&&seconds<22,"Production HTTP client deadline");rows.put("transport_timeout",transport);unchanged(db,file);
        }finally{control("resume-transport");}
        rows.put("transport_recovery",call(port,token,body,200));unchanged(db,file);
        NativeMileageAcceptance.write(RealMileageAcceptance.JSON,RealMileageAcceptance.work().resolve("gateway-faults.json"),
            Map.of("passed",true,"cases",rows,"sql_checkpoints",8,"no_archive_created",true,"vehicle_mileage_unchanged",true,"owned_image_unchanged",true,"no_input_material_created",true));
    }
}
