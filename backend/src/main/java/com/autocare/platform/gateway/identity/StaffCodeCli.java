package com.autocare.platform.gateway.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Run only with the staff-admin profile and spring.main.web-application-type=none. */
@Component
@Profile("staff-admin")
public class StaffCodeCli implements ApplicationRunner {
    private final StaffCodeOperations operations;
    private final Environment environment;

    public StaffCodeCli(StaffCodeOperations operations, Environment environment) {
        this.operations = operations;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equals(environment.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("staff-admin must run without an HTTP server");
        }
        String action = environment.getRequiredProperty("staff.action");
        long staffId = Long.parseLong(environment.getRequiredProperty("staff.id"));
        if (staffId <= 0) throw new IllegalArgumentException("staff.id must be positive");
        if ("issue".equals(action)) {
            System.out.println("ONE_TIME_EMPLOYEE_CODE=" + operations.issue(staffId));
        } else if ("revoke".equals(action)) {
            operations.revoke(staffId);
            System.out.println("EMPLOYEE_CODE_REVOKED=true");
        } else {
            throw new IllegalArgumentException("staff.action must be issue or revoke");
        }
    }
}
