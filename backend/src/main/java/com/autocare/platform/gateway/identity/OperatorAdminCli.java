package com.autocare.platform.gateway.identity;

import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline account management only. Secrets are environment input and never output. */
@Component @Profile("operator-admin")
public class OperatorAdminCli implements ApplicationRunner {
    private final Environment env;private final ApplicationContext context;private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;private final ObjectMapper mapper;
    public OperatorAdminCli(Environment env,ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper){this.env=env;this.context=context;this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.mapper=mapper;}
    public void run(ApplicationArguments args) {
        if(!"none".equals(env.getProperty("spring.main.web-application-type")))throw new IllegalStateException("operator-admin requires no HTTP server");
        String action=env.getRequiredProperty("operator.action");
        Long result=tx.execute(t->{
            long id; Map<String,Object> after;
            if("create".equals(action)) {
                String account=OperatorInput.account(env.getRequiredProperty("OPERATOR_ACCOUNT"));
                String password=OperatorInput.password(env.getRequiredProperty("OPERATOR_PASSWORD"));
                if(password.codePointCount(0,password.length())<12)throw new IllegalArgumentException("Operator password requires at least 12 characters");
                String phone=env.getRequiredProperty("OPERATOR_PHONE");if(!phone.matches("1[3-9][0-9]{9}"))throw new IllegalArgumentException("Operator phone invalid");
                boolean permission=permission();String hash=new BCryptPasswordEncoder().encode(password);var key=new GeneratedKeyHolder();
                jdbc.update(c->{var s=c.prepareStatement("INSERT INTO operator_account(account,password_hash,phone,can_review) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS);s.setString(1,account);s.setString(2,hash);s.setString(3,phone);s.setBoolean(4,permission);return s;},key);
                id=key.getKey().longValue();after=Map.of("status","ACTIVE","can_review",permission);
            } else {
                id=Long.parseLong(env.getRequiredProperty("operator.id"));if(id<1 || id>9007199254740991L)throw new IllegalArgumentException("Operator ID invalid");
                if(jdbc.queryForList("SELECT id FROM operator_account WHERE id=? FOR UPDATE",id).isEmpty())throw new IllegalArgumentException("Operator unavailable");
                if("disable".equals(action)){jdbc.update("UPDATE operator_account SET status='DISABLED' WHERE id=?",id);after=Map.of("status","DISABLED");}
                else if("review-permission".equals(action)){boolean permission=permission();jdbc.update("UPDATE operator_account SET can_review=? WHERE id=?",permission,id);after=Map.of("can_review",permission);}
                else throw new IllegalArgumentException("Unsupported operator action");
                jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='operator_account' AND subject_id=? AND revoked_at IS NULL",id);
            }
            try{jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,'OPERATOR_ACCOUNT_MANAGE','operator_account',?,'{}',?,?)",id,mapper.writeValueAsString(after),UUID.randomUUID().toString());}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Operator audit unavailable");}
            return id;
        });
        System.out.println("OPERATOR_ACCOUNT_ID="+result);SpringApplication.exit(context);
    }
    private boolean permission(){String v=env.getRequiredProperty("OPERATOR_CAN_REVIEW");if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Explicit review permission required");return Boolean.parseBoolean(v);}
}
