package com.autocare.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "JWT_SECRET=test-only-secret-with-at-least-32-characters")
class PlatformApplicationTest {
    @Test
    void contextLoads() {
    }
}
