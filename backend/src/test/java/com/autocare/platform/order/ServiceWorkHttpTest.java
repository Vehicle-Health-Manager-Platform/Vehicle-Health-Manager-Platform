package com.autocare.platform.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc
class ServiceWorkHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean ServiceWork service;@MockitoBean com.autocare.platform.file.PrivateFileAccessService files;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);
        var b=Jwt.withTokenValue(token).header("alg","HS256").subject("12").claim("subject_type",token.equals("owner")?"user":token.equals("binding")?"wechat_binding":"staff_account")
            .claim("role",token.equals("owner")?"OWNER":token.equals("merchant")?"MERCHANT":"TECHNICIAN")
            .claim("app_id",token.equals("merchant")?"merchant-account":token.equals("wrong-app")?"other":"test-app")
            .claim("merchant_id",1).claim("jti","session").expiresAt(Instant.now().plusSeconds(600));
        if(!token.equals("merchant") && !token.equals("missing-binding")){if(token.equals("fraction"))b.claim("binding_id",1.5);else b.claim("binding_id",2);}
        return b.build();});}
    String key(){return UUID.randomUUID().toString();}

    String protection(){return "{\"order_id\":1,\"items\":[\"SEAT_COVER\",\"STEERING_COVER\"],\"photo_file_id\":101}";}
    String sign(){return "{\"order_id\":1,\"signature_file_id\":105}";}
    JsonNodeBody report(){return new JsonNodeBody(mapper.valueToTree(Map.of("order_id",1,"process_photos",List.of(102),"fault_part_photos",List.of(),"finish_photos",List.of(104),"no_fault_parts",true,"repair_plan","施工","fault_analysis","无故障件","parts_used",List.of(),"no_parts",true,"work_hours",45)));}
    record JsonNodeBody(com.fasterxml.jackson.databind.JsonNode value){}
    @Test void rolesAndBindingsAreCheckedBeforeService()throws Exception{
        for(String token:List.of("owner","merchant","binding","wrong-app","fraction","missing-binding"))mvc.perform(post("/api/tech/sign").header("Authorization","Bearer "+token).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(sign())).andExpect(status().isForbidden());
        mvc.perform(post("/api/check/protection/upload").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(protection())).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictShapeKeyAndUnknownQueryAreRejected()throws Exception{
        for(String b:List.of("{}","null","[]","{\"order_id\":1,\"signature_file_id\":\"105\"}","{\"order_id\":1.5,\"signature_file_id\":105}","{\"order_id\":1,\"signature_file_id\":105,\"status\":\"PENDING_VERIFY\"}"))mvc.perform(post("/api/tech/sign").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tech/sign").header("Authorization","Bearer tech").contentType(MediaType.APPLICATION_JSON).content(sign())).andExpect(status().isBadRequest());mvc.perform(get("/api/tech/orders/1/work?technician_id=12").header("Authorization","Bearer tech")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void reportRejectsMissingEvidenceAmbiguousAbsenceAndBounds()throws Exception{
        for(String field:List.of("process_photos","finish_photos","repair_plan","fault_analysis","parts_used","work_hours","no_parts","no_fault_parts")){
            var b=(com.fasterxml.jackson.databind.node.ObjectNode)report().value();b.remove(field);mvc.perform(post("/api/tech/report/submit").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b.toString())).andExpect(status().isBadRequest());}
        for(String variant:List.of("hours","duplicate","fault","parts","oversize","identity")){
            var b=(com.fasterxml.jackson.databind.node.ObjectNode)report().value();switch(variant){case "hours"->b.put("work_hours",1441);case "duplicate"->b.set("finish_photos",mapper.valueToTree(List.of(102)));case "fault"->b.put("no_fault_parts",false);case "parts"->b.put("no_parts",false);case "oversize"->b.set("process_photos",mapper.valueToTree(List.of(1,2,3,4,5,6,7,8,9,10)));default->b.put("technician_id",12);}
            mvc.perform(post("/api/tech/report/submit").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b.toString())).andExpect(status().isBadRequest());}verifyNoInteractions(service);
    }
    @Test void protectionRequiresSeatAndSteeringCoverAndFileId()throws Exception{
        for(String b:List.of("{\"order_id\":1,\"items\":[\"SEAT_COVER\"],\"photo_file_id\":101}","{\"order_id\":1,\"items\":[\"SEAT_COVER\",\"SEAT_COVER\"],\"photo_file_id\":101}","{\"order_id\":1,\"items\":[\"SEAT_COVER\",\"STEERING_COVER\"],\"photo_file_id\":0}"))mvc.perform(post("/api/check/protection/upload").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void allWritesPreserveReceiptAndNoStore()throws Exception{
        var receipt=mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1)));when(service.protect(any(),anyString(),any())).thenReturn(receipt);when(service.submit(any(),anyString(),any())).thenReturn(receipt);when(service.sign(any(),anyString(),any())).thenReturn(receipt);
        for(var path:List.of("/api/check/protection/upload","/api/tech/report/submit","/api/tech/sign"))mvc.perform(post(path).header("Authorization","Bearer "+(path.contains("protection")?"merchant":"tech")).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(path.contains("protection")?protection():path.endsWith("sign")?sign():report().value().toString())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.order_id").value(1));
    }
    @Test void readsAndAssociatedFileAccessUseCorrectActors()throws Exception{
        when(service.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));when(service.fileOwner(any(),eq(1L),eq(101L))).thenReturn(new com.autocare.platform.file.FileMetadataRepository.Actor("staff_account",12));when(files.sign(any(),eq(101L))).thenReturn(new com.autocare.platform.file.PrivateFileAccessService.SignedAccess("https://test.invalid/signed",Instant.now().plusSeconds(120)));
        for(String prefix:List.of("merchant","tech"))for(String suffix:List.of("","/files/101/access"))mvc.perform(get("/api/"+prefix+"/orders/1/work"+suffix).header("Authorization","Bearer "+prefix)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).fileOwner(isA(com.autocare.platform.service.MerchantActor.class),eq(1L),eq(101L));verify(service).fileOwner(isA(TechnicianActor.class),eq(1L),eq(101L));
    }
    @Test void businessFailureKeepsCodeAndIsNotCached()throws Exception{
        when(service.submit(any(),anyString(),any())).thenThrow(new FulfillmentConflict(43002,"请先完成防护"));mvc.perform(post("/api/tech/report/submit").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(report().value().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(43002)).andExpect(header().string("Cache-Control","no-store"));
    }
}
