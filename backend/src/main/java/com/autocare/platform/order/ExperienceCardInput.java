package com.autocare.platform.order;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

final class ExperienceCardInput {
    static final String VERSION = "experience-v1";
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static JsonNode parse(String raw, boolean consent) {
        if (raw == null || raw.length() > 512) throw ReservationInput.bad();
        try { return validate(JSON.readTree(raw), consent); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw ReservationInput.bad(); }
    }
    static JsonNode validate(JsonNode b, boolean consent) {
        if (b == null || !b.isObject()) throw ReservationInput.bad();
        if (consent) {
            if (b.size() != 2 || !b.path("agree").isBoolean() || !b.path("agree").booleanValue()
                || !b.path("consent_version").isTextual() || !VERSION.equals(b.path("consent_version").textValue())) throw ReservationInput.bad();
        } else if (!b.isEmpty()) throw ReservationInput.bad();
        return b;
    }
}
