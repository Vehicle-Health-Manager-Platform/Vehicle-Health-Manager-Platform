package com.autocare.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class ServiceCatalogConfiguration {
    @Bean MerchantQuotes merchantQuotes(JdbcTemplate jdbc,com.autocare.platform.common.write.WriteIntegrityService integrity,
        com.fasterxml.jackson.databind.ObjectMapper mapper,ServiceCatalog catalog,PlatformTransactionManager manager) {
        return new MerchantQuotes(jdbc,integrity,mapper,catalog,manager);
    }
    @Bean ServiceCatalog serviceCatalog(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new ServiceCatalog(jdbc,manager);
    }
}
