package com.autocare.platform.vehicle.vin;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalVinDecoderTest {
    @TempDir Path temp;
    ObjectMapper mapper=new ObjectMapper();
    JsonNode fixture()throws Exception{return mapper.readTree(Files.readString(Path.of("../docs/testing/fixtures/vin-local-synthetic.json")));}
    LocalVinDecoder decoder(JsonNode value,boolean allow)throws Exception {
        Path path=temp.resolve("source.json");byte[] bytes=mapper.writeValueAsBytes(value);Files.write(path,bytes);
        return new LocalVinDecoder(path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),allow);
    }
    @Test void exactCandidateRequiresConsentAndNeverWritesVehicle()throws Exception {
        var decoder=decoder(fixture(),true);var result=decoder.decode("ltest123456789012",2026,true);
        assertEquals("LOCAL_SYNTHETIC",result.source());assertEquals(VinDecoder.Status.CANDIDATE,result.status());assertTrue(result.requiresConfirmation());
        assertEquals("合成配置A",result.fields().get("trim"));assertTrue(decoder.evidence().synthetic());
        assertEquals(VinDecoder.Status.NO_MATCH,decoder.decode("LTEST123456789012",2025,true).status());
        assertEquals(VinDecoder.Status.NO_MATCH,decoder.decode("LXXXXX12345678901",null,true).status());
        assertThrows(VinDecoder.Unavailable.class,()->decoder.decode("LTEST123456789012",null,false));
    }
    @Test void ambiguityNeverSelectsFirstAndDuplicateIdenticalRulesAreHarmless()throws Exception {
        var doc=(com.fasterxml.jackson.databind.node.ObjectNode)fixture();var rules=(com.fasterxml.jackson.databind.node.ArrayNode)doc.path("rules");
        rules.add(rules.get(0).deepCopy());assertEquals(VinDecoder.Status.CANDIDATE,decoder(doc,true).decode("LTEST123456789012",null,true).status());
        ((com.fasterxml.jackson.databind.node.ObjectNode)rules.get(1)).put("trim","其他配置");
        assertEquals(VinDecoder.Status.NO_MATCH,decoder(doc,true).decode("LTEST123456789012",null,true).status());
    }
    @Test void fixtureDisabledExpiredFutureCorruptAndRevokedFilesFailClosed()throws Exception {
        assertThrows(VinDecoder.Unavailable.class,()->decoder(fixture(),false).evidence());
        for(String field:new String[]{"valid_from","valid_until","license_reference","revision"}) {
            var doc=(com.fasterxml.jackson.databind.node.ObjectNode)fixture();doc.put(field,field.equals("valid_from")?"2099-01-01T00:00:00Z":field.equals("valid_until")?"2000-01-01T00:00:00Z":"");
            assertThrows(VinDecoder.Unavailable.class,()->decoder(doc,true).evidence());
        }
        var decoder=decoder(fixture(),true);Files.writeString(temp.resolve("source.json"),"{}");assertThrows(VinDecoder.Unavailable.class,decoder::evidence);
        Files.delete(temp.resolve("source.json"));assertThrows(VinDecoder.Unavailable.class,decoder::evidence);
    }
}
