package com.autocare.platform;

import com.autocare.platform.file.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcPrivateUploadTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    JdbcFileMetadataRepository repository;
    final List<String> saved = new ArrayList<>(), deleted = new ArrayList<>();
    final PrivateObjectStore store = new PrivateObjectStore() {
        public boolean isPrivate() { return true; }
        public void put(String key, String type, byte[] bytes) { saved.add(key); }
        public void delete(String key) { deleted.add(key); }
    };
    @BeforeAll static void schema() throws Exception {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..", "docs", "sql", "migrations", "V001__baseline.sql")));
        }
    }
    @BeforeEach void setUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_file"); jdbc.update("DELETE FROM file_object");
        repository = new JdbcFileMetadataRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    PrivateUploadService service() { return new PrivateUploadService(bytes -> VirusScanner.Result.CLEAN, store, repository); }
    long upload(FileMetadataRepository.Actor actor) {
        return service().upload(actor, "not-retained.png", new ByteArrayInputStream(PrivateUploadTest.PNG)).id();
    }
    @Test void committedMetadataContainsOnlyApprovedFieldsAndUsesActorTypeIsolation() {
        long id = upload(PrivateUploadTest.OWNER);
        var metadata = service().readable(PrivateUploadTest.OWNER, id);
        assertEquals("CLEAN", metadata.scanStatus()); assertEquals("image/png", metadata.contentType());
        assertEquals(PrivateUploadTest.PNG.length, metadata.sizeBytes()); assertEquals(saved.get(0), metadata.objectKey());
        assertEquals("user", jdbc.queryForObject("SELECT owner_type FROM file_object WHERE id=?", String.class, id));
        assertEquals(1001L, jdbc.queryForObject("SELECT owner_id FROM file_object WHERE id=?", Long.class, id));
        assertEquals(UploadException.Reason.NOT_FOUND, assertThrows(UploadException.class,
            () -> service().readable(new FileMetadataRepository.Actor("user", 2001), id)).reason());
        var staff = new FileMetadataRepository.Actor("staff_account", 1001);
        assertEquals(UploadException.Reason.NOT_FOUND, assertThrows(UploadException.class, () -> service().readable(staff, id)).reason());
        long staffId = upload(staff); assertEquals(staffId, service().readable(staff, staffId).id());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        assertTrue(deleted.isEmpty());
    }
    @Test void deletedAndNonCleanRowsAreNeverReadable() {
        long id = upload(PrivateUploadTest.OWNER);
        for (String state : List.of("PENDING", "INFECTED", "ERROR", "UNKNOWN")) {
            jdbc.update("UPDATE file_object SET scan_status=? WHERE id=?", state, id);
            assertEquals(UploadException.Reason.NOT_READABLE, assertThrows(UploadException.class,
                () -> service().readable(PrivateUploadTest.OWNER, id)).reason());
        }
        jdbc.update("UPDATE file_object SET scan_status='CLEAN',is_deleted=1 WHERE id=?", id);
        assertEquals(UploadException.Reason.NOT_FOUND, assertThrows(UploadException.class,
            () -> service().readable(PrivateUploadTest.OWNER, id)).reason());
    }
    @Test void databaseInsertFailureCompensatesObjectAndCanRetry() {
        jdbc.execute("CREATE TRIGGER reject_file BEFORE INSERT ON file_object FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private database fault'");
        var error = assertThrows(UploadException.class, () -> upload(PrivateUploadTest.OWNER));
        assertEquals(UploadException.Reason.UNAVAILABLE, error.reason()); assertNull(error.getCause());
        assertEquals(saved, deleted); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        jdbc.execute("DROP TRIGGER reject_file"); long id = upload(PrivateUploadTest.OWNER);
        assertEquals(id, service().readable(PrivateUploadTest.OWNER, id).id());
        assertNotEquals(saved.get(0), saved.get(1));
    }
    @Test void failureBeforeCommitRollsBackMetadataAndCompensatesObject() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource()) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                throw new TransactionSystemException("injected failure before commit");
            }
        };
        manager.setRollbackOnCommitFailure(true);
        repository = new JdbcFileMetadataRepository(jdbc, manager);
        assertEquals(UploadException.Reason.UNAVAILABLE, assertThrows(UploadException.class,
            () -> upload(PrivateUploadTest.OWNER)).reason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        assertEquals(saved, deleted);
    }
}
