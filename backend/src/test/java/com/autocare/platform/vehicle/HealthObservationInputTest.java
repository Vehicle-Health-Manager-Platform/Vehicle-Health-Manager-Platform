package com.autocare.platform.vehicle;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HealthObservationInputTest {
    final ObjectMapper mapper=new ObjectMapper();
    void validate(String metric,String value,String unit,String date) throws Exception {
        HealthObservationInput.parse(mapper.readTree("{\"metric\":\""+metric+"\",\"expected_revision\":0,\"measurement\":{\"measurement_name\":\"读数\",\"value\":\""+value+"\",\"unit\":\""+unit+"\",\"measured_on\":\""+date+"\",\"method\":\"资料记录\",\"provenance\":\"OWNER_TRANSCRIBED\"}}")).validate(LocalDate.of(2026,10,12));
    }
    @Test void unitsAndDecimalFormatsAreStrict() throws Exception {
        validate("COOLANT","-25.5","CELSIUS","2026-10-12");validate("TRACTION_BATTERY","100","PERCENT","2026-10-12");
        for(String value:new String[]{"-1","101","1e2","NaN","01","1.1234","1000000000"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate("TRACTION_BATTERY",value,"PERCENT","2026-10-12"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate("BRAKE_PAD","5","V","2026-10-12"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate("SCORE","5","V","2026-10-12"));
    }
    @Test void datesFieldsAndClearAreExplicit() throws Exception {
        for(String date:new String[]{"2026-10-13","2026-02-29","0999-01-01"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->validate("OIL","20","PERCENT",date));
        HealthObservationInput.parse(mapper.readTree("{\"metric\":\"OIL\",\"expected_revision\":1,\"measurement\":{}}")).validate(LocalDate.of(2026,10,12));
        for(String raw:new String[]{"{\"metric\":\"OIL\",\"expected_revision\":0,\"measurement\":{\"score\":90}}","{\"metric\":\"OIL\",\"expected_revision\":0,\"measurement\":{},\"replace_source\":true}","{\"metric\":\"OIL\",\"expected_revision\":0,\"measurement\":{},\"other\":1}"})
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->HealthObservationInput.parse(mapper.readTree(raw)).validate(LocalDate.of(2026,10,12)));
    }
}
