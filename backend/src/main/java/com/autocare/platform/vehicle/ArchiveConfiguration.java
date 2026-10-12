package com.autocare.platform.vehicle;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class ArchiveConfiguration {
    @Bean ArchiveService archiveService(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper) {
        return new ArchiveService(jdbc, integrity, mapper);
    }
    @Bean ArchiveReportService archiveReportService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactions) {
        return new ArchiveReportService(jdbc, mapper, transactions);
    }
    @Bean ArchiveReportPdfRenderer archiveReportPdfRenderer() { return new ArchiveReportPdfRenderer(); }
    @Bean ArchiveShareService archiveShareService(JdbcTemplate jdbc, WriteIntegrityService integrity,
        ArchiveReportService reports, ObjectMapper mapper, PlatformTransactionManager transactions,
        @org.springframework.beans.factory.annotation.Value("${JWT_SECRET:}") String secret) {
        return new ArchiveShareService(jdbc, integrity, reports, mapper, transactions, secret);
    }
}
