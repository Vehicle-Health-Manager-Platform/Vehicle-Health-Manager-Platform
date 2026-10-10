package com.autocare.platform.merchant;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty("MYSQL_HOST")
public class MerchantStaffConfiguration {
    @Bean
    MerchantStaff merchantStaff(JdbcTemplate identityJdbc, WriteIntegrityService integrity, ObjectMapper mapper,
                                StaffCodeOperations codes, @Value("${WECHAT_APP_ID:}") String appId,
                                @Value("${MERCHANT_STAFF_ENABLED:false}") String enabled,
                                PlatformTransactionManager manager) {
        return new MerchantStaff(identityJdbc, integrity, mapper, codes, appId,
            MerchantStaffInput.enabled(enabled), manager);
    }

    @Bean
    MerchantProfile merchantProfile(JdbcTemplate identityJdbc, WriteIntegrityService integrity,
                                    ObjectMapper mapper,
                                    @Value("${MERCHANT_STAFF_ENABLED:false}") String enabled,
                                    PlatformTransactionManager manager) {
        return new MerchantProfile(identityJdbc, integrity, mapper, MerchantStaffInput.enabled(enabled), manager);
    }
}
