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
    /** Pool size stays at 10 unless a deployment explicitly sizes it for measured concurrency. */
    static int poolSize(String value) {
        int size = Integer.parseInt(value);
        if (size < 1 || size > 200) throw new IllegalStateException("MYSQL_POOL_SIZE must be between 1 and 200");
        return size;
    }

    @Bean
    DataSource identityDataSource(@Value("${MYSQL_HOST}") String host,
                                  @Value("${MYSQL_PORT:3306}") int port,
                                  @Value("${MYSQL_DATABASE}") String database,
                                  @Value("${MYSQL_USER}") String user,
                                  @Value("${MYSQL_PASSWORD}") String password,
                                  @Value("${MYSQL_POOL_SIZE:10}") String poolSize) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC");
        source.setUsername(user);
        source.setPassword(password);
        source.setMaximumPoolSize(poolSize(poolSize));
        // Reuse parsed statements per pooled connection; keep server transaction/lock semantics.
        source.addDataSourceProperty("cachePrepStmts","true");
        source.addDataSourceProperty("prepStmtCacheSize","250");
        source.addDataSourceProperty("prepStmtCacheSqlLimit","4096");
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
    OperatorIdentity operatorIdentity(JdbcTemplate identityJdbc,org.springframework.security.oauth2.jwt.JwtEncoder encoder,org.springframework.transaction.PlatformTransactionManager manager) {
        return new OperatorIdentity(identityJdbc,encoder,manager);
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
