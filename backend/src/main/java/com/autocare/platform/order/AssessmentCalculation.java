package com.autocare.platform.order;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.time.*;
import java.util.*;

/** Deterministic calculation from the stored minimal facts, never from legacy scores. */
final class AssessmentCalculation {
 static final String VERSION="R8B_CALC_V1";
 static YearMonth month(String text){try{if(text==null||!text.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])"))throw ReservationInput.bad();return YearMonth.parse(text);}catch(java.time.DateTimeException e){throw ReservationInput.bad();}}
 static Instant start(YearMonth m){return m.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();}
 static JsonNode input(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"merchant_id","period_month","expected_revision");CouponInput.id(b.get("merchant_id"));CouponInput.version(b.get("expected_revision"));if(!b.path("period_month").isTextual())throw ReservationInput.bad();month(b.path("period_month").asText());return b;}
 static Map<String,Object> summary(JsonNode policy,List<Map<String,Object>> facts){
  var out=new LinkedHashMap<String,Object>();var metrics=new LinkedHashMap<String,Object>();
  for(String dimension:List.of("ACQUISITION","COUPON","PROCESS")){
   var selected=facts.stream().filter(f->dimension.equals(f.get("dimension"))).toList();
   long numerator=selected.stream().filter(f->"INCLUDED".equals(f.get("state"))).count();
   long denominator=dimension.equals("PROCESS")?selected.stream().filter(f->!"EXCLUDED".equals(f.get("state"))).count():policy.path(dimension.equals("ACQUISITION")?"acquisition_target":"coupon_target").asLong();
   boolean unknown=policy.isNull()||denominator==0||selected.stream().anyMatch(f->"UNKNOWN".equals(f.get("state")));
   var metric=new LinkedHashMap<String,Object>();metric.put("numerator",numerator);metric.put("denominator",denominator);metric.put("state",unknown?"UNKNOWN":"KNOWN");metric.put("score",unknown?null:BigDecimal.valueOf(Math.min(numerator,denominator)).multiply(BigDecimal.valueOf(dimension.equals("PROCESS")?20:40)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP).toPlainString());
   metric.put("reason",policy.isNull()?"POLICY_UNKNOWN":denominator==0?"NO_VALID_DENOMINATOR":selected.stream().anyMatch(f->"UNKNOWN".equals(f.get("state")))?"SOURCE_UNKNOWN":"VERIFIED");metrics.put(dimension.toLowerCase(Locale.ROOT),metric);
  }
  boolean known=metrics.values().stream().allMatch(v->"KNOWN".equals(((Map<?,?>)v).get("state")));
  BigDecimal total=known?metrics.values().stream().map(v->new BigDecimal((String)((Map<?,?>)v).get("score"))).reduce(BigDecimal.ZERO,BigDecimal::add):null;
  out.put("state",known?"CALCULATED":"UNKNOWN");out.put("metrics",metrics);out.put("total_score",total==null?null:total.toPlainString());Object grade=null;
  if(total!=null)for(var g:policy.path("grade_thresholds"))if(total.compareTo(new BigDecimal(g.path("min_score").asText()))>=0){grade=Map.of("code",g.path("code").asText(),"label",g.path("label").asText());break;}
  out.put("grade",grade);out.put("fact_count",facts.size());return out;
 }
}
