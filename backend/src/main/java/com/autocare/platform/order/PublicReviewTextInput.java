package com.autocare.platform.order;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PublicReviewTextInput {
    static final String CONSENT="R4B2_TEXT_V1",REDACTION="TEXT_REDACTION_V1";
    static final Set<String> REASONS=Set.of("PRIVACY","ABUSE","OFF_TOPIC","UNVERIFIABLE");
    private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static JsonNode parse(String raw){try{if(raw==null||raw.length()>4096)throw ReservationInput.bad();return JSON.readTree(raw);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ReservationInput.bad();}}
    static void fields(JsonNode b,String... keys){if(b==null||!b.isObject()||b.size()!=keys.length||!Arrays.stream(keys).allMatch(b::has))throw ReservationInput.bad();}
    static long version(JsonNode n){if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()>9007199254740991L)throw ReservationInput.bad();return n.longValue();}
    static String redact(JsonNode node){
        if(node==null||!node.isTextual())throw ReservationInput.bad();String raw=node.textValue();
        if(raw.codePointCount(0,raw.length())>300)throw ReservationInput.bad();
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(Character.isHighSurrogate(c)){if(i+1>=raw.length()||!Character.isLowSurrogate(raw.charAt(++i)))throw ReservationInput.bad();}else if(Character.isLowSurrogate(c))throw ReservationInput.bad();}
        String s=Normalizer.normalize(raw,Normalizer.Form.NFKC).strip();
        if(s.isEmpty()||s.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT||Character.isISOControl(c)&&c!='\n'))throw ReservationInput.bad();
        s=s.replaceAll("(?i)(?:https?://|www\\.)[^\\s]+","[已隐藏]")
            .replaceAll("(?i)[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}","[已隐藏]")
            .replaceAll("(?i)(?<![A-Z0-9])[A-HJ-NPR-Z0-9]{17}(?![A-Z0-9])","[已隐藏]")
            .replaceAll("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Za-z][A-Za-z0-9]{5,6}","[已隐藏]")
            .replaceAll("[0-9](?:[ \\-]*[0-9]){6,}","[已隐藏]");
        if(s.codePointCount(0,s.length())>300)throw ReservationInput.bad();return s;
    }
    static JsonNode owner(JsonNode b){
        if(b==null||!b.path("action").isTextual())throw ReservationInput.bad();String action=b.get("action").textValue();
        if(action.equals("WITHDRAW")){fields(b,"action","expected_version");version(b.get("expected_version"));return b;}
        if(!action.equals("SUBMIT"))throw ReservationInput.bad();fields(b,"action","expected_version","public_text","consent_version");version(b.get("expected_version"));
        if(!CONSENT.equals(b.path("consent_version").asText()))throw ReservationInput.bad();String sanitized=redact(b.get("public_text"));
        if(!sanitized.equals(b.get("public_text").textValue()))throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"请重新预览脱敏文本并确认授权");return b;
    }
    static JsonNode moderate(JsonNode b){fields(b,"expected_version","decision","reason_code");version(b.get("expected_version"));String d=b.path("decision").asText();
        if(!Set.of("APPROVE","REJECT").contains(d)||d.equals("APPROVE")&&!b.get("reason_code").isNull()||d.equals("REJECT")&&(!b.get("reason_code").isTextual()||!REASONS.contains(b.get("reason_code").textValue())))throw ReservationInput.bad();return b;}
}
