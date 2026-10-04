package com.autocare.platform.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.core.env.Environment;

@Configuration
@EnableScheduling
public class UploadHttpConfiguration {
    @Bean @ConditionalOnProperty("MYSQL_HOST")
    JdbcUploadRequests uploadRequests(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper) {
        return new JdbcUploadRequests(jdbc,manager,mapper);
    }
    @Bean @ConditionalOnProperty("MYSQL_HOST")
    UploadHttpService uploadHttpService(JdbcUploadRequests requests,ObjectProvider<VirusScanner> scanners,ObjectProvider<PrivateObjectStore> stores) {
        return new UploadHttpService(requests,scanners.getIfAvailable(),stores.getIfAvailable());
    }
    @Bean UploadAdmissionFilter uploadAdmissionFilter(ObjectProvider<UploadHttpService> services,ObjectMapper mapper,Environment env) {
        return new UploadAdmissionFilter(services,mapper,env.getProperty("UPLOAD_HTTP_CONCURRENCY",Integer.class,4));
    }
    @Bean FilterRegistrationBean<UploadAdmissionFilter> uploadAdmissionRegistration(UploadAdmissionFilter filter) {
        var bean=new FilterRegistrationBean<>(filter);bean.setEnabled(false);return bean;
    }
}
