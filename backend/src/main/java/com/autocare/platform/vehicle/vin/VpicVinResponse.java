package com.autocare.platform.vehicle.vin;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** NHTSA fields remain external candidates; unknown provider fields are discarded. */
final class VpicVinResponse {
    static VinDecoder.Result parse(String vin, Integer year, JsonNode response) {
        if (response == null || !response.isObject() || !response.path("Count").isIntegralNumber()
                || !response.path("Count").canConvertToInt() || response.path("Count").asInt() != 1
                || !response.path("Results").isArray() || response.path("Results").size() != 1) throw new VinDecoder.Unavailable();
        var row = response.path("Results").get(0);
        if (!row.isObject() || !row.path("VIN").isTextual() || !vin.equals(row.path("VIN").asText())
                || !row.path("ErrorCode").isTextual() || !row.path("ErrorCode").asText().matches("[0-9]+(?:,\\s*[0-9]+)*")) throw new VinDecoder.Unavailable();
        if (!"0".equals(row.path("ErrorCode").asText())) return new VinDecoder.Result(VinDecoder.Status.NO_MATCH,"NHTSA_VPIC",Map.of(),true);
        var fields = new LinkedHashMap<String,String>();
        for (var names : List.of(List.of("Make","make"),List.of("Model","model"),List.of("ModelYear","model_year"),List.of("Trim","trim"),List.of("VehicleType","vehicle_type"))) {
            var value = row.get(names.get(0));
            if (value == null || value.isNull()) continue;
            if (!value.isTextual()) throw new VinDecoder.Unavailable();
            String text = value.asText();
            if (text.length() > 160 || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw new VinDecoder.Unavailable();
            text = text.strip();
            if (!text.isEmpty()) fields.put(names.get(1),text);
        }
        String decodedYear = fields.get("model_year");
        if (decodedYear != null) {
            if (!decodedYear.matches("[12][0-9]{3}") || Integer.parseInt(decodedYear) < 1980 || Integer.parseInt(decodedYear) > 2100
                    || year != null && !decodedYear.equals(year.toString())) throw new VinDecoder.Unavailable();
        }
        boolean complete = fields.keySet().containsAll(Set.of("make","model","model_year"));
        return new VinDecoder.Result(complete ? VinDecoder.Status.CANDIDATE : VinDecoder.Status.PARTIAL,"NHTSA_VPIC",fields,true);
    }
}
