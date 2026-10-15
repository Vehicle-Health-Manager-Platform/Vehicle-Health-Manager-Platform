package com.autocare.platform.order;
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
@SpringBootTest(properties={"JWT_SECRET=test-only-secret-with-at-least-32-characters","WECHAT_APP_ID=test-app"}) @AutoConfigureMockMvc class InvitationRewardHttpTest {
 @Autowired MockMvc mvc;@MockitoBean JwtDecoder decoder;@MockitoBean InvitationRewards rewards;
 @BeforeEach void tokens(){when(decoder.decode(anyString())).thenAnswer(c->{String t=c.getArgument(0);boolean staff=t.equals("staff");var b=Jwt.withTokenValue(t).header("alg","HS256").subject("1").claim("jti","session").claim("subject_type",staff?"staff_account":"user").claim("role",staff?"MERCHANT":"OWNER").claim("app_id",staff?"merchant-account":"test-app").expiresAt(Instant.now().plusSeconds(600));if(staff)b.claim("merchant_id",1);return b.build();});}
 @Test void anonymousWrongRoleAndRecipientOverridesAreRejected()throws Exception{mvc.perform(get("/api/invite/rewards")).andExpect(status().isUnauthorized());mvc.perform(get("/api/invite/rewards").header("Authorization","Bearer staff")).andExpect(status().isForbidden());mvc.perform(get("/api/merchant/invite/rewards").header("Authorization","Bearer owner")).andExpect(status().isForbidden());for(String q:List.of("user_id=2","page=1&page=2","page_size=100","page=0","reward_state=COMPLETE"))mvc.perform(get("/api/invite/rewards?"+q).header("Authorization","Bearer owner")).andExpect(status().isBadRequest());verifyNoInteractions(rewards);}
 @Test void ownerAndStaffReadsAreNoStoreAndNeverProcess()throws Exception{when(rewards.records(any(com.autocare.platform.vehicle.VehicleOwner.class),eq(1))).thenReturn(ReservationStore.page(List.of(),0,1,20));when(rewards.records(any(com.autocare.platform.service.MerchantActor.class),eq(1))).thenReturn(ReservationStore.page(List.of(),0,1,20));for(var pair:List.of(new String[]{"/api/invite/rewards","owner"},new String[]{"/api/merchant/invite/rewards","staff"}))mvc.perform(get(pair[0]+"?page=1&page_size=20").header("Authorization","Bearer "+pair[1])).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.total").value(0));verify(rewards,never()).process(anyLong());verify(rewards,never()).sweep();}
 @Test void noPublicWriteTriggerExists()throws Exception{mvc.perform(post("/api/invite/rewards").header("Authorization","Bearer owner").contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());verifyNoInteractions(rewards);}
}
