package com.autocare.platform.gateway.identity;

import com.autocare.platform.gateway.SecurityConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers class JdbcOperatorIdentityTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");static JdbcTemplate jdbc;
    OperatorIdentity operators;BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();String password="synthetic-operator-password";
    @BeforeAll static void schema()throws Exception{
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V019__experience_moderation.sql")));}
    }
    @BeforeEach void setup(){jdbc.execute("DROP TRIGGER IF EXISTS reject_operator_audit");for(String t:List.of("operator_account","sms_code","auth_session","audit_log","auth_rate_limit"))jdbc.update("DELETE FROM "+t);
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,can_review) VALUES(1,'synthetic-operator',?,'13800000000',1)",bcrypt.encode(password));
        operators=new OperatorIdentity(jdbc,new NimbusJwtEncoder(new ImmutableSecret<>("synthetic-secret-32-characters-long".getBytes())),new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    void code(){operators.code("synthetic-operator",password,(phone,code)->jdbc.update("UPDATE sms_code SET code_hash=? WHERE purpose='O1'",bcrypt.encode("123456")));}
    OperatorActor actor(){String jti=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",jti,AuthTokens.sha256(jti));return new OperatorActor(1,jti,Instant.now().plusSeconds(900));}
    @Test void passwordAndSecondFactorRequiredOnceAndLoginReturnsOnlyShortSession(){
        code();assertThrows(ResponseStatusException.class,()->operators.login("synthetic-operator","wrong","123456"));assertThrows(ResponseStatusException.class,()->operators.login("synthetic-operator",password,"999999"));
        var result=operators.login("synthetic-operator",password,"123456");assertEquals(900,result.get("expires_in"));assertFalse(result.containsKey("refresh_token"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session",Integer.class));assertThrows(ResponseStatusException.class,()->operators.login("synthetic-operator",password,"123456"));
    }
    @Test void absentSmsOrProviderFailureNeverCreatesUsableCode(){
        assertThrows(ResponseStatusException.class,()->operators.code("synthetic-operator",password,null));
        assertThrows(RuntimeException.class,()->operators.code("synthetic-operator",password,(phone,code)->{throw new IllegalStateException("synthetic failure");}));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sms_code",Integer.class));
    }
    @Test void loginAuditFailureRollsBackCodeConsumptionAndSession(){code();jdbc.execute("CREATE TRIGGER reject_operator_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");
        assertThrows(RuntimeException.class,()->operators.login("synthetic-operator",password,"123456"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sms_code WHERE consumed_at IS NULL",Integer.class));
        jdbc.execute("DROP TRIGGER reject_operator_audit");operators.login("synthetic-operator",password,"123456");
    }
    @Test void permissionsDisabledAccountsAndRevocationApplyImmediately(){var a=actor();operators.authorize(a,true,false);
        jdbc.update("UPDATE operator_account SET can_review=0");assertEquals(403,assertThrows(ResponseStatusException.class,()->operators.authorize(a,true,false)).getStatusCode().value());operators.authorize(a,false,false);
        operators.logout(a);assertThrows(ResponseStatusException.class,()->operators.authorize(a,false,false));
        var b=actor();jdbc.update("UPDATE operator_account SET status='DISABLED'");assertThrows(ResponseStatusException.class,()->operators.authorize(b,false,false));
    }
    @Test void sessionsMustMatchSubjectRoleAppAndNotMerchantBinding(){var a=actor();for(String mutation:List.of("subject_id=2","role='OWNER'","app_id='merchant-account'","subject_type='staff_account'","merchant_id=1","binding_id=1")){
        jdbc.update("UPDATE auth_session SET "+mutation+" WHERE id=?",a.session());assertThrows(ResponseStatusException.class,()->operators.authorize(a,false,false));jdbc.update("UPDATE auth_session SET subject_id=1,role='OPERATOR',app_id='operator-account',subject_type='operator_account',merchant_id=NULL,binding_id=NULL WHERE id=?",a.session());}}
    @Test void concurrentLoginConsumesCodeOnlyOnce()throws Exception{code();var pool=Executors.newFixedThreadPool(2);try{
        var calls=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++)calls.add(pool.submit(()->{try{operators.login("synthetic-operator",password,"123456");return true;}catch(ResponseStatusException e){return false;}}));
        int successes=0;for(var f:calls)if(f.get(30,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session",Integer.class));
    }finally{pool.shutdownNow();}}
    @Test void expiredCodeAndSharedRateLimitsReject(){code();jdbc.update("UPDATE sms_code SET expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND)");assertThrows(ResponseStatusException.class,()->operators.login("synthetic-operator",password,"123456"));
        var limits=new AuthRateLimiter(jdbc);limits.check("operator-sms-minute","account",1,60);assertEquals(429,assertThrows(ResponseStatusException.class,()->limits.check("operator-sms-minute","account",1,60)).getStatusCode().value());}
    @Test void forgedOperatorClaimsAndTokensFailIdentityCheck(){var a=actor();var jwt=Jwt.withTokenValue("synthetic").header("alg","HS256").issuer(SecurityConfig.issuer()).subject("1").claim("jti",a.session()).expiresAt(a.expires()).claim("subject_type","operator_account").claim("role","OPERATOR").claim("app_id",OperatorActor.APP).build();assertTrue(operators.valid(jwt));
        jdbc.update("UPDATE operator_account SET status='DISABLED'");assertFalse(operators.valid(jwt));assertThrows(ResponseStatusException.class,()->OperatorActor.from(Jwt.withTokenValue("synthetic").header("alg","HS256").subject("1").claim("role","OWNER").build()));}
}
