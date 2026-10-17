package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
final class PricePolicyInput {
 static final List<String> FIELDS=List.of("quote_id","source_hash","lower_price","upper_price","price_unit","applicability_note","source_note","scope_confirmed","valid_from","valid_to");
 static JsonNode body(String raw,boolean update){var b=CouponInput.parse(raw);var keys=new ArrayList<>(FIELDS);if(update)keys.add("expected_revision");CouponInput.exact(b,keys.toArray(String[]::new));CouponInput.id(b.get("quote_id"));if(update)CouponInput.id(b.get("expected_revision"));if(!b.path("source_hash").isTextual()||!b.get("source_hash").asText().matches("[a-f0-9]{64}")||!b.path("scope_confirmed").isBoolean()||!b.get("scope_confirmed").asBoolean())throw ReservationInput.bad();
  for(String k:List.of("lower_price","upper_price"))if(!b.path(k).isTextual()||!b.get(k).asText().matches("(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}")||new BigDecimal(b.get(k).asText()).signum()<=0)throw ReservationInput.bad();if(new BigDecimal(b.get("lower_price").asText()).compareTo(new BigDecimal(b.get("upper_price").asText()))>0)throw ReservationInput.bad();
  DiagnosisServicePolicies.text(b.get("price_unit"),1,32);for(String k:List.of("applicability_note","source_note"))DiagnosisServicePolicies.text(b.get(k),2,256);if(!CouponInput.time(b.get("valid_to")).isAfter(CouponInput.time(b.get("valid_from"))))throw ReservationInput.bad();return b;
 }
}
