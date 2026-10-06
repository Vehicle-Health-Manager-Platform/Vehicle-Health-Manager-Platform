package com.autocare.platform;

import com.autocare.platform.service.*;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcMerchantQuotesTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0")
        .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    MerchantQuotes quotes;
    MerchantActor a,b,a2;
    VehicleOwner owner;
    @BeforeAll static void schema() throws Exception {
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()) {
            for(String file:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V006__merchant_project_versions.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));
        }
    }
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_quote_audit");
        for(String table:List.of("audit_log","idempotency_record","merchant_project_version","merchant_project","standard_project","auth_session","staff_account","merchant","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(1,2,'合成店A','合成地址A',1),(2,2,'合成店B','合成地址B',1)");
        jdbc.update("INSERT INTO staff_account(id,merchant_id,role,account) VALUES(1,1,'MERCHANT','one'),(2,2,'MERCHANT','two'),(3,1,'MERCHANT','three')");
        a=actor(1,1);b=actor(2,2);a2=actor(3,1);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'quote-owner',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session(),UUID.randomUUID().toString());
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) VALUES(1,'合成项目',1,'测试',100.00,200.00)");
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();
        quotes=new MerchantQuotes(jdbc,new WriteIntegrityService(jdbc,mapper,manager),mapper,new ServiceCatalog(jdbc,manager),manager);
    }
    MerchantActor actor(long staff,long merchant){var actor=new MerchantActor(staff,merchant,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES(?,'staff_account',?,'MERCHANT','merchant-account',?,?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",actor.session(),staff,merchant,UUID.randomUUID().toString());return actor;}
    QuoteInput input(String amount,int status){return new QuoteInput(1,new BigDecimal(amount),status);}
    String key(){return UUID.randomUUID().toString();}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    long total(){return (long)quotes.ownerList(owner,1,"price_asc",1,20).get("total");}
    @Test void selectionVersionsIdempotencyAndExactMoney(){
        String key=key();var first=quotes.save(a,key,input("0.01",1));
        assertEquals(first,quotes.save(a,key,input("0.01",1)));assertEquals(1,count("merchant_project_version"));assertEquals(1,count("audit_log"));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->quotes.save(a,key,input("2.00",1))).getStatusCode().value());
        var changed=quotes.save(a,key(),input("99999999.99",0));assertEquals(2,changed.path("data").path("version").asInt());assertEquals(0,total());
        assertEquals(new BigDecimal("0.01"),jdbc.queryForObject("SELECT price FROM merchant_project_version WHERE version=1",BigDecimal.class));
    }
    @Test void twoStoresIsolationAndStablePriceSort(){
        quotes.save(a,key(),input("200.00",1));quotes.save(b,key(),input("100.00",1));
        assertEquals(1L,quotes.ownList(a,1,20).get("total"));assertEquals(1L,quotes.ownList(b,1,20).get("total"));
        var rows=(List<?>)quotes.ownerList(owner,1,"price_asc",1,1).get("items");assertEquals("100.00",((Map<?,?>)rows.get(0)).get("price"));
        rows=(List<?>)quotes.ownerList(owner,1,"price_desc",1,1).get("items");assertEquals("200.00",((Map<?,?>)rows.get(0)).get("price"));
        assertEquals(2L,quotes.ownerList(owner,1,"price_asc",2,1).get("total"));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->quotes.save(new MerchantActor(a.staffId(),2,a.session(),a.expires()),key(),input("1.00",1))).getStatusCode().value());
    }
    @Test void inactiveProjectOnlyAllowsUnchangedPriceUnshelving(){
        quotes.save(a,key(),input("1.00",1));jdbc.update("UPDATE standard_project SET status=0 WHERE id=1");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->quotes.save(a,key(),input("2.00",0))).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->quotes.save(b,key(),input("1.00",0))).getStatusCode().value());
        quotes.save(a,key(),input("1.00",0));assertEquals(2,count("merchant_project_version"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->quotes.ownerList(owner,1,"price_asc",1,20)).getStatusCode().value());
    }
    @Test void cachedWriteRechecksRevocationAndMerchantAvailability(){
        String key=key();quotes.save(a,key,input("1.00",1));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",a.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->quotes.save(a,key,input("1.00",1))).getStatusCode().value());
        jdbc.update("UPDATE merchant SET status=0 WHERE id=2");assertEquals(401,assertThrows(ResponseStatusException.class,()->quotes.save(b,key(),input("2.00",1))).getStatusCode().value());
        jdbc.update("UPDATE staff_account SET status='DISABLED' WHERE id=3");assertEquals(401,assertThrows(ResponseStatusException.class,()->quotes.ownList(a2,1,20)).getStatusCode().value());
        assertEquals(1,count("merchant_project_version"));
    }
    @Test void auditFailureRollsBackQuoteVersionAndIdempotency(){
        jdbc.execute("CREATE TRIGGER reject_quote_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        assertEquals(503,assertThrows(ResponseStatusException.class,()->quotes.save(a,key(),input("1.00",1))).getStatusCode().value());
        for(String table:List.of("merchant_project","merchant_project_version","audit_log","idempotency_record"))assertEquals(0,count(table));
    }
    @Test void concurrentEmployeesProduceMonotonicVersions() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{start.await();return quotes.save(a,key(),input("1.00",1));});
            var second=pool.submit(()->{start.await();return quotes.save(a2,key(),input("2.00",1));});start.countDown();
            first.get(20,TimeUnit.SECONDS);second.get(20,TimeUnit.SECONDS);
            assertEquals(1,count("merchant_project"));assertEquals(List.of(1L,2L),jdbc.queryForList("SELECT version FROM merchant_project_version ORDER BY version",Long.class));assertEquals(2,count("audit_log"));
        }finally{pool.shutdownNow();}
    }
    @Test void migrationBackfillsOnceAndPreservesHistory() throws Exception {
        jdbc.update("INSERT INTO merchant_project(merchant_id,project_id,price,on_shelf) VALUES(1,1,12.34,1)");
        for(int i=0;i<2;i++)try(var connection=jdbc.getDataSource().getConnection()){
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations","V006__merchant_project_versions.sql")));
        }
        assertEquals(1,count("merchant_project_version"));quotes.save(a,key(),input("22.00",1));assertEquals(2,count("merchant_project_version"));
        assertEquals(new BigDecimal("12.34"),jdbc.queryForObject("SELECT price FROM merchant_project_version WHERE version=1",BigDecimal.class));
    }
}
