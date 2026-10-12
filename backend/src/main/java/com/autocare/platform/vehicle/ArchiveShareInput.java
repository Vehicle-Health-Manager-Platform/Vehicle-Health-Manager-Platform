package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Set;

public record ArchiveShareInput(ArchiveReportFilter filter, int hours, JsonNode request) {
    public static ArchiveShareInput parse(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        var allowed = Set.of("vehicle_id", "from", "to", "expires_in_hours");
        body.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
        if (!body.path("vehicle_id").isIntegralNumber() || !body.path("vehicle_id").canConvertToLong()) throw invalid();
        var params = new HashMap<String, String[]>();
        params.put("vehicle_id", new String[]{body.path("vehicle_id").asText()});
        for (var name : Set.of("from", "to")) if (body.has(name)) {
            if (!body.get(name).isTextual()) throw invalid();
            params.put(name, new String[]{body.get(name).asText()});
        }
        int hours = 24;
        if (body.has("expires_in_hours")) {
            if (!body.get("expires_in_hours").isIntegralNumber() || !body.get("expires_in_hours").canConvertToInt()) throw invalid();
            hours = body.get("expires_in_hours").intValue();
        }
        if (!Set.of(1, 24, 168).contains(hours)) throw invalid();
        return new ArchiveShareInput(ArchiveReportFilter.parse(params), hours, body.deepCopy());
    }
    private static org.springframework.web.server.ResponseStatusException invalid() {
        return WriteIntegrityService.badRequest("分享参数无效，请检查日期及有效期");
    }
}
