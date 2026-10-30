package com.autocare.platform;

import java.sql.SQLException;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcTestDataSourceTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Test void repeatedStatementsBoundPhysicalConnectionsAndClosedPoolRejectsBorrow() {
        var source = TestMysqlDataSource.open(mysql);
        try {
            var jdbc = new JdbcTemplate(source);
            var connections = new HashSet<Long>();
            for (int i = 0; i < 100; i++)
                connections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertTrue(connections.size() <= 16, "Do not open a new TCP connection for every statement");
            assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());
        } finally {
            source.close();
        }
        assertTrue(source.isClosed());
        assertThrows(SQLException.class, source::getConnection);
    }

    @Test void returnedConnectionRollsBackUncommittedWorkAndRestoresAutocommit() throws Exception {
        try (var source = TestMysqlDataSource.open(mysql)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE pooled_fixture_transaction(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO pooled_fixture_transaction VALUES(1)");
                }
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pooled_fixture_transaction", Integer.class));
            try (var connection = source.getConnection()) { assertTrue(connection.getAutoCommit()); }
            jdbc.update("INSERT INTO pooled_fixture_transaction VALUES(2)");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pooled_fixture_transaction", Integer.class));
            assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());
        }
    }
}
