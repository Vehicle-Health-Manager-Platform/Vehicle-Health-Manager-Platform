package com.autocare.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class ServiceCatalogConfiguration {
    @Bean CatalogGovernance catalogGovernance(JdbcTemplate jdbc,com.autocare.platform.common.write.WriteIntegrityService integrity,
        com.fasterxml.jackson.databind.ObjectMapper mapper,com.autocare.platform.gateway.identity.OperatorIdentity operators,
        PlatformTransactionManager manager,@org.springframework.beans.factory.annotation.Value("${CATALOG_GOVERNANCE_ENABLED:false}")boolean enabled) {
        return new CatalogGovernance(jdbc,integrity,mapper,operators,manager,enabled);
    }
    @Bean MerchantQuotes merchantQuotes(JdbcTemplate jdbc,com.autocare.platform.common.write.WriteIntegrityService integrity,
        com.fasterxml.jackson.databind.ObjectMapper mapper,ServiceCatalog catalog,PlatformTransactionManager manager) {
        return new MerchantQuotes(jdbc,integrity,mapper,catalog,manager);
    }
    @Bean ServiceCatalog serviceCatalog(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new ServiceCatalog(jdbc,manager);
    }
}
