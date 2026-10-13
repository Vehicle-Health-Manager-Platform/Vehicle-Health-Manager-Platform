package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record ArchiveStructuredInput(int revision, ObjectNode fields) {
    private static final Set<String> SOURCE=Set.of("owner_confirmed","source_reference","source_checked_on");
    private static final Map<Integer, Set<String>> ALLOWED = Map.of(
        1, Set.of("provider_name","service_items","performed_mileage","service_date","oil_spec","parts_used","next_due_date","next_due_mileage"),
        2, Set.of("provider_name","repair_items","performed_mileage","repair_date","problem_description","parts_used","result_notes"),
        3, Set.of("insurer_name","policy_number","coverage_type","coverage_start","coverage_end","policy_holder","insured_name","insured_vehicle_plate","insured_vin","coverage_amount"),
        6, Set.of("case_number","occurred_on","location","description","penalty_amount","points","violation_status","handled_on","authority"),
        7, Set.of("inspection_station","inspection_result","valid_until","inspection_date","certificate_number","remarks"),
        8, Set.of("owner_name","address","plate_number","vehicle_type","brand_model","vin","engine_number","use_character","register_date","issue_date","issuing_authority","record_number","gross_mass_kg","unladen_mass_kg","approved_passengers","inspection_valid_until"),
        9, Set.of("holder_name","license_number","nationality","address","birth_date","first_issue_date","permitted_classes","valid_from","valid_until","validity_type","issuing_authority"),
        10, Set.of("fault_code","fault_description","observed_on","fault_status","cleared_on","attention_level"));
    public static ArchiveStructuredInput parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("fields") || !body.path("fields").isObject()
            || !body.path("expected_revision").isIntegralNumber() || !body.path("expected_revision").canConvertToInt()
            || body.path("expected_revision").intValue() < 0 || body.path("expected_revision").intValue() >= Integer.MAX_VALUE) throw invalid();
        return new ArchiveStructuredInput(body.path("expected_revision").intValue(), ((ObjectNode) body.get("fields")).deepCopy());
    }
    public void validateFor(int type) {
        Set<String> allowed = ALLOWED.get(type);
        if (allowed == null) throw invalid();
        fields.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)&&!SOURCE.contains(name)) throw invalid();
            JsonNode value = fields.get(name);
            switch (name) {
                case "performed_mileage","next_due_mileage","points","approved_passengers" -> { if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || name.equals("points")&&value.intValue()>1000 || name.equals("approved_passengers")&&value.intValue()>1000) throw invalid(); }
                case "service_items","repair_items","parts_used" -> {
                    if (!value.isArray() || value.isEmpty() || value.size() > 20) throw invalid();
                    var seen = new HashSet<String>();
                    for (JsonNode item : value) { checkText(item, 80); if (!seen.add(item.textValue())) throw invalid(); }
                }
                case "coverage_type" -> checkEnum(value, Set.of("COMPULSORY", "COMMERCIAL", "COMBINED", "OTHER"));
                case "inspection_result" -> checkEnum(value, Set.of("PASSED", "FAILED", "UNKNOWN"));
                case "violation_status" -> checkEnum(value,Set.of("PENDING","HANDLED","UNKNOWN"));
                case "fault_status" -> checkEnum(value,Set.of("ACTIVE","RESOLVED","UNKNOWN"));
                case "attention_level" -> checkEnum(value,Set.of("NORMAL","URGENT"));
                case "validity_type" -> checkEnum(value,Set.of("FINITE","LONG_TERM"));
                case "owner_confirmed" -> {if(!value.isBoolean())throw invalid();}
                case "penalty_amount","coverage_amount" -> {if(!value.isTextual()||!value.textValue().matches("(0|[1-9][0-9]{0,7})\\.[0-9]{2}"))throw invalid();}
                case "gross_mass_kg","unladen_mass_kg" -> {if(!value.isTextual()||!value.textValue().matches("(0|[1-9][0-9]{0,8})(\\.[0-9]{1,3})?"))throw invalid();}
                case "coverage_start","coverage_end","valid_until","service_date","repair_date","next_due_date","occurred_on","handled_on","inspection_date","register_date","issue_date","inspection_valid_until","birth_date","first_issue_date","valid_from","observed_on","cleared_on","source_checked_on" -> checkDate(value);
                default -> checkText(value, SOURCE.contains(name)?256:Set.of("description","fault_description","problem_description","result_notes","remarks","address").contains(name)?200:Set.of("policy_number","case_number","license_number","certificate_number").contains(name)?64:100);
            }
        });
        if (fields.has("coverage_start") && fields.has("coverage_end")
            && fields.get("coverage_start").textValue().compareTo(fields.get("coverage_end").textValue()) > 0) throw invalid();
        for(String[] pair:new String[][]{{"valid_from","valid_until"},{"birth_date","first_issue_date"},{"first_issue_date","valid_from"},{"register_date","issue_date"},{"occurred_on","handled_on"},{"observed_on","cleared_on"},{"inspection_date","valid_until"},{"service_date","next_due_date"}})if(fields.has(pair[0])&&fields.has(pair[1])&&fields.get(pair[0]).textValue().compareTo(fields.get(pair[1]).textValue())>0)throw invalid();
        if(fields.path("validity_type").asText().equals("LONG_TERM")&&fields.has("valid_until"))throw invalid();
        if(fields.path("owner_confirmed").asBoolean()){for(String required:confirmedFields(type))if(!fields.has(required))throw invalid();if(LocalDate.parse(fields.path("source_checked_on").asText()).isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw invalid();
            for(String k:Set.of("service_date","repair_date","occurred_on","handled_on","inspection_date","register_date","issue_date","birth_date","first_issue_date","observed_on","cleared_on"))if(fields.has(k)&&LocalDate.parse(fields.get(k).textValue()).isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw invalid();
            if(type==9&&fields.path("validity_type").asText().equals("FINITE")&&!fields.has("valid_until"))throw invalid();
            if(type==6&&fields.path("violation_status").asText().equals("HANDLED")&&!fields.has("handled_on"))throw invalid();
            if(type==10&&fields.path("fault_status").asText().equals("RESOLVED")&&!fields.has("cleared_on"))throw invalid();
        }
    }
    private static Set<String> confirmedFields(int type){var keys=new HashSet<>(Set.of("source_reference","source_checked_on"));keys.addAll(switch(type){case 1->Set.of("provider_name","service_items","performed_mileage","service_date");case 2->Set.of("provider_name","repair_items","repair_date");case 3->Set.of("insurer_name","policy_number","coverage_type","coverage_start","coverage_end");case 6->Set.of("case_number","occurred_on","location","description","violation_status");case 7->Set.of("inspection_station","inspection_result","inspection_date","valid_until");case 8->Set.of("owner_name","plate_number","vehicle_type","brand_model","vin","register_date","issue_date","issuing_authority");case 9->Set.of("holder_name","license_number","birth_date","first_issue_date","permitted_classes","valid_from","validity_type","issuing_authority");case 10->Set.of("fault_code","fault_description","observed_on","fault_status","attention_level");default->throw invalid();});return keys;}
    private static void checkText(JsonNode node, int maximum) {
        if (!node.isTextual() || node.textValue().isBlank() || !node.textValue().equals(node.textValue().strip())
            || node.textValue().codePointCount(0, node.textValue().length()) > maximum
            || node.textValue().codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE)) throw invalid();
    }
    private static void checkEnum(JsonNode node, Set<String> allowed) { if (!node.isTextual() || !allowed.contains(node.textValue())) throw invalid(); }
    private static void checkDate(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { if (LocalDate.parse(node.textValue()).getYear() < 1000) throw invalid(); }
        catch (RuntimeException e) { throw invalid(); }
    }
    public Map<String, Object> canonical() { return Map.of("expected_revision", revision, "fields", fields); }
    private static org.springframework.web.server.ResponseStatusException invalid() { return WriteIntegrityService.badRequest("请检查本类资料的字段、格式和版本"); }
}
