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
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(i->{String token=i.getArgument(0);boolean merchant=Set.of("merchant","employee").contains(token);var b=Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("jti","mileage-test-session").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).claim("subject_type",merchant||token.equals("staff")?"staff_account":token.equals("binding")?"wechat_binding":"user").claim("role",merchant?(token.equals("employee")?"STAFF":"MERCHANT"):token.equals("staff")?"TECHNICIAN":"OWNER");if(merchant)b.claim("app_id","merchant-account").claim("merchant_id",1L);return b.build();});}
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
    static final String PICKUP="/api/check/pickup/mileage-recognition";
    static final String PICKUP_BODY="{\"order_id\":1,\"file_id\":1,\"consent\":true,\"consent_version\":\"PICKUP_MILEAGE_OCR_V1\"}";
    @Test void pickup_rejects_anonymous_owner_technician_and_binding_before_service()throws Exception{
        mvc.perform(post(PICKUP).contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY)).andExpect(status().isUnauthorized());
        for(String token:List.of("owner","staff","binding"))mvc.perform(post(PICKUP).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void pickup_requires_strict_consent_body_and_no_query()throws Exception{
        for(String body:List.of("{}",PICKUP_BODY.replace("true","false"),PICKUP_BODY.replace("PICKUP_MILEAGE_OCR_V1","MILEAGE_OCR_V1"),PICKUP_BODY.replace("\"order_id\":1","\"order_id\":1,\"order_id\":2"),PICKUP_BODY+"{}"))mvc.perform(post(PICKUP).header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(PICKUP+"?order_id=2").header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY)).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void pickup_manager_and_staff_use_authenticated_shop_and_no_store()throws Exception{
        when(service.recognizePickup(any(),any())).thenReturn(Map.of("order_id",1,"requires_confirmation",true));
        for(String token:List.of("merchant","employee"))mvc.perform(post(PICKUP).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).recognizePickup(argThat(a->a.staffId()==1&&a.merchantId()==1&&a.role().equals("MERCHANT")),any());
        verify(service).recognizePickup(argThat(a->a.staffId()==1&&a.merchantId()==1&&a.role().equals("STAFF")),any());
    }
    @Test void pickup_disabled_or_database_failure_is_not_empty_success()throws Exception{
        when(service.recognizePickup(any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic private database failure"));
        mvc.perform(post(PICKUP).header("Authorization","Bearer merchant").contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50300)).andExpect(header().string("Cache-Control","no-store"));
    }
}
