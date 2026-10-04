package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class ArchiveConfiguration {
    @Bean ArchiveService archiveService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        return new ArchiveService(jdbc, integrity, mapper);
    }
}
