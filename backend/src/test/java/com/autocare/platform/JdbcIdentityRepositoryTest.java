package com.autocare.platform;

import com.autocare.platform.gateway.identity.JdbcIdentityRepository;
import com.autocare.platform.gateway.identity.JdbcAuthSessionRepository;
import com.autocare.platform.gateway.identity.AuthSessionRepository;
import com.autocare.platform.gateway.identity.AuthRateLimiter;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcIdentityRepositoryTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    JdbcTemplate jdbc;
    DriverManagerDataSource dataSource;
    JdbcIdentityRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS auth_session");
        jdbc.execute("DROP TABLE IF EXISTS auth_rate_limit");
        jdbc.execute("DROP TABLE IF EXISTS staff_wechat_identity");
        jdbc.execute("DROP TABLE IF EXISTS staff_account");
        jdbc.execute("DROP TABLE IF EXISTS merchant");
        jdbc.execute("DROP TABLE IF EXISTS `user`");
        jdbc.execute("CREATE TABLE `user` (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, openid VARCHAR(128) UNIQUE, "
            + "phone VARCHAR(20) UNIQUE, status TINYINT NOT NULL DEFAULT 1, is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE merchant (id BIGINT UNSIGNED PRIMARY KEY, status TINYINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE staff_account (id BIGINT UNSIGNED PRIMARY KEY, merchant_id BIGINT UNSIGNED, "
            + "role VARCHAR(24), status VARCHAR(24), is_deleted TINYINT NOT NULL DEFAULT 0, employee_code_hash CHAR(64) UNIQUE)");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                Path.of("..", "docs", "sql", "migrations", "V002__staff_wechat_identity.sql")));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                Path.of("..", "docs", "sql", "migrations", "V003__auth_lifecycle.sql")));
        }
        repository = new JdbcIdentityRepository(jdbc);
    }

    @Test
    void simultaneousFirstLoginCreatesOnlyOneOwner() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { ready.countDown(); start.await(); return repository.createOwnerOrRead("same-openid").id(); });
            var second = workers.submit(() -> { ready.countDown(); start.await(); return repository.createOwnerOrRead("same-openid").id(); });
            ready.await();
            start.countDown();
            assertEquals(first.get(), second.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `user` WHERE openid='same-openid'", Integer.class));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void bindingUsesEmployeeRoleMerchantStatusAndUniqueConstraints() throws Exception {
        String code = "high-entropy-employee-code-123";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(code.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO merchant (id,status) VALUES (9,1)");
        jdbc.update("INSERT INTO staff_account (id,merchant_id,role,status,employee_code_hash) VALUES (31,9,'TECHNICIAN','ACTIVE',?)", hash);
        var bound = repository.bindTechnician("app", "openid-tech", code);
        assertTrue(bound.active());
        assertEquals(31, bound.staffId());
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> repository.bindTechnician("app", "second-openid", code)).getStatusCode().value());
        jdbc.update("UPDATE merchant SET status=2 WHERE id=9");
        assertFalse(repository.technicianByBindingId(bound.id()).orElseThrow().active());
        jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED', unbound_at=UTC_TIMESTAMP() WHERE id=?", bound.id());
        assertTrue(repository.technicianByBindingId(bound.id()).isEmpty());
    }

    @Test
    void refreshRotationRejectsReplayAndLogoutRevokesAccess() {
        var sessions = new JdbcAuthSessionRepository(jdbc);
        var session = new AuthSessionRepository.Session("00000000-0000-0000-0000-000000000001",
            "user", 1001, "OWNER", "app", null, null);
        sessions.create(session, "first-hash", Instant.now().plusSeconds(3600));
        assertTrue(sessions.active(session.id()));
        assertEquals(session, sessions.rotate("first-hash", "second-hash").orElseThrow());
        assertTrue(sessions.rotate("first-hash", "third-hash").isEmpty());
        sessions.revoke(session.id());
        assertFalse(sessions.active(session.id()));
        assertTrue(sessions.rotate("second-hash", "third-hash").isEmpty());
    }

    @Test
    void rateLimitsAreSharedAndStaffCodeRotationRevokesBinding() {
        var first = new AuthRateLimiter(jdbc);
        var second = new AuthRateLimiter(jdbc);
        first.check("bind-openid", "secret-openid", 2, 900);
        second.check("bind-openid", "secret-openid", 2, 900);
        assertEquals(429, assertThrows(ResponseStatusException.class,
            () -> first.check("bind-openid", "secret-openid", 2, 900)).getStatusCode().value());
        jdbc.update("INSERT INTO merchant (id,status) VALUES (9,1)");
        jdbc.update("INSERT INTO staff_account (id,merchant_id,role,status) VALUES (31,9,'TECHNICIAN','ACTIVE')");
        var operations = new StaffCodeOperations(jdbc);
        String firstCode = operations.issue(31);
        var binding = repository.bindTechnician("app", "openid-tech", firstCode);
        assertTrue(binding.active());
        String replacement = operations.issue(31);
        assertFalse(firstCode.equals(replacement));
        assertTrue(repository.technicianByBindingId(binding.id()).isEmpty());
        operations.revoke(31);
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> repository.bindTechnician("app", "another-openid", replacement)).getStatusCode().value());
    }

    @Test
    void phoneCannotBeBoundToTwoOwners() {
        jdbc.update("INSERT INTO `user` (id,openid,phone) VALUES (1001,'openid-a','13800138000')");
        jdbc.update("INSERT INTO `user` (id,openid) VALUES (1002,'openid-b')");
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> repository.bindOwnerPhone(1002, "13800138000")).getStatusCode().value());
        assertTrue(repository.ownerById(1002).orElseThrow().phone() == null);
    }

    @Test
    void concurrentRefreshCanRotateOnlyOnce() throws Exception {
        var sessions = new JdbcAuthSessionRepository(jdbc);
        String id = "00000000-0000-0000-0000-000000000002";
        sessions.create(new AuthSessionRepository.Session(id, "user", 1001, "OWNER", "app", null, null),
            "initial-hash", Instant.now().plusSeconds(3600));
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> { ready.countDown(); start.await();
                return transaction.execute(status -> sessions.rotate("initial-hash", "new-hash-a").isPresent()); });
            var second = workers.submit(() -> { ready.countDown(); start.await();
                return transaction.execute(status -> sessions.rotate("initial-hash", "new-hash-b").isPresent()); });
            ready.await();
            start.countDown();
            assertTrue(first.get() ^ second.get());
        } finally {
            workers.shutdownNow();
        }
    }
}
