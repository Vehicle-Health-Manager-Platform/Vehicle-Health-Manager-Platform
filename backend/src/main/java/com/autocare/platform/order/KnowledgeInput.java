package com.autocare.platform.order;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

final class KnowledgeInput {
 static final List<String> FIELDS=List.of("title","publisher","document_version","source_reference","license_reference","license_confirmed","model_id","valid_from","valid_to","search_allowed","excerpt_allowed","ai_allowed","body");
 private static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 static JsonNode parse(String raw){try{if(raw==null||raw.length()>64000)throw ReservationInput.bad();return JSON.readTree(raw);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ReservationInput.bad();}}
 static String text(JsonNode n,int max,boolean lines){if(n==null||!n.isTextual())throw ReservationInput.bad();String s=n.asText();if(!s.equals(s.strip())||s.isEmpty()||s.codePointCount(0,s.length())>max||s.codePoints().anyMatch(c->Character.getType(c)==Character.FORMAT||Character.isISOControl(c)&&!(lines&&c=='\n')))throw ReservationInput.bad();return s;}
 static Instant time(JsonNode n){String s=text(n,20,false);try{Instant t=Instant.parse(s);if(!s.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")||!t.toString().equals(s))throw ReservationInput.bad();return t;}catch(java.time.DateTimeException e){throw ReservationInput.bad();}}
 static JsonNode config(String raw,boolean update){var b=parse(raw);var fields=new ArrayList<>(FIELDS);if(update)fields.add("expected_revision");CouponInput.exact(b,fields.toArray(String[]::new));if(update)CouponInput.id(b.get("expected_revision"));validatePayload(b);return b;}
 static void validatePayload(JsonNode b){text(b.get("title"),120,false);text(b.get("publisher"),120,false);text(b.get("document_version"),80,false);text(b.get("source_reference"),500,false);text(b.get("license_reference"),500,false);text(b.get("body"),10000,true);CouponInput.id(b.get("model_id"));Instant from=time(b.get("valid_from")),to=time(b.get("valid_to"));if(!from.isBefore(to))throw ReservationInput.bad();for(String k:List.of("license_confirmed","search_allowed","excerpt_allowed","ai_allowed"))if(!b.path(k).isBoolean())throw ReservationInput.bad();}
 static JsonNode payload(JsonNode b){var out=JSON.createObjectNode();FIELDS.forEach(k->out.set(k,b.get(k)));return out;}
 static JsonNode state(String raw){var b=parse(raw);CouponInput.exact(b,"expected_revision","status");CouponInput.id(b.get("expected_revision"));if(!b.path("status").isTextual()||!Set.of("PUBLISHED","STOPPED").contains(b.path("status").asText()))throw ReservationInput.bad();return b;}
 static JsonNode question(String raw){var b=parse(raw);CouponInput.exact(b,"vehicle_id","question");CouponInput.id(b.get("vehicle_id"));text(b.get("question"),1000,true);return b;}
}
