package com.autocare.platform.order;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AssessmentCalculationTest {
 final ObjectMapper json=new ObjectMapper();
 JsonNode policy()throws Exception{return json.readTree("{\"acquisition_target\":3,\"coupon_target\":1,\"grade_thresholds\":[{\"code\":\"A\",\"label\":\"甲\",\"min_score\":\"50.00\"},{\"code\":\"B\",\"label\":\"乙\",\"min_score\":\"0.00\"}]}");}
 Map<String,Object> f(String d,String s){return Map.of("dimension",d,"state",s);}
 @Test void roundingCapsAndGradeAreReproducible()throws Exception{var facts=List.of(f("ACQUISITION","INCLUDED"),f("COUPON","INCLUDED"),f("COUPON","INCLUDED"),f("PROCESS","INCLUDED"),f("PROCESS","INCOMPLETE"),f("PROCESS","INCOMPLETE"));var result=AssessmentCalculation.summary(policy(),facts);assertEquals("60.00",result.get("total_score"));assertEquals("A",((Map<?,?>)result.get("grade")).get("code"));assertEquals(result,AssessmentCalculation.summary(policy(),facts));}
 @Test void unknownAndNoDenominatorNeverBecomeZeroOrAGrade()throws Exception{var r=AssessmentCalculation.summary(policy(),List.of());assertEquals("UNKNOWN",r.get("state"));assertNull(r.get("total_score"));var metrics=(Map<?,?>)r.get("metrics");assertEquals("0.00",((Map<?,?>)metrics.get("coupon")).get("score"));assertNull(((Map<?,?>)metrics.get("process")).get("score"));assertNull(AssessmentCalculation.summary(policy(),List.of(f("PROCESS","UNKNOWN"))).get("grade"));assertNull(AssessmentCalculation.summary(json.nullNode(),List.of(f("PROCESS","INCLUDED"))).get("total_score"));}
 @Test void wholeBeijingMonthsAndStrictBodiesAreRequired(){assertEquals("2026-12-31T16:00:00Z",AssessmentCalculation.start(AssessmentCalculation.month("2027-01")).toString());for(String m:List.of("2026-00","2026-13","2026-1"," 2026-01","0000-01"))assertThrows(Exception.class,()->AssessmentCalculation.month(m));for(String b:List.of("{}","{\"merchant_id\":1,\"period_month\":\"2027-01\",\"expected_revision\":-1}","{\"merchant_id\":1,\"period_month\":\"2027-01\",\"expected_revision\":0,\"score\":100}"))assertThrows(Exception.class,()->AssessmentCalculation.input(b));}
}
