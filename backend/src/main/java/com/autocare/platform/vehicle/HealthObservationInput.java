package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record HealthObservationInput(String metric, int revision, ObjectNode measurement, boolean replaceSource, String operation) {
    public static final List<String> METRICS=List.of("OIL","BRAKE_PAD","TIRE","TRACTION_BATTERY","COOLANT","AUX_BATTERY");
    public static final Map<String,Set<String>> UNITS=Map.of("OIL",Set.of("KM","PERCENT"),"BRAKE_PAD",Set.of("MM"),"TIRE",Set.of("MM","KPA"),
        "TRACTION_BATTERY",Set.of("PERCENT","V"),"COOLANT",Set.of("CELSIUS"),"AUX_BATTERY",Set.of("V","PERCENT"));
    public static String metric(String metric) { if(!METRICS.contains(metric))throw invalid();return metric; }
    public static HealthObservationInput parse(JsonNode body) {
        if(body==null||!body.isObject()||!body.path("metric").isTextual()||!body.path("measurement").isObject()
            ||!body.path("expected_revision").isIntegralNumber()||!body.path("expected_revision").canConvertToInt()
            ||body.path("expected_revision").intValue()<0||body.path("expected_revision").intValue()>=Integer.MAX_VALUE)throw invalid();
        body.fieldNames().forEachRemaining(k->{if(!Set.of("metric","expected_revision","measurement","replace_source","operation").contains(k))throw invalid();});
        if(body.has("replace_source")&&!body.path("replace_source").isBoolean())throw invalid();
        String operation=body.has("operation")?body.path("operation").asText(""):body.path("measurement").isEmpty()?"CLEAR":"RECORD";
        if(!Set.of("RECORD","CORRECT","CLEAR").contains(operation)||operation.equals("CLEAR")!=body.path("measurement").isEmpty())throw invalid();
        return new HealthObservationInput(metric(body.path("metric").textValue()),body.path("expected_revision").intValue(),
            ((ObjectNode)body.get("measurement")).deepCopy(),body.path("replace_source").asBoolean(false),operation);
    }
    public void validate(LocalDate today) {
        if(measurement.isEmpty()){if(replaceSource)throw invalid();return;}
        var names=Set.of("measurement_name","value","unit","measured_on","method","provenance");
        if(measurement.size()!=names.size())throw invalid();
        measurement.fieldNames().forEachRemaining(k->{if(!names.contains(k))throw invalid();});
        text(measurement.path("measurement_name"),80);text(measurement.path("method"),120);
        if(!measurement.path("unit").isTextual()||!UNITS.get(metric).contains(measurement.path("unit").textValue())
            ||!measurement.path("provenance").isTextual()||!Set.of("OWNER_MEASURED","OWNER_TRANSCRIBED").contains(measurement.path("provenance").textValue()))throw invalid();
        var raw=measurement.path("value");
        if(!raw.isTextual()||!raw.textValue().matches("-?(0|[1-9][0-9]{0,8})(\\.[0-9]{1,3})?"))throw invalid();
        var value=new BigDecimal(raw.textValue());String unit=measurement.path("unit").textValue();
        if(!unit.equals("CELSIUS")&&value.signum()<0||unit.equals("PERCENT")&&value.compareTo(new BigDecimal("100"))>0)throw invalid();
        JsonNode day=measurement.path("measured_on");
        if(!day.isTextual()||!day.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalid();
        try{LocalDate parsed=LocalDate.parse(day.textValue());if(parsed.getYear()<1000||parsed.isAfter(today))throw invalid();}
        catch(RuntimeException e){throw invalid();}
    }
    private static void text(JsonNode node,int maximum){
        if(!node.isTextual()||node.textValue().isBlank()||!node.textValue().equals(node.textValue().strip())
            ||node.textValue().codePointCount(0,node.textValue().length())>maximum||node.textValue().codePoints().anyMatch(Character::isISOControl))throw invalid();
    }
    public Map<String,Object> canonical(){
        var result=new java.util.LinkedHashMap<String,Object>();result.put("metric",metric);result.put("expected_revision",revision);
        result.put("measurement",measurement);result.put("replace_source",replaceSource);
        if(operation.equals("CORRECT"))result.put("operation",operation);return result;
    }
    private static org.springframework.web.server.ResponseStatusException invalid(){return WriteIntegrityService.badRequest("请检查指标、读数、单位、来源说明和日期");}
}
