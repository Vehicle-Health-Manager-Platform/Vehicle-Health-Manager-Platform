package com.autocare.platform;

import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.MySQLContainer;

/** Bounded physical connections for long-running JDBC fixtures, like the application. */
public final class TestMysqlDataSource {
    private TestMysqlDataSource() {}

    public static HikariDataSource open(MySQLContainer<?> mysql) {
        var source = new HikariDataSource();
        source.setJdbcUrl(mysql.getJdbcUrl());
        source.setUsername(mysql.getUsername());
        source.setPassword(mysql.getPassword());
        source.setMaximumPoolSize(16);
        source.setMinimumIdle(1);
        source.setConnectionTimeout(10_000);
        return source;
    }
}
