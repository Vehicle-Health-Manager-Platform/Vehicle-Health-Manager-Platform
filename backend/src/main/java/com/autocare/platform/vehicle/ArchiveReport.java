package com.autocare.platform.vehicle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ArchiveReport(
    String modelName,
    String maskedPlate,
    String maskedVin,
    Integer registeredMileage,
    LocalDate from,
    LocalDate to,
    int undatedCount,
    Instant generatedAt,
    List<Entry> entries
) {
    public ArchiveReport { entries = List.copyOf(entries); }

    public record Entry(
        int archiveType,
        int inputType,
        LocalDate recordedDate,
        String title,
        String notes,
        Integer mileage,
        int photoCount,
        boolean testMode,
        int workMinutes,
        boolean noParts,
        List<Part> parts,
        String submittedAt,
        String signedAt,
        String redeemedAt
    ) {
        public Entry { parts = List.copyOf(parts); }
    }

    public record Part(String name, String model, String brand, int quantity) {}
}
