package com.autocare.platform.vehicle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveReminderTest {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void boundariesAreInclusiveAndEitherTargetTriggers() {
        var today = LocalDate.of(2026,10,12);
        assertEquals(List.of(), ArchiveReminderStatus.triggered(today, 999, today.plusDays(1), 1000));
        assertEquals(List.of("DATE"), ArchiveReminderStatus.triggered(today, 999, today, 1000));
        assertEquals(List.of("MILEAGE"), ArchiveReminderStatus.triggered(today, 1000, today.plusDays(1), 1000));
        assertEquals(List.of("DATE", "MILEAGE"), ArchiveReminderStatus.triggered(today, 1001, today.minusDays(1), 1000));
        assertEquals(List.of(), ArchiveReminderStatus.triggered(today, null, null, 1000));
    }
    @Test void strictTargetsDisableAndVersionValidation() throws Exception {
        for (String body : List.of("{}", "{\"expected_revision\":0,\"active\":true}",
            "{\"expected_revision\":-1,\"active\":false}", "{\"expected_revision\":0,\"active\":true,\"due_date\":\"2026-02-30\"}",
            "{\"expected_revision\":0,\"active\":true,\"due_mileage\":2147483648}", "{\"expected_revision\":1,\"active\":false,\"due_date\":null}",
            "{\"expected_revision\":1,\"active\":false,\"secret\":1}")) {
            var node = mapper.readTree(body); assertThrows(ResponseStatusException.class, () -> ArchiveReminderInput.parse(node));
        }
        var mile = ArchiveReminderInput.parse(mapper.readTree("{\"expected_revision\":0,\"active\":true,\"due_mileage\":0}"));
        mile.validateFor(1); assertThrows(ResponseStatusException.class, () -> mile.validateFor(3));
        var date = ArchiveReminderInput.parse(mapper.readTree("{\"expected_revision\":0,\"active\":true,\"due_date\":\"2026-10-12\"}"));
        date.validateFor(3); date.validateFor(7);
        assertFalse(ArchiveReminderInput.parse(mapper.readTree("{\"expected_revision\":1,\"active\":false}")).active());
    }
}
