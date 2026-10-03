package com.autocare.platform;

import com.autocare.platform.gateway.identity.JdbcMerchantIdentityRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class JdbcMerchantIdentityRepositoryTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    JdbcTemplate jdbc;
    JdbcMerchantIdentityRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS sms_code");
        jdbc.execute("DROP TABLE IF EXISTS staff_account");
        jdbc.execute("DROP TABLE IF EXISTS merchant");
        jdbc.execute("CREATE TABLE merchant (id BIGINT UNSIGNED PRIMARY KEY,status TINYINT,is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE staff_account (id BIGINT UNSIGNED PRIMARY KEY,merchant_id BIGINT UNSIGNED,"
            + "role VARCHAR(24),account VARCHAR(64),phone VARCHAR(20),password_hash VARCHAR(128),"
            + "status VARCHAR(24),is_deleted TINYINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE sms_code (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,phone VARCHAR(20),"
            + "purpose VARCHAR(24),code_hash CHAR(64),expires_at DATETIME,consumed_at DATETIME,"
            + "is_deleted TINYINT DEFAULT 0)");
        jdbc.update("INSERT INTO merchant (id,status) VALUES (9,1)");
        jdbc.update("INSERT INTO staff_account (id,merchant_id,role,account,phone,password_hash,status) "
            + "VALUES (31,9,'MERCHANT','shop-owner','13800138000',?,'ACTIVE')",
            new BCryptPasswordEncoder().encode("correct-password"));
        repository = new JdbcMerchantIdentityRepository(jdbc);
    }

    @Test
    void codeIsScopedToAccountAndCanOnlyBeConsumedOnce() {
        String hash = new BCryptPasswordEncoder().encode("123456");
        repository.issueSmsCode(31, "13800138000", hash, () -> {});
        assertTrue(repository.byAccount("shop-owner").orElseThrow().active());
        assertFalse(repository.consumeSmsCode(32, "13800138000", "123456"));
        assertFalse(repository.consumeSmsCode(31, "13800138000", "000000"));
        assertTrue(repository.consumeSmsCode(31, "13800138000", "123456"));
        assertFalse(repository.consumeSmsCode(31, "13800138000", "123456"));
        jdbc.update("UPDATE merchant SET status=2 WHERE id=9");
        assertFalse(repository.byId(31).orElseThrow().active());
    }

    @Test
    void failedDeliveryCanBeRolledBackAndNewCodeInvalidatesOldOne() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        AtomicInteger deliveries = new AtomicInteger();
        try {
            tx.executeWithoutResult(status -> repository.issueSmsCode(31, "13800138000",
                new BCryptPasswordEncoder().encode("123456"), () -> { throw new IllegalStateException("sms failed"); }));
        } catch (IllegalStateException expected) {
            assertEquals("sms failed", expected.getMessage());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sms_code", Integer.class));
        tx.executeWithoutResult(status -> repository.issueSmsCode(31, "13800138000",
            new BCryptPasswordEncoder().encode("123456"), deliveries::incrementAndGet));
        tx.executeWithoutResult(status -> repository.issueSmsCode(31, "13800138000",
            new BCryptPasswordEncoder().encode("654321"), deliveries::incrementAndGet));
        assertEquals(2, deliveries.get());
        assertFalse(repository.consumeSmsCode(31, "13800138000", "123456"));
        assertTrue(repository.consumeSmsCode(31, "13800138000", "654321"));
    }
}
