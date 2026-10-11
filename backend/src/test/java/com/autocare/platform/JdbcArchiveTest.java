package com.autocare.platform;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.ArchiveInput;
import com.autocare.platform.vehicle.ArchiveReport;
import com.autocare.platform.vehicle.ArchiveReportFilter;
import com.autocare.platform.vehicle.ArchiveReportService;
import com.autocare.platform.vehicle.ArchiveService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcArchiveTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static final ObjectMapper mapper = new ObjectMapper();
    ArchiveService service;
    ArchiveReportService reports;
    VehicleOwner owner, other;

    @BeforeAll static void schema() throws Exception {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            for (String name : List.of("V001__baseline.sql", "V003__auth_lifecycle.sql", "V005__vehicle_archive_files.sql", "V005__vehicle_archive_files.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs", "sql", "migrations", name)));
        }
    }

    @BeforeEach void setUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_archive_audit");
        for (String table : List.of("vehicle_archive_file", "vehicle_archive", "audit_log", "idempotency_record", "file_object", "vehicle", "auth_session", "user"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES (1,'archive-test-one',1),(2,'archive-test-two',1)");
        owner = new VehicleOwner(1, UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        other = new VehicleOwner(2, UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        for (var actor : List.of(owner, other))
            jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
                + "VALUES (?,'user',?,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",
                actor.session(), actor.id(), UUID.randomUUID().toString());
        jdbc.update("INSERT INTO vehicle(id,user_id,current_mileage) VALUES (101,1,0),(102,1,0),(201,2,0)");
        jdbc.update("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) "
            + "VALUES (11,'user',1,'test-archive-11','image/jpeg',100,'CLEAN'),"
            + "(12,'user',1,'test-archive-12','image/png',100,'CLEAN'),"
            + "(21,'user',2,'test-archive-21','image/jpeg',100,'CLEAN'),"
            + "(31,'user',1,'test-archive-31','image/jpeg',100,'PENDING')");
        var transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        service = new ArchiveService(jdbc, new WriteIntegrityService(jdbc, mapper, transactions), mapper);
        reports = new ArchiveReportService(jdbc, mapper, transactions);
    }

    ArchiveInput input(long vehicle, List<Long> files) {
        return new ArchiveInput(vehicle, 1, LocalDate.of(2026, 10, 4), 12345, "保养", "更换机油", files, 3);
    }
    String key() { return UUID.randomUUID().toString(); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    int status(Runnable call) { return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value(); }

    @Test void createsOrderedImagesAndListsOnlySelectedVehicle() {
        var created = service.add(owner, key(), input(101, List.of(12L, 11L)));
        long id = created.path("data").path("archive_id").asLong();
        assertTrue(id > 0);
        assertEquals(List.of(12L, 11L), jdbc.query("SELECT file_id FROM vehicle_archive_file WHERE archive_id=? ORDER BY position",
            (rs, n) -> rs.getLong(1), id));
        var list = mapper.valueToTree(service.list(owner, 101, 1, 20));
        assertEquals(1, list.path("total").asInt());
        assertEquals("2026-10-04", list.path("list").get(0).path("recorded_date").asText());
        assertEquals(12, list.path("list").get(0).path("file_ids").get(0).asLong());
        assertEquals(0L, service.list(owner, 102, 1, 20).get("total"));
        assertEquals(404, status(() -> service.list(other, 101, 1, 20)));
        assertFalse(jdbc.queryForObject("SELECT after_state FROM audit_log", String.class).contains("更换机油"));
    }

    @Test void refusesForeignMissingAndUncleanPicturesWithoutPartialWrite() {
        for (long file : List.of(21L, 31L, 999L)) {
            assertEquals(404, status(() -> service.add(owner, key(), input(101, List.of(file)))));
            assertEquals(0, count("vehicle_archive"));
        }
        assertEquals(404, status(() -> service.add(owner, key(), input(201, List.of()))));
        jdbc.update("UPDATE file_object SET is_deleted=1 WHERE id=11");
        assertEquals(404, status(() -> service.add(owner, key(), input(101, List.of(11L)))));
        assertEquals(0, count("audit_log"));
    }

    @Test void replaysSameRequestAndRejectsChangedBody() {
        String key = key(); var first = service.add(owner, key, input(101, List.of()));
        assertEquals(first, service.add(owner, key.toUpperCase(), input(101, List.of())));
        assertEquals(400, status(() -> service.add(owner, key, input(102, List.of()))));
        assertEquals(1, count("vehicle_archive")); assertEquals(1, count("audit_log"));
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?", owner.session());
        assertEquals(401, status(() -> service.add(owner, key, input(101, List.of()))));
        assertEquals(401, status(() -> service.list(owner, 101, 1, 20)));
    }

    @Test void auditFailureRollsBackArchiveAndLinks() {
        jdbc.execute("CREATE TRIGGER reject_archive_audit BEFORE INSERT ON audit_log FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private audit failure'");
        assertEquals(503, status(() -> service.add(owner, key(), input(101, List.of(11L)))));
        assertEquals(0, count("vehicle_archive")); assertEquals(0, count("vehicle_archive_file"));
        assertEquals(0, count("idempotency_record"));
    }

    @Test void validatesInputAndPaginates() throws Exception {
        for (String bad : List.of("{}", "{\"vehicle_id\":101,\"archive_type\":8,\"recorded_date\":\"2026-10-04\",\"title\":\"x\"}",
            "{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-02-30\",\"title\":\"x\"}",
            "{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-10-04\",\"title\":\"x\",\"file_ids\":[11,11]}",
            "{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-10-04\",\"title\":\"x\",\"user_id\":1}"))
            assertEquals(400, status(() -> ArchiveInput.parse(read(bad))));
        service.add(owner, key(), input(101, List.of()));
        service.add(owner, key(), new ArchiveInput(101, 2, LocalDate.of(2026, 10, 5), null, "维修", "", List.of(), 3));
        var page = mapper.valueToTree(service.list(owner, 101, 1, 1));
        assertEquals(2, page.path("total").asInt());
        assertEquals(2, page.path("list").get(0).path("archive_type").asInt());
        assertEquals(400, status(() -> service.list(owner, 101, 1, 101)));
    }
    com.fasterxml.jackson.databind.JsonNode read(String value) {
        try { return mapper.readTree(value); } catch (Exception exception) { throw new AssertionError(exception); }
    }

    @Test void photoEntryRequiresCleanPictureAndAppearsWithManualRecords() {
        var manual = ArchiveInput.parse(read("{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-10-04\",\"title\":\"手动记录\"}"));
        assertEquals(3, manual.inputType());
        assertFalse(manual.canonical().containsKey("input_type"));
        assertEquals(400, status(() -> ArchiveInput.parse(read("{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-10-04\",\"title\":\"无图\",\"input_type\":1}"))));
        assertEquals(400, status(() -> ArchiveInput.parse(read("{\"vehicle_id\":101,\"archive_type\":1,\"recorded_date\":\"2026-10-04\",\"title\":\"语音\",\"input_type\":2}"))));
        service.add(owner, key(), manual);
        var photo = new ArchiveInput(101, 1, LocalDate.of(2026, 10, 5), null, "拍照记录", "", List.of(11L), 1);
        assertEquals(1, photo.canonical().get("input_type"));
        String requestKey = key();
        service.add(owner, requestKey, photo);
        assertEquals(400, status(() -> service.add(owner, requestKey, input(101, List.of(11L)))));
        assertEquals(404, status(() -> service.add(owner, key(), new ArchiveInput(101, 1, LocalDate.of(2026, 10, 5), null, "外部图片", "", List.of(21L), 1))));
        assertEquals(404, status(() -> service.add(owner, key(), new ArchiveInput(101, 1, LocalDate.of(2026, 10, 5), null, "未扫描图片", "", List.of(31L), 1))));
        var list = mapper.valueToTree(service.list(owner, 101, 1, 1));
        assertEquals(2, list.path("total").asInt());
        assertEquals(1, list.path("list").get(0).path("input_type").asInt());
        assertEquals(3, mapper.valueToTree(service.list(owner, 101, 2, 1)).path("list").get(0).path("input_type").asInt());
        assertEquals(2, count("vehicle_archive"));
        assertEquals(2, count("audit_log"));
        assertTrue(jdbc.queryForObject("SELECT after_state FROM audit_log ORDER BY id DESC LIMIT 1", String.class).contains("input_type"));
    }

    @Test void reportUsesTheSameOwnerVisibleSourcesAndDateRulesWithoutWriting() throws Exception {
        service.add(owner, key(), input(101, List.of()));
        service.add(owner, key(), new ArchiveInput(101, 1, LocalDate.of(2026, 10, 5), null,
            "拍照记录", "保养照片", List.of(11L), 1));
        insertArchive(101, 3, 3, null, "{\"version\":1,\"title\":\"日期未提供\",\"notes\":\"未知时间\"}");
        insertArchive(101, 2, 4, Date.valueOf("2026-10-05"), """
            {"version":2,"title":"施工记录","notes":"维修方案：检查发动机","mileage":null,
             "test_mode":true,"work_minutes":45,"no_parts":true,"parts_used":[],
             "submitted_at":"2026-10-05T01:00:00Z","signed_at":"2026-10-05T02:00:00Z",
             "redeemed_at":"2026-10-05T03:00:00Z","source":{"user_id":1,"order_id":501}}
            """);
        insertArchive(101, 2, 4, Date.valueOf("2026-10-05"),
            "{\"title\":\"他人施工\",\"source\":{\"user_id\":2},\"parts_used\":[],\"no_parts\":true}");
        insertArchive(101, 2, 2, Date.valueOf("2026-10-05"), "{\"title\":\"历史语音\"}");
        insertArchive(101, 1, 3, Date.valueOf("2026-10-05"), "{\"title\":\"已删除\"}");
        jdbc.update("UPDATE vehicle_archive SET is_deleted=1 WHERE content->>'$.title'='已删除'");
        jdbc.update("INSERT INTO vehicle_archive_file(archive_id,file_id,position) "
            + "SELECT id,12,0 FROM vehicle_archive WHERE vehicle_id=101 AND input_type=4 AND content->>'$.source.user_id'='1'");
        int auditsBefore = count("audit_log");

        ArchiveReport complete = reports.load(owner, new ArchiveReportFilter(101, null, null));
        assertEquals(4, complete.entries().size());
        assertEquals(0, complete.undatedCount());
        assertTrue(complete.entries().stream().anyMatch(row -> row.recordedDate() == null && row.title().equals("日期未提供")));
        ArchiveReport.Entry work = complete.entries().stream().filter(row -> row.inputType() == 4).findFirst().orElseThrow();
        assertTrue(work.testMode());
        assertEquals(45, work.workMinutes());
        assertTrue(work.noParts());
        assertEquals(1, work.photoCount());
        assertTrue(work.notes().contains("检查发动机"));

        ArchiveReport filtered = reports.load(owner, new ArchiveReportFilter(101,
            LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5)));
        assertEquals(2, filtered.entries().size());
        assertEquals(1, filtered.undatedCount());
        assertTrue(filtered.entries().stream().allMatch(row -> LocalDate.of(2026, 10, 5).equals(row.recordedDate())));
        assertEquals(auditsBefore, count("audit_log"));
        assertEquals(404, status(() -> reports.load(other, new ArchiveReportFilter(101, null, null))));
    }

    @Test void reportRejectsRevokedSessionsAndMoreThanFiveHundredRows() {
        String json = "{\"version\":1,\"title\":\"保养\",\"notes\":\"\"}";
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) rows.add(new Object[]{101, 1, json, 3, Date.valueOf("2026-10-04")});
        jdbc.batchUpdate("INSERT INTO vehicle_archive(vehicle_id,archive_type,content,input_type,recorded_at) VALUES(?,?,?,?,?)", rows);
        assertEquals(500, reports.load(owner, new ArchiveReportFilter(101, null, null)).entries().size());
        rows.clear();
        rows.add(new Object[]{101, 1, json, 3, Date.valueOf("2026-10-04")});
        jdbc.batchUpdate("INSERT INTO vehicle_archive(vehicle_id,archive_type,content,input_type,recorded_at) VALUES(?,?,?,?,?)", rows);
        assertEquals(409, status(() -> reports.load(owner, new ArchiveReportFilter(101, null, null))));
        assertEquals(0, count("audit_log"));
        jdbc.update("DELETE FROM vehicle_archive");
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?", owner.session());
        assertEquals(401, status(() -> reports.load(owner, new ArchiveReportFilter(101, null, null))));
    }

    private void insertArchive(long vehicleId, int archiveType, int inputType, Date date, String content) {
        jdbc.update("INSERT INTO vehicle_archive(vehicle_id,archive_type,content,input_type,recorded_at) VALUES(?,?,?,?,?)",
            vehicleId, archiveType, content, inputType, date);
    }
}
