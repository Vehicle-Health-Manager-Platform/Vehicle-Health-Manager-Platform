package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class AssessmentPolicyInput {
 static final List<String> EVIDENCE=List.of("CHECK_IN_CONFIRMED","PROTECTION_COMPLETE","REPORT_SUBMITTED","QUALITY_SIGNED");
 static Object canonical(JsonNode n){if(n.isObject()){var sorted=new TreeMap<String,Object>();n.fields().forEachRemaining(e->sorted.put(e.getKey(),canonical(e.getValue())));return sorted;}if(n.isArray()){var values=new ArrayList<Object>();n.forEach(v->values.add(canonical(v)));return values;}return n;}
 static JsonNode body(String raw,boolean update){var b=CouponInput.parse(raw);var fields=new ArrayList<>(List.of("merchant_id","acquisition_target","coupon_target","process_requirements","grade_thresholds","source_note","valid_from","valid_to"));if(update)fields.add("expected_revision");CouponInput.exact(b,fields.toArray(String[]::new));CouponInput.id(b.get("merchant_id"));if(update)CouponInput.id(b.get("expected_revision"));
  for(String k:List.of("acquisition_target","coupon_target"))if(!b.path(k).isIntegralNumber()||!b.path(k).canConvertToInt()||b.path(k).intValue()<1||b.path(k).intValue()>1000000)throw ReservationInput.bad();
  var requirements=b.get("process_requirements");if(!requirements.isArray()||requirements.size()<1||requirements.size()>EVIDENCE.size())throw ReservationInput.bad();var seen=new HashSet<String>();for(var item:requirements)if(!item.isTextual()||!EVIDENCE.contains(item.asText())||!seen.add(item.asText()))throw ReservationInput.bad();
  var grades=b.get("grade_thresholds");if(!grades.isArray()||grades.size()<2||grades.size()>10)throw ReservationInput.bad();var codes=new HashSet<String>();var labels=new HashSet<String>();var previous=new BigDecimal("100.01");
  for(var grade:grades){CouponInput.exact(grade,"code","label","min_score");String code=grade.path("code").asText();if(!grade.path("code").isTextual()||!code.matches("[A-Z][A-Z0-9_]{0,15}")||!codes.add(code))throw ReservationInput.bad();DiagnosisServicePolicies.text(grade.get("label"),1,32);if(!labels.add(grade.get("label").asText())||!grade.path("min_score").isTextual()||!grade.get("min_score").asText().matches("(?:0|[1-9][0-9]?|100)\\.[0-9]{2}"))throw ReservationInput.bad();var score=new BigDecimal(grade.get("min_score").asText());if(score.compareTo(new BigDecimal("100.00"))>0||score.compareTo(previous)>=0)throw ReservationInput.bad();previous=score;}
  if(previous.signum()!=0)throw ReservationInput.bad();DiagnosisServicePolicies.text(b.get("source_note"),2,256);Instant from=month(b.get("valid_from")),to=month(b.get("valid_to"));if(!to.isAfter(from))throw ReservationInput.bad();return b;
 }
 private static Instant month(JsonNode n){var instant=CouponInput.time(n);var local=instant.atZone(ZoneId.of("Asia/Shanghai"));if(local.getDayOfMonth()!=1||!local.toLocalTime().equals(LocalTime.MIDNIGHT))throw ReservationInput.bad();return instant;}
}
