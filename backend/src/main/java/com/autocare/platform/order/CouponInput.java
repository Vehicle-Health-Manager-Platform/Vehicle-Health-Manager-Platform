package com.autocare.platform.order;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Strict contract; monetary values are decimal strings, never binary floating point. */
public final class CouponInput {
 private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对券规则、时间和版本");}
 static JsonNode parse(String raw){try{var n=JSON.readTree(raw);if(n==null||!n.isObject())throw bad();return n;}catch(Exception e){throw bad();}}
 static void exact(JsonNode n,String... keys){if(n==null||!n.isObject()||n.size()!=keys.length)throw bad();for(String k:keys)if(!n.has(k))throw bad();}
 static long version(JsonNode n){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<0||n.longValue()>=9007199254740991L)throw bad();return n.longValue();}
 static long id(JsonNode n){long v=version(n);if(v==0)throw bad();return v;}
 static BigDecimal money(JsonNode n){if(!n.isTextual()||!n.textValue().matches("(?:0|[1-9][0-9]{0,5})\\.[0-9]{2}"))throw bad();return new BigDecimal(n.textValue());}
 static Instant time(JsonNode n){try{if(!n.isTextual()||!n.textValue().matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))throw bad();var result=Instant.parse(n.textValue());if(!result.toString().equals(n.textValue()))throw bad();return result;}catch(Exception e){throw bad();}}
 static JsonNode terms(JsonNode n){exact(n,"name","type","project_id","amount","min_spend","total_quantity","valid_from","valid_to");
  if(!n.get("name").isTextual()||n.get("name").textValue().isBlank()||!n.get("name").textValue().equals(n.get("name").textValue().strip())||n.get("name").textValue().codePointCount(0,n.get("name").textValue().length())>128||n.get("name").textValue().chars().anyMatch(Character::isISOControl))throw bad();
  String type=n.path("type").asText();if(!Set.of("DISCOUNT","SERVICE").contains(type))throw bad();id(n.get("project_id"));var amount=money(n.get("amount"));var minimum=money(n.get("min_spend"));if(type.equals("SERVICE")?(amount.signum()!=0||minimum.signum()!=0):(amount.signum()<=0||minimum.compareTo(amount)<0))throw bad();
  if(!n.get("total_quantity").isIntegralNumber()||!n.get("total_quantity").canConvertToInt()||n.get("total_quantity").intValue()<1)throw bad();if(!time(n.get("valid_from")).isBefore(time(n.get("valid_to"))))throw bad();return n;
 }
 static JsonNode draft(String raw,boolean update){var b=parse(raw);if(update){exact(b,"expected_revision","terms");version(b.get("expected_revision"));}else exact(b,"terms");terms(b.get("terms"));return b;}
 static JsonNode status(String raw){var b=parse(raw);exact(b,"expected_revision","status");version(b.get("expected_revision"));if(!b.get("status").isTextual()||!Set.of("PUBLISHED","STOPPED").contains(b.get("status").textValue()))throw bad();return b;}
 static JsonNode pool(String raw){var b=parse(raw);exact(b,"expected_pool_revision","selected");version(b.get("expected_pool_revision"));if(!b.get("selected").isBoolean())throw bad();return b;}
 static JsonNode claim(String raw){var b=parse(raw);exact(b,"coupon_id","publication_revision");id(b.get("coupon_id"));id(b.get("publication_revision"));return b;}
}
