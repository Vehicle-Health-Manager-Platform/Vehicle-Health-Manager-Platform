package com.autocare.platform.merchant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"})
@AutoConfigureMockMvc
class MerchantStaffHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean MerchantStaff staff;
    @MockitoBean MerchantProfile profile;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach void tokens(){
        when(decoder.decode(anyString())).thenAnswer(call->{
            String token=call.getArgument(0);
            String type=token.equals("owner")?"user":token.equals("technician")?"staff_account":"staff_account";
            String role=token.equals("owner")?"OWNER":token.equals("technician")?"TECHNICIAN":token.equals("staff")?"STAFF":"MERCHANT";
            var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("jti","session")
                .expiresAt(Instant.now().plusSeconds(900)).claim("subject_type",type).claim("role",role);
            if(!"owner".equals(token)){
                builder=builder.claim("app_id",token.equals("technician")?"test-app":"merchant-account").claim("merchant_id",1);
                if("technician".equals(token)) builder=builder.claim("binding_id",7);
            }
            return builder.build();
        });
    }

    String manager(){return "Bearer manager";}
    String staffToken(){return "Bearer staff";}
    String key(){return UUID.randomUUID().toString();}
    String create(){return "{\"role\":\"STAFF\",\"display_name\":\"前台小李\",\"phone\":\"13800000000\",\"password\":\"passw0rd1\"}";}
    String technician(){return "{\"role\":\"TECHNICIAN\",\"display_name\":\"机修小张\",\"password\":\"passw0rd2\"}";}
    String profileBody(){return "{\"name\":\"演示汽修厂\",\"address\":\"广州市天河区演示路1号\",\"contact_phone\":\"13800000000\",\"lng\":113.2644,\"lat\":23.1291}";}

    @Test void anonymousAndNonStorefrontTokensAreRejected() throws Exception {
        mvc.perform(get("/api/merchant/staff")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/merchant/profile")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/merchant/staff").header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(create())).andExpect(status().isUnauthorized());
        for(String token:List.of("owner","technician")){
            mvc.perform(get("/api/merchant/staff").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(get("/api/merchant/profile").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            mvc.perform(post("/api/merchant/staff").header("Authorization","Bearer "+token).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(create())).andExpect(status().isForbidden());
            mvc.perform(post("/api/merchant/staff/1/disable").header("Authorization","Bearer "+token).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/merchant/staff/1/employee-code").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
            mvc.perform(delete("/api/merchant/staff/1/employee-code").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
            mvc.perform(put("/api/merchant/profile").header("Authorization","Bearer "+token).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(profileBody())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(staff,profile);
    }

    @Test void queryAndPathParametersAreStrict() throws Exception {
        mvc.perform(get("/api/merchant/staff").header("Authorization",manager()).param("unknown","1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/staff").header("Authorization",manager()).param("role","MERCHANT")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/staff").header("Authorization",manager()).param("role","STAFF","role","TECHNICIAN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/staff").header("Authorization",manager()).param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/staff").header("Authorization",manager()).param("page_size","51")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/staff").header("Authorization",manager()).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content(create()).param("page","1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/staff/0/disable").header("Authorization",manager()).header("Idempotency-Key",key())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/staff/1/disable").header("Authorization",manager())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/staff/1/disable").header("Authorization",manager()).header("Idempotency-Key","not-a-uuid")
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/merchant/staff/1/employee-code").header("Authorization",manager()).param("page","1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/merchant/profile").header("Authorization",manager()).param("page","1")).andExpect(status().isBadRequest());
        verifyNoInteractions(staff,profile);
    }

    @Test void strictStaffBodyRejectedBeforeService() throws Exception {
        List<String> invalid=List.of(
            "{}",
            create()+"{}",
            create().replace("\"role\":\"STAFF\"","\"role\":\"MERCHANT\""),
            create().replace("\"role\":\"STAFF\"","\"role\":\"OWNER\""),
            create().replace("\"display_name\":\"前台小李\"","\"display_name\":\"李\""),
            create().replace("\"display_name\":\"前台小李\"","\"display_name\":\" 前台小李\""),
            create().replace("\"display_name\":\"前台小李\"","\""+ "前".repeat(33) +"\""),
            create().replace("\"password\":\"passw0rd1\"","\"password\":\"short1\""),
            create().replace("\"password\":\"passw0rd1\"","\"password\":\"onlyletters\""),
            create().replace("\"password\":\"passw0rd1\"","\"password\":\"12345678\""),
            create().replace("\"password\":\"passw0rd1\"","\"password\":\"pass w0rd1\""),
            create().replace("\"phone\":\"13800000000\"","\"phone\":\"1380000000\""),
            create().replace("\"phone\":\"13800000000\"","\"phone\":\"23800000000\""),
            create().replace(",\"phone\":\"13800000000\"",""),
            create().replace("\"role\":\"STAFF\"","\"role\":\"STAFF\",\"extra\":1"),
            create().replace("\"role\":\"STAFF\"","\"role\":\"STAFF\",\"role\":\"TECHNICIAN\""),
            technician().replace("\"role\":\"TECHNICIAN\"","\"role\":\"TECHNICIAN\",\"phone\":\"1380000000\""),
            "[]");
        for(String body:invalid)
            mvc.perform(post("/api/merchant/staff").header("Authorization",manager()).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(staff,profile);
    }

    @Test void strictProfileBodyRejectedBeforeService() throws Exception {
        List<String> invalid=List.of(
            "{}",
            profileBody()+"{}",
            profileBody().replace(",\"lat\":23.1291",""),
            profileBody().replace("\"address\":","\"merchant_type\":3,\"address\":"),
            profileBody().replace("\"lng\":113.2644","\"lng\":181"),
            profileBody().replace("\"lat\":23.1291","\"lat\":-91"),
            profileBody().replace("\"lng\":113.2644","\"lng\":\"113.2644\""),
            profileBody().replace("\"contact_phone\":\"13800000000\"","\"contact_phone\":\"1380000000\""),
            profileBody().replace("\"name\":\"演示汽修厂\"","\"name\":\"厂\""),
            profileBody().replace("\"name\":\"演示汽修厂\"","\"name\":\" 演示汽修厂\""),
            "[]");
        for(String body:invalid)
            mvc.perform(put("/api/merchant/profile").header("Authorization",manager()).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(staff,profile);
    }

    @Test void employeeCodeEndpointsDoNotDemandIdempotencyKeyAndKeepSingleEnvelope() throws Exception {
        when(staff.issueCode(any(),eq(5L))).thenReturn(java.util.Map.of("staff_id",5L,"employee_code","rotated-code","single_use",true));
        when(staff.revokeCode(any(),eq(5L))).thenReturn(java.util.Map.of("staff_id",5L,"employee_code_revoked",true));
        // 轮换语义：不带 Idempotency-Key 也必须成功，且响应是单层信封。
        mvc.perform(post("/api/merchant/staff/5/employee-code").header("Authorization",manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.employee_code").value("rotated-code"))
            .andExpect(jsonPath("$.data.data").doesNotExist());
        mvc.perform(delete("/api/merchant/staff/5/employee-code").header("Authorization",manager()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.employee_code_revoked").value(true));
    }

    @Test void writeScopesReturnStoredEnvelopeAsIs() throws Exception {
        var envelope=com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .createObjectNode();
        envelope.put("code",0);envelope.put("message","success");envelope.put("request_id","11111111-1111-1111-1111-111111111111");
        var data=envelope.putObject("data");data.put("staff_id",9);data.put("account","s1-1");data.put("role","STAFF");
        data.put("display_name","前台小李");data.put("phone_masked","138****0000");data.put("status","ACTIVE");
        data.put("employee_code_issued",false);data.put("wechat_bound",false);data.put("created_at","2026-10-11T00:00:00Z");
        when(staff.create(any(),anyString(),any())).thenReturn(envelope);
        mvc.perform(post("/api/merchant/staff").header("Authorization",manager()).header("Idempotency-Key",key())
                .contentType(MediaType.APPLICATION_JSON).content(create()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.account").value("s1-1"))
            .andExpect(jsonPath("$.data.data").doesNotExist())
            .andExpect(jsonPath("$.data.code").doesNotExist());
    }
}
