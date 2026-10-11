package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

public record ArchiveReportFilter(long vehicleId, LocalDate from, LocalDate to) {
    private static final Set<String> ALLOWED = Set.of("vehicle_id", "from", "to");
    private static final long MAX_SAFE_ID = 9007199254740991L;

    public static ArchiveReportFilter parse(Map<String, String[]> parameters) {
        if (parameters == null || !ALLOWED.containsAll(parameters.keySet())) throw invalid();
        long vehicleId = positiveId(one(parameters, "vehicle_id"));
        boolean hasFrom = parameters.containsKey("from");
        boolean hasTo = parameters.containsKey("to");
        if (hasFrom != hasTo) throw invalid();
        if (!hasFrom) return new ArchiveReportFilter(vehicleId, null, null);
        LocalDate from = date(one(parameters, "from"));
        LocalDate to = date(one(parameters, "to"));
        if (from.isAfter(to)) throw invalid();
        return new ArchiveReportFilter(vehicleId, from, to);
    }

    private static String one(Map<String, String[]> parameters, String key) {
        String[] values = parameters.get(key);
        if (values == null || values.length != 1 || values[0] == null || values[0].isBlank()) throw invalid();
        return values[0];
    }

    private static long positiveId(String raw) {
        if (!raw.matches("[1-9][0-9]{0,15}")) throw invalid();
        try {
            long value = Long.parseLong(raw);
            if (value > MAX_SAFE_ID) throw invalid();
            return value;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static LocalDate date(String raw) {
        if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try {
            LocalDate value = LocalDate.parse(raw);
            if (value.getYear() < 1000) throw invalid();
            return value;
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static org.springframework.web.server.ResponseStatusException invalid() {
        return WriteIntegrityService.badRequest("档案报告参数无效，请检查车辆和日期范围");
    }
}
