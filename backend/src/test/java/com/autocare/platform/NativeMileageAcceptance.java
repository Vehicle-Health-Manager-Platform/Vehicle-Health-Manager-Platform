package com.autocare.platform;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Private handoff to isolated Devtools. Never loaded by production. */
final class NativeMileageAcceptance {
    static Map<String,Object> run(JdbcTemplate db,ObjectMapper json,int port,String token,byte[] image)throws Exception{
        Path work=RealMileageAcceptance.work(),ready=work.resolve("native-ready.private.json");
        var phases=new LinkedHashSet<String>();
        try{
            write(json,ready,Map.of("port",port,"token",token,"image",Base64.getEncoder().encodeToString(image)));
            Path done=work.resolve("native.json");long deadline=System.nanoTime()+Duration.ofMinutes(7).toNanos();
            while(!Files.exists(done)&&System.nanoTime()<deadline){
                for(String phase:List.of("candidate","cancelled","applied")){
                    if(Files.exists(work.resolve("checkpoint-"+phase+".request"))&&phases.add(phase)){
                        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=992",Integer.class));
                        assertEquals(100,db.queryForObject("SELECT current_mileage FROM vehicle WHERE id=992",Integer.class));
                        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_id=992 AND scan_status='CLEAN' AND is_deleted=0",Integer.class));
                        write(json,work.resolve("checkpoint-"+phase+".json"),Map.of("phase",phase,"passed",true));
                    }
                }
                Thread.sleep(100);
            }
            assertTrue(Files.exists(done),"Native mileage runner timed out");var ui=json.readTree(done.toFile());
            for(String flag:List.of("passed","session_cleared","system_api_restored","proxy_closed","local_reference_released"))assertTrue(ui.path(flag).asBoolean(),flag);
            assertEquals(Set.of("candidate","cancelled","applied"),phases);
            assertEquals(1,ui.path("counts").path("upload").asInt());assertEquals(2,ui.path("counts").path("recognition").asInt());assertEquals(1,ui.path("counts").path("archive").asInt());
            var content=json.readTree(db.queryForObject("SELECT content FROM vehicle_archive WHERE vehicle_id=992 AND archive_type=1 AND input_type=3",String.class));
            assertTrue(content.path("mileage").isIntegralNumber());assertEquals(123456,content.path("mileage").asInt());
            assertEquals("synthetic-mileage-native",content.path("title").asText());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=992",Integer.class));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM vehicle_archive_file af JOIN vehicle_archive a ON a.id=af.archive_id JOIN file_object f ON f.id=af.file_id WHERE a.vehicle_id=992 AND f.owner_type='user' AND f.owner_id=992 AND f.scan_status='CLEAN' AND f.is_deleted=0 AND f.size_bytes=?",Integer.class,image.length));
            assertEquals(100,db.queryForObject("SELECT current_mileage FROM vehicle WHERE id=992",Integer.class));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=992",Integer.class));
            long file=db.queryForObject("SELECT id FROM file_object WHERE owner_type='user' AND owner_id=992",Long.class);
            var access=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/file/"+file+"/access")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,access.statusCode());
            var download=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(json.readTree(access.body()).path("data").path("url").asText())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,download.statusCode());assertArrayEquals(image,download.body());
            return Map.of("passed",true,"checkpoints",phases,"zero_archives_before_save",true,"one_archive_saved",true,"correct_mileage_and_file",true,"vehicle_mileage_unchanged",true,"signed_download_matches",true,"no_input_material_created",true);
        }finally{Files.deleteIfExists(ready);}
    }
    static void write(ObjectMapper json,Path target,Object value)throws Exception{
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");json.writeValue(temporary.toFile(),value);Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
    }
}
