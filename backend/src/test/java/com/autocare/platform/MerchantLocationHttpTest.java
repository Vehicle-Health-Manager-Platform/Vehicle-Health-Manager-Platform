package com.autocare.platform;

import com.autocare.platform.merchant.MerchantLocation;
import com.autocare.platform.service.*;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class MerchantLocationHttpTest {
    @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean NearbyMerchants nearby;@MockitoBean MerchantLocation locations;
    final String point="{\"lng\":113,\"lat\":22,\"coordinate_system\":\"GCJ02\"}";
    final String store="{\"lng\":\"113\",\"lat\":\"22\",\"coordinate_system\":\"GCJ02\",\"source\":\"MERCHANT_CONFIRMED\"}";
    @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String role=c.getArgument(0);return Jwt.withTokenValue(role).header("alg","HS256").subject("1").claim("jti","session")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).claim("subject_type",role.equals("OWNER")?"user":"staff_account").claim("role",role).claim("app_id",role.equals("OWNER")?"test":"merchant-account").claim("merchant_id",1L).build();});}
    @Test void anonymousAndWrongRoleRejectBeforeReadServices() throws Exception {
        mvc.perform(post("/api/service/merchant/1/distance").contentType("application/json").content(point)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/service/merchant/1/distance").header("Authorization","Bearer STAFF").contentType("application/json").content(point)).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/location").header("Authorization","Bearer OWNER")).andExpect(status().isForbidden());verifyNoInteractions(nearby,locations);
    }
    @Test void strictPointRejectsDuplicateTrailingUnknownRangeAndCoordinateSystem() throws Exception {
        for(String raw:List.of(point.replace("113","181"),point.replace("22","91"),point.replace("113","\"113\""),point.replace("GCJ02","WGS84"),point.replace("113","1e400"),point.replace("{","{\"lng\":1,"),point+" {}",point.replace("}",",\"private\":1}")))
            mvc.perform(post("/api/service/merchant/1/distance").header("Authorization","Bearer OWNER").contentType("application/json").content(raw)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/service/merchant/1/distance?lng=113").header("Authorization","Bearer OWNER").contentType("application/json").content(point)).andExpect(status().isBadRequest());verifyNoInteractions(nearby);
    }
    @Test void strictNearbyPageSortAndRegionRejectBeforeDatabase() throws Exception {
        String raw=point.replace("}",",\"sort\":\"distance_asc\",\"page\":1,\"page_size\":20,\"region_code\":null}");
        for(String b:List.of(raw.replace("distance_asc","rating_desc"),raw.replace("\"page\":1","\"page\":0"),raw.replace("\"page\":1","\"page\":1.5"),raw.replace("\"page\":1","\"page\":4294967297"),raw.replace("\"page_size\":20","\"page_size\":101"),raw.replace("null","\"440305 \"")))
            mvc.perform(post("/api/service/project/11/nearby-merchants").header("Authorization","Bearer OWNER").contentType("application/json").content(b)).andExpect(status().isBadRequest());verifyNoInteractions(nearby);
    }
    @Test void validReadUsesBodyOnlyAndNoStoreAndHidesDatabaseErrors() throws Exception {
        when(nearby.distance(any(),eq(1L),any())).thenReturn(Map.of("merchant_id",1,"distance_meters",0,"location_source","MERCHANT_CONFIRMED"));
        mvc.perform(post("/api/service/merchant/1/distance").header("Authorization","Bearer OWNER").contentType("application/json").content(point)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.distance_meters").value(0));
        when(nearby.distance(any(),eq(1L),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL 113.1234"));
        mvc.perform(post("/api/service/merchant/1/distance").header("Authorization","Bearer OWNER").contentType("application/json").content(point)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("距离服务暂不可用，请稍后重试"));
    }
    @Test void storeInputIsStrictAndSuccessEnvelopeIsNotWrappedTwice() throws Exception {
        for(String raw:List.of(store.replace("113","181"),store.replace("\"113\"","113"),store.replace("22","22.12345678"),store.replace("GCJ02","WGS84"),store.replace("{","{\"lng\":\"1\","),store.replace("}",",\"merchant_id\":2}")))
            mvc.perform(put("/api/merchant/location").header("Authorization","Bearer MERCHANT").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(raw)).andExpect(status().isBadRequest());
        verifyNoInteractions(locations);
        when(locations.update(any(),anyString(),any())).thenReturn(new ObjectMapper().readTree("{\"code\":0,\"data\":{\"location\":null}}"));
        mvc.perform(put("/api/merchant/location").header("Authorization","Bearer MERCHANT").header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(store)).andExpect(status().isOk()).andExpect(jsonPath("$.data.location").isEmpty()).andExpect(jsonPath("$.data.data").doesNotExist());
    }
}
