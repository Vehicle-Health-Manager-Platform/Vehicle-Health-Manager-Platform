package com.autocare.platform;

import com.autocare.platform.gateway.identity.JdbcIdentityRepository;
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
    JdbcIdentityRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS staff_wechat_identity");
        jdbc.execute("DROP TABLE IF EXISTS staff_account");
        jdbc.execute("DROP TABLE IF EXISTS merchant");
        jdbc.execute("DROP TABLE IF EXISTS `user`");
        jdbc.execute("CREATE TABLE `user` (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, openid VARCHAR(128) UNIQUE, "
            + "phone VARCHAR(20), status TINYINT NOT NULL DEFAULT 1, is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE merchant (id BIGINT UNSIGNED PRIMARY KEY, status TINYINT NOT NULL, is_deleted TINYINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE staff_account (id BIGINT UNSIGNED PRIMARY KEY, merchant_id BIGINT UNSIGNED, "
            + "role VARCHAR(24), status VARCHAR(24), is_deleted TINYINT NOT NULL DEFAULT 0, employee_code_hash CHAR(64) UNIQUE)");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                Path.of("..", "docs", "sql", "migrations", "V002__staff_wechat_identity.sql")));
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
}
