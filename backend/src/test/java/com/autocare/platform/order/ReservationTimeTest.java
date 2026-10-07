package com.autocare.platform.order;
import java.sql.Timestamp;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReservationTimeTest {
    @Test void mysqlDatetimeAndTimestampBothUseUtc(){
        Instant instant=Instant.parse("2026-10-08T02:00:00Z");
        assertEquals(instant,ReservationStore.instant(Timestamp.from(instant)));
        assertEquals(instant,ReservationStore.instant(LocalDateTime.parse("2026-10-08T02:00:00")));
    }
}
