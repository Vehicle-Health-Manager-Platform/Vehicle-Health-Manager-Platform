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
    static byte[] image()throws Exception {
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0,0,0x0b6b43);var bytes=new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(image,"png",bytes));return bytes.toByteArray();
    }
    static byte[] audio()throws Exception {
        try(var input=NativeMaterialAcceptance.class.getResourceAsStream("/fixtures/native-tone.mp3")){assertNotNull(input);return input.readAllBytes();}
    }
    static void run(JdbcTemplate jdbc,ObjectMapper mapper,int port,String token,byte[] payload)throws Exception {
        String kind=System.getenv().getOrDefault("AUTOCARE_NATIVE_UPLOAD_KIND","pdf");
        assertTrue(Set.of("pdf","image","audio").contains(kind));boolean image=kind.equals("image"),audio=kind.equals("audio");
        int duration=audio?new com.autocare.platform.file.InputMaterialValidator().validate("native.mp3",new java.io.ByteArrayInputStream(payload)).durationMs():0;
        String id=System.getenv("AUTOCARE_NATIVE_MATERIAL_RUN_ID");assertTrue(id!=null&&id.matches("[0-9a-f]{32}"));
        Path work=Path.of("..",".cache","native-material-"+id).toAbsolutePath().normalize();Files.createDirectories(work);
        Path ready=work.resolve("ready.private.json"),done=work.resolve("native.json");
        try {
            Path temp=work.resolve("ready.tmp");mapper.writeValue(temp.toFile(),Map.of("token",token,kind,Base64.getEncoder().encodeToString(payload),"port",port,"kind",kind,"duration_ms",duration));
            Files.move(temp,ready,StandardCopyOption.REPLACE_EXISTING);
            long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();
            while(!Files.exists(done)&&System.nanoTime()<deadline)Thread.sleep(100);
            assertTrue(Files.exists(done),"Native material runner timed out");var ui=mapper.readTree(done.toFile());
            for(String key:List.of("passed","session_cleared","system_api_restored","local_file_released"))assertTrue(ui.path(key).asBoolean(),key);
            assertEquals(kind,ui.path("kind").asText());
            if(audio){assertTrue(ui.path("audio_fixture_restored").asBoolean());assertTrue(ui.path("audio").path("playerPlayEvents").asInt()>0);assertEquals(0,ui.path("audio").path("playerErrorEvents").asInt());}
            long material;
            if(image){
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_type='user' AND owner_id=991",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_type='user' AND owner_id=991 AND content_type='image/png' AND scan_status='CLEAN' AND is_deleted=0 AND size_bytes=?",Integer.class,payload.length));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991",Integer.class));
                material=jdbc.queryForObject("SELECT id FROM file_object WHERE owner_type='user' AND owner_id=991",Long.class);
            }else{
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991",Integer.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM input_material WHERE owner_id=991 AND vehicle_id=991 AND purpose='ARCHIVE' AND content_type=? AND pages=? AND duration_ms=? AND size_bytes=? AND scan_status='CLEAN' AND is_deleted=0",Integer.class,audio?"audio/mpeg":"application/pdf",audio?0:1,duration,payload.length));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM file_object WHERE owner_id=991",Integer.class));
                material=jdbc.queryForObject("SELECT id FROM input_material WHERE owner_id=991",Long.class);
            }
            var client=HttpClient.newHttpClient();
            String accessPath=image?"/api/file/"+material+"/access":"/api/input-materials/ARCHIVE/991/"+material+"/access";
            var access=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+accessPath)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,access.statusCode());URI signed=URI.create(mapper.readTree(access.body()).path("data").path("url").asText());
            var downloaded=client.send(HttpRequest.newBuilder(signed).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,downloaded.statusCode());assertArrayEquals(payload,downloaded.body());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=991",Integer.class));
            mapper.writeValue(work.resolve("database.json").toFile(),Map.of("passed",true,image?"one_clean_owned_png":audio?"one_clean_scoped_audio":"one_clean_scoped_pdf",true,"signed_download_matches",true,"no_archive_created",true,"no_cross_kind_material",true,"duration_ms",duration));
        } finally {Files.deleteIfExists(ready);}
    }
}
