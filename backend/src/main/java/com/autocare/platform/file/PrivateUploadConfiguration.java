package com.autocare.platform.file;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PrivateUploadConfiguration {
    @Bean
    @ConditionalOnProperty("MYSQL_HOST")
    FileMetadataRepository fileMetadataRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcFileMetadataRepository(jdbc, manager);
    }
    @Bean
    PrivateUploadService privateUploadService(ObjectProvider<VirusScanner> scanners,
        ObjectProvider<PrivateObjectStore> stores, ObjectProvider<FileMetadataRepository> repositories) {
        return new PrivateUploadService(scanners.getIfAvailable(), stores.getIfAvailable(), repositories.getIfAvailable());
    }
}
