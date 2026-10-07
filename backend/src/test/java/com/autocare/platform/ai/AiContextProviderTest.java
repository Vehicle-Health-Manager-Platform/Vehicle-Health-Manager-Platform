package com.autocare.platform.ai;

import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AiContextProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void formatsGroundedContextWithoutIdentityFields() {
        String summary = AiContextProvider.describe("宝马 3系 2021 运动版", 48000, "燃油",
            List.of(new AiContextProvider.Archive("2026-09-20", 1, "更换机油机滤", "使用全合成机油", 47500),
                new AiContextProvider.Archive("2026-06-02", 2, "更换刹车片", "", null)));
        assertTrue(summary.startsWith("车型：宝马 3系 2021 运动版\n当前里程：48000 km\n动力类型：燃油\n"), summary);
        assertTrue(summary.contains("- 2026-09-20 保养：更换机油机滤（里程 47500 km）；备注：使用全合成机油"), summary);
        assertTrue(summary.endsWith("- 2026-06-02 维修：更换刹车片"), summary);
        assertFalse(summary.contains("车牌"));
        assertFalse(summary.contains("VIN"));
    }

    @Test void tellsTheModelWhenNoArchiveExists() {
        String summary = AiContextProvider.describe("未知车型", 0, "", List.of());
        assertTrue(summary.contains("车型：未知车型"), summary);
        assertTrue(summary.contains("该车暂无可用的历史养护档案。"), summary);
        assertFalse(summary.contains("动力类型"));
    }

    @Test void handlesMissingVehicleNameAndMapsUnknownArchiveTypes() {
        String summary = AiContextProvider.describe(null, 12, null,
            List.of(new AiContextProvider.Archive("", 0, "", "", null)));
        assertTrue(summary.contains("车型：未知"), summary);
        assertTrue(summary.contains("-  其他：未填写标题"), summary);
    }

    @Test void readsArchiveContentSafely() {
        AiContextProvider.Archive parsed = AiContextProvider.parseArchive(mapper, 3,
            "{\"title\":\"交强险续保\",\"notes\":\"2027-01 到期\",\"mileage\":51000}", "2026-09-30");
        assertEquals("交强险续保", parsed.title());
        assertEquals("2027-01 到期", parsed.notes());
        assertEquals(51000, parsed.mileage());
        assertEquals(3, parsed.archiveType());

        for (String broken : List.of("", "null", "not-json", "[]", "{}")) {
            AiContextProvider.Archive empty = AiContextProvider.parseArchive(mapper, 1, broken, null);
            assertEquals("", empty.title());
            assertEquals("", empty.notes());
            assertNull(empty.mileage());
            assertEquals("", empty.date());
        }
    }

    @Test void collapsesWhitespaceInFieldsAndCapsLongContext() {
        assertEquals("换 机油 记录", AiContextProvider.singleLine("  换\n机油\t 记录  ", 40));
        assertEquals(4, AiContextProvider.singleLine("换机油记录", 3).length());
        assertTrue(AiContextProvider.singleLine("换机油记录", 3).endsWith("…"));
        String long1 = "备".repeat(5000);
        assertEquals(1801, AiContextProvider.cap(long1, 1800).length());
    }

    @Test void rejectsInvalidVehicleIdBeforeQuerying() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiContextProvider provider = new AiContextProvider(jdbc, mapper);
        VehicleOwner owner = new VehicleOwner(1, "session", Instant.now().plusSeconds(60));
        for (long bad : List.of(0L, -1L, 9007199254740992L)) {
            var error = assertThrows(ResponseStatusException.class, () -> provider.forVehicle(owner, bad));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        verifyNoInteractions(jdbc);
    }
}
