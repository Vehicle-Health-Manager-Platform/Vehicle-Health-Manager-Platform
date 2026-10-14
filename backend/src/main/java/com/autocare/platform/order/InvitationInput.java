package com.autocare.platform.order;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class InvitationInput {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"邀请参数无效");}
    public static JsonNode parse(String raw){try{var n=JSON.readTree(raw);if(n==null||!n.isObject())throw bad();return n;}catch(Exception e){throw bad();}}
    public static JsonNode empty(String raw){var n=parse(raw);if(n.size()!=0)throw bad();return n;}
    public static String phone(String raw){var n=parse(raw);if(n.size()!=1||!n.path("code").isTextual()||n.path("code").textValue().isBlank()||n.path("code").textValue().length()>512)throw bad();return n.path("code").textValue();}
    public static String code(JsonNode n){if(n==null)return null;if(!n.isTextual()||!n.textValue().matches("[0-9a-f]{32}"))throw bad();return n.textValue();}
    public static JsonNode login(String raw,boolean cloud){var n=parse(raw);var fields=cloud?java.util.Set.of("role","invite_code"):java.util.Set.of("role","code","invite_code");n.fieldNames().forEachRemaining(f->{if(!fields.contains(f))throw bad();});if(!n.path("role").isTextual()||!java.util.Set.of("owner","technician").contains(n.path("role").textValue())||!cloud&&(!n.path("code").isTextual()||n.path("code").textValue().isBlank()||n.path("code").textValue().length()>512))throw bad();if(n.has("invite_code")){code(n.get("invite_code"));if(!"owner".equals(n.path("role").textValue()))throw bad();}return n;}
}
