package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ArchiveReminderInput(int revision, boolean active, LocalDate date, Integer mileage, boolean replaceSource) {
    public static ArchiveReminderInput parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        var allowed = Set.of("expected_revision", "active", "due_date", "due_mileage", "replace_source");
        body.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw invalid(); });
        if (!body.path("expected_revision").isIntegralNumber() || !body.path("expected_revision").canConvertToInt()
            || body.path("expected_revision").intValue() < 0 || body.path("expected_revision").intValue() >= Integer.MAX_VALUE
            || !body.path("active").isBoolean()) throw invalid();
        boolean active = body.path("active").booleanValue();
        LocalDate date = null;
        if (body.hasNonNull("due_date")) {
            String value = body.get("due_date").textValue();
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
            try { date = LocalDate.parse(value); } catch (RuntimeException e) { throw invalid(); }
            if (date.getYear() < 1000) throw invalid();
        }
        Integer mileage = null;
        if (body.hasNonNull("due_mileage")) {
            var value = body.get("due_mileage");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
            mileage = value.intValue();
        }
        boolean replace = false;
        if (body.has("replace_source")) {
            if (!body.get("replace_source").isBoolean()) throw invalid();
            replace = body.get("replace_source").booleanValue();
        }
        if (active ? date == null && mileage == null : body.has("due_date") || body.has("due_mileage") || replace) throw invalid();
        return new ArchiveReminderInput(body.path("expected_revision").intValue(), active, date, mileage, replace);
    }
    public void validateFor(int type) { if (active && type != 1 && (date == null || mileage != null)) throw invalid(); }
    public Map<String, Object> canonical() {
        var result = new LinkedHashMap<String, Object>();
        result.put("expected_revision", revision); result.put("active", active);
        if (active) { result.put("due_date", date == null ? null : date.toString()); result.put("due_mileage", mileage); result.put("replace_source", replaceSource); }
        return result;
    }
    private static org.springframework.web.server.ResponseStatusException invalid() { return WriteIntegrityService.badRequest("请检查目标日期、里程和设置版本"); }
}
