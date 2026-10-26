package com.autocare.platform.vehicle.vin;

import java.util.*;

/** Internal candidate protocol; never a catalog match or vehicle registration. */
public interface VinDecoder {
    enum Status { CANDIDATE, PARTIAL, NO_MATCH }

    record Result(Status status, String source, Map<String,String> fields, boolean requiresConfirmation) {
        public Result {
            Objects.requireNonNull(status);
            if (source == null || !source.matches("[A-Z0-9_]{1,40}") || !requiresConfirmation || fields == null
                    || !Set.of("make", "model", "model_year", "trim", "vehicle_type").containsAll(fields.keySet())
                    || status == Status.NO_MATCH && !fields.isEmpty()) throw new IllegalArgumentException("Invalid VIN candidate protocol");
            boolean complete = fields.keySet().containsAll(Set.of("make","model","model_year"));
            if (status == Status.CANDIDATE && !complete || status == Status.PARTIAL && complete
                    || fields.values().stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 160 || v.codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid VIN candidate protocol");
            fields = Map.copyOf(fields);
        }
    }

    Result decode(String vin, Integer modelYear, boolean consent);

    static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("请输入完整17位VIN，不含I、O、Q");
        String value = raw.strip();
        // Check ASCII before uppercasing: Unicode expansions must not create a VIN.
        if (!value.matches("[A-HJ-NPR-Za-hj-npr-z0-9]{17}")) throw new IllegalArgumentException("请输入完整17位VIN，不含I、O、Q");
        return value.toUpperCase(Locale.ROOT);
    }

    static void checkYear(Integer year) {
        if (year != null && (year < 1980 || year > 2100)) throw new IllegalArgumentException("请提供有效年款提示");
    }

    /** Deliberately has no upstream message, cause, URL or VIN. */
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("VIN解码未配置或暂不可用，请手动确认车型"); }
    }
}
