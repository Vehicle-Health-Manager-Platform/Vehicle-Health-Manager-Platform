package com.autocare.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AiConfiguration {
    @Bean
    @ConditionalOnProperty("MYSQL_HOST")
    AiContextProvider aiContextProvider(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new AiContextProvider(jdbc, mapper);
    }
}
