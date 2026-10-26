package com.autocare.platform;
import com.autocare.platform.order.MileageRecognition;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class MileageRecognitionHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean MileageRecognition service;
    static final String PATH="/api/vehicle/mileage-recognition";
    static final String BODY="{\"vehicle_id\":101,\"file_id\":1,\"consent\":true,\"consent_version\":\"MILEAGE_OCR_V1\"}";
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(i->{String token=i.getArgument(0);return Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("jti","mileage-test-session").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).claim("subject_type",token.equals("staff")?"staff_account":token.equals("binding")?"wechat_binding":"user").claim("role",token.equals("staff")?"TECHNICIAN":"OWNER").build();});}
    @Test void anonymous_staff_and_binding_are_rejected_before_service()throws Exception{
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        for(String token:List.of("staff","binding"))mvc.perform(post(PATH).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void strict_body_consent_and_query_are_checked_before_service()throws Exception{
        for(String body:List.of("{}",BODY.replace("true","false"),BODY.replace("101","0"),BODY.replace("}",",\"url\":\"remote\"}")))mvc.perform(post(PATH).header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH+"?vehicle_id=2").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void owner_response_is_no_store_and_uses_authenticated_subject()throws Exception{
        when(service.recognize(any(),any())).thenReturn(Map.of("requires_confirmation",true));
        mvc.perform(post(PATH).header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.requires_confirmation").value(true));
        verify(service).recognize(argThat(a->a.id()==1&&a.session().equals("mileage-test-session")),any());
    }
    @Test void unavailable_is_not_reported_as_empty_success()throws Exception{
        when(service.recognize(any(),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"识别不可用"));
        mvc.perform(post(PATH).header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
    }
}
