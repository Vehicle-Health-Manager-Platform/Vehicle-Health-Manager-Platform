package com.autocare.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional private filesystem handoff only; opens no additional network listener. */
final class NativeMaterialAcceptance {
    static void run(JdbcTemplate jdbc,ObjectMapper mapper,int port,String token,byte[] pdf)throws Exception {
        String id=System.getenv("AUTOCARE_NATIVE_MATERIAL_RUN_ID");assertTrue(id!=null&&id.matches("[0-9a-f]{32}"));
        Path work=Path.of("..",".cache","native-material-"+id).toAbsolutePath().normalize();Files.createDirectories(work);
        Path ready=work.resolve("ready.private.json"),done=work.resolve("native.json");
        try {
            Path temp=work.resolve("ready.tmp");mapper.writeValue(temp.toFile(),Map.of("token",token,"pdf",Base64.getEncoder().encodeToString(pdf),"port",port));
            Files.move(temp,ready,StandardCopyOption.REPLACE_EXISTING);
            long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
            while(!Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(100);
            assertTrue(Files.exists(done),"Native material runner timed out");var ui=mapper.readTree(done.toFile());
            for(String key:List.of("passed","session_cleared","system_api_restored","local_file_released"))assertTrue(ui.path(key).asBoolean(),key);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991 AND vehicle_id=991 AND purpose='ARCHIVE' AND content_type='application/pdf' AND pages=1 AND scan_status='CLEAN' AND is_deleted=0",Integer.class));
            long material=jdbc.queryForObject("SELECT id FROM input_material WHERE owner_id=991",Long.class);
            var client=HttpClient.newHttpClient();
            var access=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/input-materials/ARCHIVE/991/"+material+"/access")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,access.statusCode());URI signed=URI.create(mapper.readTree(access.body()).path("data").path("url").asText());
            var downloaded=client.send(HttpRequest.newBuilder(signed).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,downloaded.statusCode());assertArrayEquals(pdf,downloaded.body());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=991",Integer.class));
            mapper.writeValue(work.resolve("database.json").toFile(),Map.of("passed",true,"one_clean_scoped_pdf",true,"signed_download_matches",true,"no_archive_created",true));
        } finally {Files.deleteIfExists(ready);}
    }
}
