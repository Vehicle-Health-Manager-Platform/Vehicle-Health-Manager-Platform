package com.autocare.platform.order;

import com.autocare.platform.vehicle.HealthObservationInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

final class HealthPolicyInput {
 static JsonNode input(String raw,String metric,LocalDate today){var b=PublicReviewTextInput.parse(raw);PublicReviewTextInput.fields(b,"expected_version","rule");PublicReviewTextInput.version(b.get("expected_version"));if(!b.get("rule").isNull())rule(b.get("rule"),metric,today,false);return b;}
 static void text(JsonNode n,int max){if(n==null||!n.isTextual())throw ReservationInput.bad();String s=n.textValue();if(s.isBlank()||!s.equals(s.strip())||s.codePointCount(0,s.length())>max||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw ReservationInput.bad();}
 static LocalDate date(JsonNode n){if(n==null||!n.isTextual()||!n.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ReservationInput.bad();try{var d=LocalDate.parse(n.textValue());if(d.getYear()<1000)throw ReservationInput.bad();return d;}catch(java.time.DateTimeException e){throw ReservationInput.bad();}}
 static int integer(JsonNode n,int min,int max){if(n==null||!n.isIntegralNumber()||!n.canConvertToInt()||n.intValue()<min||n.intValue()>max)throw ReservationInput.bad();return n.intValue();}
 static BigDecimal decimal(JsonNode n){if(n==null||!n.isTextual()||!n.textValue().matches("-?(0|[1-9][0-9]{0,8})(\\.[0-9]{1,3})?"))throw ReservationInput.bad();return new BigDecimal(n.textValue());}
 static void rule(JsonNode r,String metric,LocalDate today,boolean publish){HealthObservationInput.metric(metric);PublicReviewTextInput.fields(r,"applicability","source_reference","source_checked_on","valid_until","source_confirmed","measurement_name","unit","method","provenance","max_age_days","weight","bands","action_project_id","action_score_below");text(r.get("source_reference"),256);var checked=date(r.get("source_checked_on"));var until=date(r.get("valid_until"));if(!r.get("source_confirmed").isBoolean()||!r.get("source_confirmed").booleanValue()||checked.isAfter(today)||until.isBefore(checked)||publish&&until.isBefore(today))throw ReservationInput.bad();
  String applicability=r.path("applicability").asText();if(applicability.equals("NOT_APPLICABLE")){for(String k:Set.of("measurement_name","unit","method","provenance","action_project_id","action_score_below"))if(!r.get(k).isNull())throw ReservationInput.bad();integer(r.get("max_age_days"),0,0);integer(r.get("weight"),0,0);if(!r.get("bands").isArray()||!r.get("bands").isEmpty())throw ReservationInput.bad();return;}
  if(!applicability.equals("APPLICABLE"))throw ReservationInput.bad();text(r.get("measurement_name"),80);text(r.get("method"),120);if(!HealthObservationInput.UNITS.get(metric).contains(r.path("unit").asText())||!Set.of("OWNER_MEASURED","OWNER_TRANSCRIBED").contains(r.path("provenance").asText()))throw ReservationInput.bad();integer(r.get("max_age_days"),1,3650);integer(r.get("weight"),1,100);
  var bands=r.get("bands");if(!bands.isArray()||bands.size()<1||bands.size()>20)throw ReservationInput.bad();BigDecimal prior=null;for(int i=0;i<bands.size();i++){var band=bands.get(i);PublicReviewTextInput.fields(band,"upper_bound","score");integer(band.get("score"),0,100);if(i==bands.size()-1){if(!band.get("upper_bound").isNull())throw ReservationInput.bad();}else{var upper=decimal(band.get("upper_bound"));if(prior!=null&&upper.compareTo(prior)<=0)throw ReservationInput.bad();if(!r.path("unit").asText().equals("CELSIUS")&&upper.signum()<0||r.path("unit").asText().equals("PERCENT")&&upper.compareTo(BigDecimal.valueOf(100))>0)throw ReservationInput.bad();prior=upper;}}
  if(r.get("action_project_id").isNull()){if(!r.get("action_score_below").isNull())throw ReservationInput.bad();}else{ReservationInput.id(r.get("action_project_id"));integer(r.get("action_score_below"),1,100);}
 }
 static boolean matches(JsonNode measurement,JsonNode rule){for(String k:Set.of("measurement_name","unit","method","provenance"))if(!measurement.path(k).equals(rule.path(k)))return false;return true;}
 static int score(JsonNode measurement,JsonNode rule){var value=decimal(measurement.get("value"));for(var b:rule.get("bands"))if(b.get("upper_bound").isNull()||value.compareTo(decimal(b.get("upper_bound")))<=0)return b.get("score").intValue();throw ReservationInput.bad();}
}
