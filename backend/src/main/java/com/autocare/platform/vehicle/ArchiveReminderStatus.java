package com.autocare.platform.vehicle;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ArchiveReminderStatus {
    private ArchiveReminderStatus() {}
    public static List<String> triggered(LocalDate today, Integer currentMileage, LocalDate date, Integer mileage) {
        var triggers = new ArrayList<String>();
        if (date != null && !today.isBefore(date)) triggers.add("DATE");
        if (mileage != null && currentMileage != null && currentMileage >= mileage) triggers.add("MILEAGE");
        return List.copyOf(triggers);
    }
}
