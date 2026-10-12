package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveShareTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void rejectsUnknownFieldsBadDatesAndUnsupportedTtl() throws Exception {
        for (String body : List.of("{\"vehicle_id\":1,\"expires_in_hours\":2}", "{\"vehicle_id\":1,\"from\":\"2026-02-30\",\"to\":\"2026-03-01\"}",
            "{\"vehicle_id\":1,\"from\":\"2026-01-01\"}", "{\"vehicle_id\":1,\"receiver\":2}", "{\"vehicle_id\":1.5}", "{\"vehicle_id\":9007199254740992}")) {
            var node = mapper.readTree(body);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> ArchiveShareInput.parse(node)).getStatusCode().value());
        }
        assertEquals(24, ArchiveShareInput.parse(mapper.readTree("{\"vehicle_id\":1}")).hours());
        for (int hours : List.of(1, 24, 168)) assertEquals(hours, ArchiveShareInput.parse(mapper.readTree("{\"vehicle_id\":1,\"expires_in_hours\":" + hours + "}")).hours());
    }
    @Test void projectsOnlyFixedFieldsEvenWhenPrivateReportContainsSensitiveText() {
        var report = new ArchiveReport("客户车型含手机号", "粤B12345", "sensitive-vin", 12, null, null, 0, Instant.now(),
            List.of(new ArchiveReport.Entry(1, 3, LocalDate.of(2026,10,12), "secret title", "secret note", 13, 2, false, 0, false,
                List.of(new ArchiveReport.Part("secret part", "secret model", "secret brand", 1)), "signature", "signature", "order")));
        String summary = ArchiveShareSummary.from(report, mapper).toString();
        for (String secret : List.of("客户", "粤B", "sensitive", "secret", "signature", "photo", "parts", "order")) assertFalse(summary.contains(secret));
        assertEquals(13, ArchiveShareSummary.read(summary, mapper).path("entries").get(0).path("mileage").asInt());
        var corrupt = ArchiveShareSummary.from(report, mapper); corrupt.put("notes", "unexpected secret");
        assertThrows(ResponseStatusException.class, () -> ArchiveShareSummary.read(corrupt.toString(), mapper));
    }
    @Test void domainSeparatedCapabilitiesAreUnpredictableFromPublicIdAndRotate() {
        var first = new ArchiveShareTokens("a".repeat(32)); var second = new ArchiveShareTokens("b".repeat(32));
        String id = UUID.randomUUID().toString(); String token = first.derive(id);
        assertTrue(ArchiveShareTokens.valid(token)); assertEquals(token, first.derive(id));
        assertNotEquals(token, first.derive(UUID.randomUUID().toString())); assertNotEquals(token, second.derive(id));
        assertTrue(first.matches(id, ArchiveShareTokens.hash(token))); assertFalse(second.matches(id, ArchiveShareTokens.hash(token)));
        assertNotEquals(token, ArchiveShareTokens.hash(token)); assertFalse(ArchiveShareTokens.valid(token.toUpperCase()));
    }
}
