package com.autocare.platform.vehicle;

import java.util.Map;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveReportFilterTest {
    @Test void acceptsOnlyOneVehicleAndAnOptionalCompleteInclusiveDatePair() {
        var all = ArchiveReportFilter.parse(Map.of("vehicle_id", new String[]{"12"}));
        assertEquals(12, all.vehicleId());
        assertNull(all.from());
        var range = ArchiveReportFilter.parse(Map.of("vehicle_id", new String[]{"12"},
            "from", new String[]{"2026-10-01"}, "to", new String[]{"2026-10-31"}));
        assertEquals("2026-10-01", range.from().toString());
        assertEquals("2026-10-31", range.to().toString());
    }

    @Test void rejectsDuplicatesUnknownsPartialRangesAndInvalidDates() {
        assertBad(Map.of("vehicle_id", new String[]{"1", "2"}));
        assertBad(Map.of("vehicle_id", new String[]{"1"}, "page", new String[]{"1"}));
        assertBad(Map.of("vehicle_id", new String[]{"1"}, "from", new String[]{"2026-01-01"}));
        assertBad(Map.of("vehicle_id", new String[]{"1"}, "from", new String[]{"2026-02-30"}, "to", new String[]{"2026-03-01"}));
        assertBad(Map.of("vehicle_id", new String[]{"1"}, "from", new String[]{"2026-02-01"}, "to", new String[]{"2026-01-31"}));
        assertBad(Map.of("vehicle_id", new String[]{"9007199254740992"}));
    }

    @Test void acceptsLastCalendarDateWithoutInventingAnOutOfRangeSuccessor() {
        var filter = ArchiveReportFilter.parse(Map.of("vehicle_id", new String[]{"1"},
            "from", new String[]{"9999-12-31"}, "to", new String[]{"9999-12-31"}));
        assertEquals(LocalDate.of(9999, 12, 31), filter.to());
    }

    private static void assertBad(Map<String, String[]> parameters) {
        var error = assertThrows(ResponseStatusException.class, () -> ArchiveReportFilter.parse(parameters));
        assertEquals(400, error.getStatusCode().value());
    }
}
