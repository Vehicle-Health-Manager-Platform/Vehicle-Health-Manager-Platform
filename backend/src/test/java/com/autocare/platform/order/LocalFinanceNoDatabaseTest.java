package com.autocare.platform.order;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class LocalFinanceNoDatabaseTest {
    @Test void configuredQueryWithoutDatabaseIsUnavailable() {
        var controller = new LocalFinanceController(new StaticListableBeanFactory().getBeanProvider(LocalFinance.class));
        var jwt = Jwt.withTokenValue("test").header("alg","HS256").subject("1").claim("jti","test")
            .claim("subject_type","operator_account").claim("role","OPERATOR").claim("app_id","operator-account")
            .expiresAt(Instant.now().plusSeconds(600)).build();
        var q = new LinkedMultiValueMap<String,String>();q.add("date","2026-10-23");q.add("channel","WECHAT");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->controller.report(jwt,q)).getStatusCode().value());
    }
}
