package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveStructuredInputTest {
    final ObjectMapper mapper=new ObjectMapper();
    void validate(int type,String fields) throws Exception { ArchiveStructuredInput.parse(mapper.readTree("{\"expected_revision\":0,\"fields\":"+fields+"}")).validateFor(type); }
    @Test void typedFieldsAndUnknownValuesAreStrict() throws Exception {
        validate(1,"{\"service_items\":[\"机油\"],\"performed_mileage\":0}");
        validate(3,"{\"policy_number\":\"POLICY-1\",\"coverage_type\":\"COMPULSORY\"}");
        validate(7,"{\"inspection_result\":\"UNKNOWN\",\"valid_until\":\"2028-02-29\"}");
        for(String fields:new String[]{"{\"score\":90}","{\"performed_mileage\":-1}","{\"performed_mileage\":1.5}","{\"service_items\":[\"机油\",\"机油\"]}","{\"provider_name\":null}","{\"provider_name\":\" a \"}","{\"service_items\":[]}"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate(1,fields));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate(7,"{\"coverage_type\":\"OTHER\"}"));
    }
    @Test void datesOrderLimitsAndClearAreExplicit() throws Exception {
        validate(3,"{}"); validate(7,"{}");
        for(String fields:new String[]{"{\"coverage_end\":\"2027-02-29\"}","{\"coverage_start\":\"2027-01-02\",\"coverage_end\":\"2027-01-01\"}","{\"coverage_type\":\"YES\"}","{\"policy_number\":\""+"x".repeat(65)+"\"}"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate(3,fields));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ArchiveStructuredInput.parse(mapper.readTree("{\"expected_revision\":0,\"fields\":{},\"extra\":true}")));
    }
}
