package com.autocare.platform.gateway.identity;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class OperatorInput {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public static JsonNode parse(String raw) {
        try { if (raw==null || raw.length()>1024) throw bad(); var b=JSON.readTree(raw); if(b==null || !b.isObject())throw bad(); return b; }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw bad(); }
    }
    public static String account(String a) {
        if(a==null || !a.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"))throw bad(); return a.toLowerCase(Locale.ROOT);
    }
    public static String password(String p) {
        if(p==null || p.isBlank() || p.getBytes(StandardCharsets.UTF_8).length>72 || p.indexOf('\0')>=0)throw bad(); return p;
    }
    public static void login(JsonNode b, boolean code) {
        if(b.size()!=(code?2:3) || !b.path("account").isTextual() || !b.path("password").isTextual())throw bad();
        account(b.get("account").textValue()); password(b.get("password").textValue());
        if(!code && (!b.path("sms_code").isTextual() || !b.get("sms_code").textValue().matches("[0-9]{6}")))throw bad();
    }
    public static org.springframework.web.server.ResponseStatusException bad() { return WriteIntegrityService.badRequest("运营请求信息无效"); }
}
