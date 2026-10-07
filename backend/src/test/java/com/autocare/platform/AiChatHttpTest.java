package com.autocare.platform;

import com.autocare.platform.ai.AiContextProvider;
import com.autocare.platform.ai.AiUpstreamException;
import com.autocare.platform.ai.DeepSeekClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="JWT_SECRET=test-only-secret-with-at-least-32-characters")
@AutoConfigureMockMvc
class AiChatHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean DeepSeekClient client;
    @MockitoBean AiContextProvider contexts;

    @BeforeEach void tokens() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token=call.getArgument(0);
            if(token.equals("expired")) throw new BadJwtException("Expired");
            var builder=Jwt.withTokenValue(token).header("alg","HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
            if(!token.equals("local")) builder.claim("subject_type",token.equals("binding")?"wechat_binding":token.equals("owner")?"user":"staff_account");
            if(!token.equals("missing-session")) builder.claim("jti","ai-session");
            return builder.claim("role",token.equals("staff")?"TECHNICIAN":token.equals("merchant")?"MERCHANT":"OWNER").build();
        });
    }

    private ResultActions chat(String token,String json) throws Exception {
        return mvc.perform(post("/api/ai/chat").header("Authorization","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test void rejectsAnonymousExpiredAndNonOwnerRoles() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"刹车异响\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        chat("expired","{\"message\":\"刹车异响\"}").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(40100));
        for(String token:List.of("local","binding","staff","merchant"))
            chat(token,"{\"message\":\"刹车异响\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40300));
        verifyNoInteractions(client,contexts);
    }

    @Test void validatesBodyBeforeAnyProviderCall() throws Exception {
        List<String> invalid=List.of(
            "[]",
            "{\"vehicle_id\":7}",
            "{\"message\":\"   \"}",
            "{\"message\":\""+"长".repeat(2001)+"\"}",
            "{\"message\":\"刹车异响\",\"unknown\":1}",
            "{\"message\":\"刹车异响\",\"vehicle_id\":0}",
            "{\"message\":\"刹车异响\",\"vehicle_id\":9007199254740992}",
            "{\"message\":\"刹车异响\",\"vehicle_id\":\"7\"}",
            "{\"message\":\"刹车异响\",\"history\":{}}",
            "{\"message\":\"刹车异响\",\"history\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"user\",\"content\":\"b\"},{\"role\":\"user\",\"content\":\"c\"},{\"role\":\"user\",\"content\":\"d\"},{\"role\":\"user\",\"content\":\"e\"},{\"role\":\"user\",\"content\":\"f\"},{\"role\":\"user\",\"content\":\"g\"},{\"role\":\"user\",\"content\":\"h\"},{\"role\":\"user\",\"content\":\"i\"}]}",
            "{\"message\":\"刹车异响\",\"history\":[{\"role\":\"system\",\"content\":\"a\"}]}",
            "{\"message\":\"刹车异响\",\"history\":[{\"role\":\"user\"}]}"
        );
        for(String json:invalid) chat("owner",json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
        verifyNoInteractions(client,contexts);
    }

    @Test void groundsAnswerInOwnerVehicleArchive() throws Exception {
        doReturn(new AiContextProvider.Context(7L,"宝马 3系",
            "车型：宝马 3系\n当前里程：48000 km\n近期养护档案（倒序，最多 5 条）：\n- 2026-09-20 保养：更换机油"))
            .when(contexts).forVehicle(any(),eq(7L));
        doReturn(new DeepSeekClient.Completion("建议先检查刹车片厚度。","deepseek-flash",120,64))
            .when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"刹车有异响\",\"vehicle_id\":7,\"history\":[{\"role\":\"user\",\"content\":\"你好\"},{\"role\":\"assistant\",\"content\":\"您好，请描述现象\"}]}")
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.data.reply").value("建议先检查刹车片厚度。"))
            .andExpect(jsonPath("$.data.model").value("deepseek-flash"))
            .andExpect(jsonPath("$.data.grounded").value(true))
            .andExpect(jsonPath("$.data.vehicle_id").value(7));
        ArgumentCaptor<String> system=ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<DeepSeekClient.Message>> turns=ArgumentCaptor.forClass(List.class);
        verify(client).complete(system.capture(),turns.capture(),eq(1024));
        assertThat(system.getValue(),containsString("【车主当前车辆档案】"));
        assertThat(system.getValue(),containsString("更换机油"));
        Assertions.assertEquals(List.of("user","assistant","user"),turns.getValue().stream().map(DeepSeekClient.Message::role).toList());
        Assertions.assertEquals("刹车有异响",turns.getValue().get(2).content());
        verify(contexts).forVehicle(argThat(owner->owner.id()==1&&owner.session().equals("ai-session")),eq(7L));
    }

    @Test void answersGenerallyWithoutVehicle() throws Exception {
        doReturn(new DeepSeekClient.Completion("通用建议","deepseek-flash",8,4)).when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"多久换一次机油\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.grounded").value(false))
            .andExpect(jsonPath("$.data.vehicle_id").value(nullValue()));
        ArgumentCaptor<String> system=ArgumentCaptor.forClass(String.class);
        verify(client).complete(system.capture(),anyList(),anyInt());
        Assertions.assertFalse(system.getValue().contains("【车主当前车辆档案】"));
        verifyNoInteractions(contexts);
    }

    @Test void degradesToGeneralAnswerWhenArchiveDatabaseIsDown() throws Exception {
        doThrow(new DataAccessResourceFailureException("private database URL")).when(contexts).forVehicle(any(),eq(7L));
        doReturn(new DeepSeekClient.Completion("通用建议","deepseek-flash",8,4)).when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"刹车有异响\",\"vehicle_id\":7}").andExpect(status().isOk())
            .andExpect(jsonPath("$.data.grounded").value(false))
            .andExpect(jsonPath("$.data.reply").value("通用建议"));
    }

    @Test void rejectsVehicleThatIsNotOwned() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"车辆或档案不可用")).when(contexts).forVehicle(any(),eq(9L));
        chat("owner","{\"message\":\"刹车有异响\",\"vehicle_id\":9}").andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40400));
        verifyNoInteractions(client);
    }

    @Test void degradesWhenProviderIsUnconfiguredOrFailing() throws Exception {
        doThrow(new AiUpstreamException(AiUpstreamException.Reason.UNCONFIGURED,"AI 管家尚未配置"))
            .when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"刹车有异响\"}").andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value(50301))
            .andExpect(jsonPath("$.message").value("AI 管家尚未配置"));
        doThrow(new AiUpstreamException(AiUpstreamException.Reason.UPSTREAM_FAILURE,"AI 管家暂时不可用"))
            .when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"刹车有异响\"}").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value(50301));
        doThrow(new AiUpstreamException(AiUpstreamException.Reason.RATE_LIMITED,"AI 管家请求过于频繁，请稍后重试"))
            .when(client).complete(anyString(),anyList(),anyInt());
        chat("owner","{\"message\":\"刹车有异响\"}").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value(42900));
    }
}
