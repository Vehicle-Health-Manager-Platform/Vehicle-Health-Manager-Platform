package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deliberate allowlist: never copy the private report as JSON. */
public final class ArchiveShareSummary {
    private ArchiveShareSummary() {}
    public static ObjectNode from(ArchiveReport report, ObjectMapper mapper) {
        ObjectNode summary = mapper.createObjectNode();
        summary.put("version", 1);
        summary.put("generated_at", report.generatedAt().toString());
        summary.put("from", report.from() == null ? null : report.from().toString());
        summary.put("to", report.to() == null ? null : report.to().toString());
        if (report.registeredMileage() == null) summary.putNull("registered_mileage");
        else summary.put("registered_mileage", report.registeredMileage());
        summary.put("undated_count", report.undatedCount());
        var rows = summary.putArray("entries");
        for (var row : report.entries()) {
            ObjectNode item = rows.addObject();
            item.put("archive_type", row.archiveType());
            item.put("input_type", row.inputType());
            item.put("recorded_date", row.recordedDate() == null ? null : row.recordedDate().toString());
            if (row.mileage() == null) item.putNull("mileage"); else item.put("mileage", row.mileage());
            item.put("work_minutes", row.workMinutes());
            item.put("test_mode", row.testMode());
        }
        return summary;
    }
    public static JsonNode read(String stored, ObjectMapper mapper) {
        try {
            JsonNode value = mapper.readTree(stored);
            var keys = java.util.Set.of("version", "generated_at", "from", "to", "registered_mileage", "undated_count", "entries");
            var rowKeys = java.util.Set.of("archive_type", "input_type", "recorded_date", "mileage", "work_minutes", "test_mode");
            if (!value.isObject() || value.size() != keys.size() || value.path("version").asInt() != 1
                || !value.path("entries").isArray() || value.path("entries").size() > 500) throw new IllegalArgumentException();
            value.fieldNames().forEachRemaining(k -> { if (!keys.contains(k)) throw new IllegalArgumentException(); });
            java.time.Instant.parse(value.path("generated_at").textValue());
            date(value.get("from")); date(value.get("to")); number(value.get("registered_mileage"), true, Integer.MAX_VALUE);
            number(value.get("undated_count"), false, Integer.MAX_VALUE);
            for (var row : value.path("entries")) {
                if (!row.isObject() || row.size() != rowKeys.size()) throw new IllegalArgumentException();
                row.fieldNames().forEachRemaining(k -> { if (!rowKeys.contains(k)) throw new IllegalArgumentException(); });
                number(row.get("archive_type"), false, 7); number(row.get("input_type"), false, 4);
                if (row.path("archive_type").asInt() < 1 || !java.util.Set.of(1, 3, 4).contains(row.path("input_type").asInt())) throw new IllegalArgumentException();
                date(row.get("recorded_date")); number(row.get("mileage"), true, Integer.MAX_VALUE);
                number(row.get("work_minutes"), false, 1440);
                if (!row.path("test_mode").isBoolean()) throw new IllegalArgumentException();
            }
            return value;
        } catch (Exception e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "分享摘要暂不可用"); }
    }
    private static void date(JsonNode node) {
        if (node == null) throw new IllegalArgumentException();
        if (!node.isNull()) {
            if (!node.isTextual() || !node.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            java.time.LocalDate.parse(node.textValue());
        }
    }
    private static void number(JsonNode node, boolean nullable, int max) {
        if (node == null || !(nullable && node.isNull()) && (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0 || node.intValue() > max)) throw new IllegalArgumentException();
    }
}
