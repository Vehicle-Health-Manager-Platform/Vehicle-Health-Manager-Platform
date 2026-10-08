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
class ServiceWorkNoDatabaseTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;
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
    @Test void validWritesAndReadsAreUnavailableWithoutDatabase()throws Exception{
        for(var path:List.of("/api/check/protection/upload","/api/tech/report/submit","/api/tech/sign"))mvc.perform(post(path).header("Authorization","Bearer "+(path.contains("protection")?"merchant":"tech")).header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(path.contains("protection")?protection():path.endsWith("sign")?sign():report().value().toString())).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/tech/orders/1/work").header("Authorization","Bearer tech")).andExpect(status().isServiceUnavailable());
    }
    @Test void malformedWritesAndIdsFailBeforeUnconfiguredService()throws Exception{
        mvc.perform(post("/api/tech/sign").header("Authorization","Bearer tech").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());mvc.perform(get("/api/tech/orders/0/work").header("Authorization","Bearer tech")).andExpect(status().isBadRequest());mvc.perform(get("/api/merchant/orders/1/work/files/0/access").header("Authorization","Bearer merchant")).andExpect(status().isBadRequest());
    }
}
