package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record ArchiveStructuredInput(int revision, ObjectNode fields) {
    private static final Map<Integer, Set<String>> ALLOWED = Map.of(
        1, Set.of("provider_name", "service_items", "performed_mileage"),
        3, Set.of("insurer_name", "policy_number", "coverage_type", "coverage_start", "coverage_end"),
        7, Set.of("inspection_station", "inspection_result", "valid_until"));
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
            if (!allowed.contains(name)) throw invalid();
            JsonNode value = fields.get(name);
            switch (name) {
                case "performed_mileage" -> { if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid(); }
                case "service_items" -> {
                    if (!value.isArray() || value.isEmpty() || value.size() > 20) throw invalid();
                    var seen = new HashSet<String>();
                    for (JsonNode item : value) { checkText(item, 80); if (!seen.add(item.textValue())) throw invalid(); }
                }
                case "coverage_type" -> checkEnum(value, Set.of("COMPULSORY", "COMMERCIAL", "COMBINED", "OTHER"));
                case "inspection_result" -> checkEnum(value, Set.of("PASSED", "FAILED", "UNKNOWN"));
                case "coverage_start", "coverage_end", "valid_until" -> checkDate(value);
                default -> checkText(value, name.equals("policy_number") ? 64 : 100);
            }
        });
        if (fields.has("coverage_start") && fields.has("coverage_end")
            && fields.get("coverage_start").textValue().compareTo(fields.get("coverage_end").textValue()) > 0) throw invalid();
    }
    private static void checkText(JsonNode node, int maximum) {
        if (!node.isTextual() || node.textValue().isBlank() || !node.textValue().equals(node.textValue().strip())
            || node.textValue().codePointCount(0, node.textValue().length()) > maximum
            || node.textValue().codePoints().anyMatch(Character::isISOControl)) throw invalid();
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
