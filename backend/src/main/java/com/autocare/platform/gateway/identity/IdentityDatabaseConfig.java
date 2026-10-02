package com.autocare.platform.gateway.identity;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class IdentityDatabaseConfig {
    @Bean
    DataSource identityDataSource(@Value("${MYSQL_HOST}") String host,
                                  @Value("${MYSQL_DATABASE}") String database,
                                  @Value("${MYSQL_USER}") String user,
                                  @Value("${MYSQL_PASSWORD}") String password) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl("jdbc:mysql://" + host + ":3306/" + database + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC");
        source.setUsername(user);
        source.setPassword(password);
        source.setMaximumPoolSize(10);
        return source;
    }

    @Bean
    JdbcTemplate identityJdbc(DataSource identityDataSource) {
        return new JdbcTemplate(identityDataSource);
    }

    @Bean
    IdentityRepository identityRepository(JdbcTemplate identityJdbc) {
        return new JdbcIdentityRepository(identityJdbc);
    }

    @Bean
    MerchantIdentityRepository merchantIdentityRepository(JdbcTemplate identityJdbc) {
        return new JdbcMerchantIdentityRepository(identityJdbc);
    }

    @Bean
    AuthSessionRepository authSessionRepository(JdbcTemplate identityJdbc) {
        return new JdbcAuthSessionRepository(identityJdbc);
    }

    @Bean
    AuthRateLimiter authRateLimiter(JdbcTemplate identityJdbc) {
        return new AuthRateLimiter(identityJdbc);
    }

    @Bean
    StaffCodeOperations staffCodeOperations(JdbcTemplate identityJdbc) {
        return new StaffCodeOperations(identityJdbc);
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource identityDataSource) {
        return new DataSourceTransactionManager(identityDataSource);
    }
}
