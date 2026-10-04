package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record VehicleInput(long modelId, int mileage, String plate, String vin) {
    public static VehicleInput parse(JsonNode body) {
        Set<String> fields = Set.of("add_type", "model_id", "current_mileage", "plate_no", "vin");
        if (body == null || !body.isObject()) throw invalid();
        body.fieldNames().forEachRemaining(field -> { if (!fields.contains(field)) throw invalid(); });
        if (!body.path("add_type").isIntegralNumber() || !body.path("add_type").canConvertToInt() || body.path("add_type").intValue() != 4
            || !body.path("model_id").isIntegralNumber() || !body.path("model_id").canConvertToLong()
            || body.path("model_id").longValue() <= 0 || body.path("model_id").longValue() > 9007199254740991L) throw invalid();
        int mileage = 0;
        if (body.has("current_mileage")) {
            var value = body.get("current_mileage");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw invalid();
            mileage = value.intValue();
        }
        String plate = text(body, "plate_no"), vin = text(body, "vin");
        if (!plate.isEmpty() && !plate.matches("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z][A-Z0-9]{5,6}")) throw invalid();
        if (!vin.isEmpty() && !vin.matches("[A-HJ-NPR-Z0-9]{17}")) throw invalid();
        return new VehicleInput(body.get("model_id").longValue(), mileage, plate, vin);
    }
    private static String text(JsonNode body, String name) {
        if (!body.has(name)) return "";
        if (!body.get(name).isTextual() || body.get(name).textValue().length() > 32) throw invalid();
        return body.get(name).textValue().trim().toUpperCase(Locale.ROOT);
    }
    public Map<String, Object> canonical() {
        return Map.of("add_type", 4, "model_id", modelId, "current_mileage", mileage, "plate_no", plate, "vin", vin);
    }
    private static org.springframework.web.server.ResponseStatusException invalid() {
        return WriteIntegrityService.badRequest("请检查车型、里程、车牌和17位VIN；仅支持手动录入");
    }
}
