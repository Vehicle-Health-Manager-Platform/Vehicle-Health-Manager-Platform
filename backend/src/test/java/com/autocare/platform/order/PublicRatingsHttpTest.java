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
class PublicRatingsHttpTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@MockitoBean JwtDecoder decoder;@MockitoBean PublicRatings service;
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(call->{String token=call.getArgument(0);return Jwt.withTokenValue(token).header("alg","HS256").subject("1").claim("subject_type",token.equals("owner")?"user":"staff_account").claim("role",token.equals("owner")?"OWNER":"MERCHANT").claim("jti","session").expiresAt(Instant.now().plusSeconds(600)).build();});}
    String key(){return UUID.randomUUID().toString();}
    @Test void rolesAndAnonymousCannotUseEndpoints()throws Exception{
        for(String path:List.of("/api/order/1/review-publication","/api/service/merchant/1/ratings")){mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer merchant")).andExpect(status().isForbidden());}
        mvc.perform(put("/api/order/1/review-publication").header("Authorization","Bearer merchant").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"publish\":true,\"expected_version\":0}")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void strictBodyKeyPathAndQueries()throws Exception{
        for(String body:List.of("{}","null","[]","{\"publish\":true,\"expected_version\":0}{}","{\"publish\":true,\"publish\":false,\"expected_version\":0}","{\"publish\":true,\"expected_version\":0,\"user_id\":2}","{\"publish\":1,\"expected_version\":0}","{\"publish\":true,\"expected_version\":-1}","{\"publish\":true,\"expected_version\":1.0}","{\"publish\":true,\"expected_version\":9007199254740992}"))mvc.perform(put("/api/order/1/review-publication").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for(String path:List.of("/api/order/0/review-publication","/api/order/1/review-publication?publish=true","/api/service/merchant/1/ratings?page=0","/api/service/merchant/1/ratings?page=1&page=2","/api/service/merchant/1/ratings?page_size=51","/api/service/merchant/1/ratings?user_id=2"))mvc.perform(get(path).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void noStoreSingleEnvelopeAndSafeDatabaseFailure()throws Exception{
        when(service.detail(any(),eq(1L))).thenReturn(Map.of("order_id",1));when(service.update(any(),eq(1L),anyString(),eq(true),eq(0L))).thenReturn(mapper.valueToTree(Map.of("code",0,"data",Map.of("order_id",1))));
        mvc.perform(get("/api/order/1/review-publication").header("Authorization","Bearer owner")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.order_id").value(1));
        mvc.perform(put("/api/order/1/review-publication").header("Authorization","Bearer owner").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON).content("{\"publish\":true,\"expected_version\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.order_id").value(1)).andExpect(jsonPath("$.data.data").doesNotExist());
        when(service.list(any(),eq(1L),eq(1),eq(20))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL"));mvc.perform(get("/api/service/merchant/1/ratings").header("Authorization","Bearer owner")).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").value("公开星级服务暂不可用，请稍后重试"));
    }
}
