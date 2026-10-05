package com.autocare.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "JWT_SECRET=test-only-secret-with-at-least-32-characters")
class NonWebSecurityContextTest {
    @Autowired ApplicationContext context;

    @Test
    void nonWebProcessDoesNotCreateServletSecurityFilterChain() {
        assertFalse(context.containsBean("securityFilterChain"));
    }
}
