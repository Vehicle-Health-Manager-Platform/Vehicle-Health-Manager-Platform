package com.autocare.platform.common.write;

import com.autocare.platform.vehicle.LocalMileageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class WriteIntegrityConfiguration {
    @Bean
    WriteIntegrityService writeIntegrityService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        return new WriteIntegrityService(jdbc, mapper, manager);
    }
    @Bean
    @Profile("local")
    LocalMileageService localMileageService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        return new LocalMileageService(jdbc, integrity, mapper);
    }
}
