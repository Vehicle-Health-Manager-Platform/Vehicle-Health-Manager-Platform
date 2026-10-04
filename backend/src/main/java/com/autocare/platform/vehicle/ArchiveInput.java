package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ArchiveInput(long vehicleId, int archiveType, LocalDate recordedDate,
                           Integer mileage, String title, String notes, List<Long> fileIds) {
    private static final long MAX_SAFE_ID = 9007199254740991L;

    public static ArchiveInput parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        Set<String> allowed = Set.of("vehicle_id", "archive_type", "recorded_date", "mileage", "title", "notes", "file_ids");
        body.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw invalid(); });
        long vehicleId = id(body.path("vehicle_id"));
        JsonNode type = body.path("archive_type");
        if (!type.isIntegralNumber() || !type.canConvertToInt() || type.intValue() < 1 || type.intValue() > 7) throw invalid();
        JsonNode date = body.path("recorded_date");
        if (!date.isTextual() || !date.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        LocalDate recorded;
        try { recorded = LocalDate.parse(date.textValue()); }
        catch (DateTimeParseException exception) { throw invalid(); }
        if (recorded.getYear() < 1000) throw invalid();
        Integer mileage = null;
        if (body.has("mileage") && !body.get("mileage").isNull()) {
            JsonNode value = body.get("mileage");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
            mileage = value.intValue();
        }
        String title = string(body.path("title"), 80, false);
        String notes = body.has("notes") && !body.get("notes").isNull() ? string(body.get("notes"), 1000, true) : "";
        List<Long> files = new ArrayList<>();
        if (body.has("file_ids")) {
            JsonNode ids = body.get("file_ids");
            if (!ids.isArray() || ids.size() > 5) throw invalid();
            for (JsonNode item : ids) {
                long fileId = id(item);
                if (files.contains(fileId)) throw invalid();
                files.add(fileId);
            }
        }
        return new ArchiveInput(vehicleId, type.intValue(), recorded, mileage, title, notes, List.copyOf(files));
    }

    private static long id(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()
            || value.longValue() <= 0 || value.longValue() > MAX_SAFE_ID) throw invalid();
        return value.longValue();
    }

    private static String string(JsonNode value, int max, boolean empty) {
        if (!value.isTextual()) throw invalid();
        String result = value.textValue().strip();
        int length = result.codePointCount(0, result.length());
        if (length > max || (!empty && length == 0)) throw invalid();
        return result;
    }

    public Map<String, Object> canonical() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("vehicle_id", vehicleId);
        fields.put("archive_type", archiveType);
        fields.put("recorded_date", recordedDate.toString());
        fields.put("mileage", mileage);
        fields.put("title", title);
        fields.put("notes", notes);
        fields.put("file_ids", fileIds);
        return fields;
    }

    private static org.springframework.web.server.ResponseStatusException invalid() {
        return WriteIntegrityService.badRequest("档案信息无效，请检查日期、里程、标题和图片");
    }
}
