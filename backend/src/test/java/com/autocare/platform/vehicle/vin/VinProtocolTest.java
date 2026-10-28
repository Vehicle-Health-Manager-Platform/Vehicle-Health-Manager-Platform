package com.autocare.platform.vehicle.vin;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VinProtocolTest {
    static final String VIN="LTEST123456789012"; // Synthetic format fixture, not a verified vehicle.
    final ObjectMapper mapper=new ObjectMapper();
    JsonNode response() {return mapper.valueToTree(Map.of("Count",1,"Results",List.of(Map.of("VIN",VIN,"ErrorCode","0","Make","Synthetic","Model","Fixture","ModelYear","2026","Trim","Test","VehicleType","PASSENGER CAR","ModelID","9999","ErrorText","synthetic private diagnostic"))));}
    com.fasterxml.jackson.databind.node.ObjectNode row(JsonNode node){return (com.fasterxml.jackson.databind.node.ObjectNode)node.path("Results").get(0);}
    @Test void ascii_case_and_outer_whitespace_only_are_normalized(){assertEquals(VIN,VinDecoder.normalize(" "+VIN.toLowerCase(Locale.ROOT)+" "));}
    @Test void malformed_partial_unicode_and_forbidden_characters_are_not_decoded(){
        for(String raw:List.of("",VIN.substring(1),VIN+"A","5UXWX7C5*BA",VIN.replace('T','I'),VIN.replace('T','O'),VIN.replace('T','Q'),"LTEST1234567890ß",VIN.replace('L','Ｌ'),"https://example.invalid",VIN.substring(0,8)+" "+VIN.substring(9),"\0"+VIN,VIN+"\0"))assertThrows(IllegalArgumentException.class,()->VinDecoder.normalize(raw));
        assertThrows(IllegalArgumentException.class,()->VinDecoder.normalize(null));
    }
    @Test void year_is_optional_and_never_guessed_from_vin(){VinDecoder.checkYear(null);VinDecoder.checkYear(1980);VinDecoder.checkYear(2100);for(int year:List.of(1979,2101,-1))assertThrows(IllegalArgumentException.class,()->VinDecoder.checkYear(year));}
    @Test void complete_candidate_has_provenance_but_no_internal_model_or_raw_diagnostics(){
        var result=VpicVinResponse.parse(VIN,2026,response());assertEquals(VinDecoder.Status.CANDIDATE,result.status());assertEquals("NHTSA_VPIC",result.source());assertTrue(result.requiresConfirmation());
        assertEquals(Set.of("make","model","model_year","trim","vehicle_type"),result.fields().keySet());assertEquals("2026",result.fields().get("model_year"));
        assertFalse(result.toString().contains(VIN));assertFalse(result.toString().contains("9999"));assertFalse(result.toString().contains("diagnostic"));assertThrows(UnsupportedOperationException.class,()->result.fields().put("model_id","1"));
    }
    @Test void missing_year_is_partial_and_hint_does_not_become_supplier_fact(){var node=response();row(node).putNull("ModelYear");var result=VpicVinResponse.parse(VIN,2026,node);assertEquals(VinDecoder.Status.PARTIAL,result.status());assertFalse(result.fields().containsKey("model_year"));}
    @Test void empty_result_fields_stay_partial_not_full_match(){var node=response();for(String name:List.of("Make","Model","ModelYear","Trim","VehicleType"))row(node).put(name,"");var result=VpicVinResponse.parse(VIN,null,node);assertEquals(VinDecoder.Status.PARTIAL,result.status());assertTrue(result.fields().isEmpty());}
    @Test void provider_errors_never_promote_partially_populated_facts(){for(String code:List.of("1","0, 14","6","999")){var node=response();row(node).put("ErrorCode",code);var result=VpicVinResponse.parse(VIN,null,node);assertEquals(VinDecoder.Status.NO_MATCH,result.status());assertTrue(result.fields().isEmpty());}}
    @Test void response_vin_must_match_exact_request(){for(String vin:List.of("LTEST123456789013",VIN.toLowerCase(Locale.ROOT),"")){var node=response();row(node).put("VIN",vin);assertThrows(VinDecoder.Unavailable.class,()->VpicVinResponse.parse(VIN,null,node));}}
    @Test void count_shape_and_multiple_rows_are_rejected(){
        for(JsonNode node:List.of(mapper.createObjectNode(),mapper.valueToTree(Map.of("Count",1,"Results",List.of())),mapper.valueToTree(Map.of("Count",2,"Results",List.of(response().path("Results").get(0),response().path("Results").get(0)))),mapper.valueToTree(Map.of("Count",4294967297L,"Results",response().path("Results"))),mapper.valueToTree(Map.of("Count",1.0,"Results",response().path("Results")))))assertThrows(VinDecoder.Unavailable.class,()->VpicVinResponse.parse(VIN,null,node));
    }
    @Test void missing_error_code_or_wrong_scalar_type_is_protocol_failure(){for(JsonNode value:List.of(mapper.nullNode(),mapper.getNodeFactory().numberNode(0),mapper.getNodeFactory().textNode(""))){var node=response();row(node).set("ErrorCode",value);assertThrows(VinDecoder.Unavailable.class,()->VpicVinResponse.parse(VIN,null,node));}}
    @Test void unsafe_or_nontext_fields_are_not_returned(){for(JsonNode value:List.of(mapper.getNodeFactory().textNode("bad\nvalue"),mapper.getNodeFactory().textNode("x".repeat(161)),mapper.getNodeFactory().numberNode(123),mapper.getNodeFactory().textNode("\ud800"))){var node=response();row(node).set("Make",value);assertThrows(VinDecoder.Unavailable.class,()->VpicVinResponse.parse(VIN,null,node));}}
    @Test void contradictory_or_invalid_year_never_becomes_match(){for(String year:List.of("2025","0026","2026.0","2101")){var node=response();row(node).put("ModelYear",year);assertThrows(VinDecoder.Unavailable.class,()->VpicVinResponse.parse(VIN,2026,node));}}
    @Test void protocol_forbids_auto_confirmation_and_catalog_id_injection(){
        assertThrows(IllegalArgumentException.class,()->new VinDecoder.Result(VinDecoder.Status.CANDIDATE,"NHTSA_VPIC",Map.of(),true));
        assertThrows(IllegalArgumentException.class,()->new VinDecoder.Result(VinDecoder.Status.NO_MATCH,"NHTSA_VPIC",Map.of("make","x"),true));
        assertThrows(IllegalArgumentException.class,()->new VinDecoder.Result(VinDecoder.Status.PARTIAL,"NHTSA_VPIC",Map.of("model_id","1"),true));
        assertThrows(IllegalArgumentException.class,()->new VinDecoder.Result(VinDecoder.Status.PARTIAL,"NHTSA_VPIC",Map.of(),false));
    }
}
