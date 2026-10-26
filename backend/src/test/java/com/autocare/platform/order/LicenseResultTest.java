package com.autocare.platform.order;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LicenseResultTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode answer(){return LicenseTestData.answer(mapper);}
    ObjectNode field(ObjectNode out,int index){return (ObjectNode)out.path("fields").get(index);}
    ObjectNode row(ObjectNode out,int index){return (ObjectNode)field(out,index).path("candidates").get(0);}
    void empty(ObjectNode out,int index){field(out,index).put("status","NO_CANDIDATE").putArray("candidates");}
    @Test void complete_projection_is_defensively_copied_and_contains_no_internal_model(){
        var source=answer();var safe=LicenseResult.validate(source);row(source,0).put("value","粤L11111");
        assertEquals(LicenseTestData.PLATE,safe.path("fields").get(0).path("candidates").get(0).path("value").asText());
        assertFalse(safe.has("model_id"));assertTrue(safe.path("requires_confirmation").booleanValue());
    }
    @Test void no_candidate_partial_low_and_ambiguous_are_kept_separate(){
        var partial=answer();empty(partial,1);partial.put("status","PARTIAL");assertEquals("PARTIAL",LicenseResult.validate(partial).path("status").asText());
        var none=answer();empty(none,0);empty(none,1);none.put("status","NO_CANDIDATE");assertEquals("NO_CANDIDATE",LicenseResult.validate(none).path("status").asText());
        var low=answer();row(low,0).put("confidence",.899);field(low,0).put("status","LOW_CONFIDENCE");low.put("status","LOW_CONFIDENCE");assertEquals(.899,LicenseResult.validate(low).path("fields").get(0).path("candidates").get(0).path("confidence").doubleValue());
        var ambiguous=answer();((ArrayNode)field(ambiguous,0).path("candidates")).addObject().put("value","粤L11111").put("confidence",.99);field(ambiguous,0).put("status","AMBIGUOUS");ambiguous.put("status","AMBIGUOUS");assertEquals(2,LicenseResult.validate(ambiguous).path("fields").get(0).path("candidates").size());
    }
    @Test void structural_source_version_flags_extra_private_fields_and_field_order_are_rejected(){
        for(Consumer<ObjectNode> change:List.<Consumer<ObjectNode>>of(o->o.put("version",4294967297L),o->o.put("version",1.0),o->o.put("scene","mileage"),o->o.put("source","CLOUD"),o->o.put("requires_confirmation",false),o->o.put("image_recognition_performed",false),o->o.put("owner_name","private"),o->field(o,0).put("address","private"),o->row(o,0).put("raw_text","private"),o->((ArrayNode)o.path("fields")).remove(1),o->field(o,0).put("key","vin"))){var out=answer();change.accept(out);assertThrows(RuntimeException.class,()->LicenseResult.validate(out));}
        assertThrows(RuntimeException.class,()->LicenseResult.validate(null));
    }
    @Test void confidence_not_numeric_finite_or_in_range_is_rejected(){
        for(JsonNode score:List.of(mapper.getNodeFactory().booleanNode(true),mapper.getNodeFactory().textNode(".95"),mapper.getNodeFactory().nullNode(),DoubleNode.valueOf(Double.NaN),DoubleNode.valueOf(Double.POSITIVE_INFINITY),DoubleNode.valueOf(-.1),DoubleNode.valueOf(1.01))){var out=answer();row(out,0).set("confidence",score);assertThrows(RuntimeException.class,()->LicenseResult.validate(out));}
    }
    @Test void malformed_identifiers_unicode_repairs_whitespace_and_duplicate_values_are_rejected(){
        for(String vin:List.of("LTEST42345678901O","LTEST42345678901I","A".repeat(15)+"ß",LicenseTestData.VIN.toLowerCase(Locale.ROOT)," "+LicenseTestData.VIN,LicenseTestData.VIN+"\0")){var out=answer();row(out,1).put("value",vin);assertThrows(RuntimeException.class,()->LicenseResult.validate(out));}
        for(String plate:List.of("粤 L54321","粤l54321","粤L5432","粤L5432100","粤L54321\0")){var out=answer();row(out,0).put("value",plate);assertThrows(RuntimeException.class,()->LicenseResult.validate(out));}
        var duplicate=answer();((ArrayNode)field(duplicate,0).path("candidates")).add(row(duplicate,0).deepCopy());assertThrows(RuntimeException.class,()->LicenseResult.validate(duplicate));
    }
    @Test void false_status_promotion_and_too_many_alternatives_are_rejected(){
        var low=answer();row(low,0).put("confidence",.5);assertThrows(RuntimeException.class,()->LicenseResult.validate(low));
        var wrongOverall=answer();wrongOverall.put("status","PARTIAL");assertThrows(RuntimeException.class,()->LicenseResult.validate(wrongOverall));
        var tooMany=answer();var candidates=(ArrayNode)field(tooMany,0).path("candidates");candidates.removeAll();for(int i=0;i<11;i++)candidates.addObject().put("value","粤L"+(10000+i)).put("confidence",.95);field(tooMany,0).put("status","AMBIGUOUS");tooMany.put("status","AMBIGUOUS");assertThrows(RuntimeException.class,()->LicenseResult.validate(tooMany));
    }
}
