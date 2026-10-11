package com.autocare.platform.order;
import java.time.*;import java.math.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CommissionRulesTest {
 @Test void threeCalendarMonthsClipMonthEndInShanghai(){assertEquals(Instant.parse("2026-04-30T15:59:59Z"),CommissionLedger.waivedUntil(Instant.parse("2026-01-31T15:59:59Z")));assertEquals(Instant.parse("2026-05-01T00:00:00Z"),CommissionLedger.waivedUntil(Instant.parse("2026-02-01T00:00:00Z")));}
 @Test void halfUpCentsHaveNoFloatingPointErrors(){assertEquals(new BigDecimal("0.30"),CommissionLedger.fee(new BigDecimal("10.00"),false));assertEquals(new BigDecimal("0.01"),CommissionLedger.fee(new BigDecimal("0.17"),false));assertEquals(new BigDecimal("0.00"),CommissionLedger.fee(new BigDecimal("0.16"),false));assertEquals(new BigDecimal("0.00"),CommissionLedger.fee(new BigDecimal("99999.99"),true));}
}
