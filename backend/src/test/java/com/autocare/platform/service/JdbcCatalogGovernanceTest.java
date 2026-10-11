package com.autocare.platform.service;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcCatalogGovernanceTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0").withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;static ObjectMapper json=new ObjectMapper();
    CatalogGovernance catalog; OperatorActor a,b;VehicleOwner owner;VehicleService vehicles;ServiceCatalog projects;
    @BeforeAll static void schema()throws Exception {var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(x->x.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));ScriptUtils.executeSqlScript(c,new FileSystemResource(Path.of("..","docs","sql","migrations","V022__catalog_governance.sql")));}}
    @BeforeEach void setup(){
        jdbc.execute("DROP TRIGGER IF EXISTS reject_catalog_audit");
        for(String table:List.of("audit_log","idempotency_record","auth_session","vehicle","model","series","brand","standard_project","operator_account","user"))jdbc.update("DELETE FROM "+table);
        var tx=new DataSourceTransactionManager(jdbc.getDataSource());var integrity=new WriteIntegrityService(jdbc,json,tx);
        var operators=new OperatorIdentity(jdbc,new NimbusJwtEncoder(new ImmutableSecret<>("synthetic-32-character-secret-key".getBytes())),tx);
        catalog=new CatalogGovernance(jdbc,integrity,json,operators,tx,true);vehicles=new VehicleService(jdbc,integrity,json);projects=new ServiceCatalog(jdbc,tx);
        jdbc.update("INSERT INTO operator_account(id,account,password_hash,phone,can_catalog,can_review,can_onboard) VALUES(1,'catalog-a','hash','13800000000',1,0,0),(2,'catalog-b','hash','13800000001',1,1,1)");
        a=actor(1);b=actor(2);jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-catalog-owner',1)");String session=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",session,session);
        owner=new VehicleOwner(1,session,Instant.now().plusSeconds(900));
    }
    OperatorActor actor(long id){String s=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',?,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE))",s,id,s);return new OperatorActor(id,s,Instant.now().plusSeconds(900));}
    ObjectNode body(String kind,long parent){var n=json.createObjectNode().put("sort_order",0).put("source_note","授权合成测试目录");switch(kind){
        case "brands" -> n.put("name","合成品牌");case "series" -> n.put("brand_id",parent).put("name","合成车系");
        case "models" -> n.put("series_id",parent).put("year","2026").put("config_name","合成配置").put("power_type","FUEL");
        default -> n.put("project_name","合成项目").put("category",1).put("service_content","合成养护内容").put("quality_standard","合成质检要求").put("base_price_low","10.00").put("base_price_high","20.00");}return n;}
    JsonNode create(String kind,ObjectNode body){return catalog.save(a,kind,0,UUID.randomUUID().toString(),CatalogInput.parse(body.toString(),kind,false)).path("data");}
    JsonNode update(OperatorActor actor,String kind,JsonNode row,int status){var n=row.deepCopy();((ObjectNode)n).remove("id");((ObjectNode)n).put("status",status);return catalog.save(actor,kind,row.get("id").longValue(),UUID.randomUUID().toString(),CatalogInput.parse(n.toString(),kind,true)).path("data");}
    int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    @Test void draftsPublishSortAndDisableAffectOwnerSelections(){var draft=create("projects",body("projects",0));assertEquals(0,draft.get("status").intValue());assertEquals(0L,projects.list(owner,null,1,20).get("total"));var live=update(a,"projects",draft,1);assertEquals(1L,projects.list(owner,null,1,20).get("total"));update(a,"projects",live,0);assertEquals(404,status(()->projects.detail(owner,live.get("id").longValue())));}
    @Test void childPublishingRequiresEntirePublishedChainAndParentDisableHidesChildren(){var brand=create("brands",body("brands",0));var draftSeries=create("series",body("series",brand.get("id").longValue()));assertEquals(409,status(()->update(a,"series",draftSeries,1)));brand=update(a,"brands",brand,1);var series=update(a,"series",draftSeries,1);var model=create("models",body("models",series.get("id").longValue()));update(a,"models",model,1);assertEquals(1L,vehicles.catalog(owner,"model",series.get("id").longValue(),1,20).get("total"));update(a,"brands",brand,0);assertEquals(0L,vehicles.catalog(owner,"model",series.get("id").longValue(),1,20).get("total"));assertEquals(404,status(()->vehicles.add(owner,UUID.randomUUID().toString(),new VehicleInput(model.get("id").longValue(),10,"",""))));}
    @Test void idempotencyEnvelopeAndRevokedPermissionOnReplay(){var body=body("brands",0);String key=UUID.randomUUID().toString();var result=catalog.save(a,"brands",0,key,body);assertFalse(result.path("data").has("data"));assertEquals(result,catalog.save(a,"brands",0,key,body));jdbc.update("UPDATE operator_account SET can_catalog=0 WHERE id=1");assertEquals(403,status(()->catalog.save(a,"brands",0,key,body)));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM brand",Integer.class));}
    @Test void staleRevisionDoesNotOverwriteChanges(){var row=create("brands",body("brands",0));update(b,"brands",row,1);assertEquals(409,status(()->update(a,"brands",row,0)));assertEquals(2L,((Map<?,?>)((List<?>)catalog.list(a,"brands",1,20).get("items")).get(0)).get("revision"));}
    @Test void referencedVehicleIdentityFrozenButStatusAndOrderingAllowed(){var brand=update(a,"brands",create("brands",body("brands",0)),1);var series=update(a,"series",create("series",body("series",brand.get("id").longValue())),1);var model=update(a,"models",create("models",body("models",series.get("id").longValue())),1);vehicles.add(owner,UUID.randomUUID().toString(),new VehicleInput(model.get("id").longValue(),10,"",""));var n=(ObjectNode)brand.deepCopy();n.remove("id");n.put("name","不允许改名");assertEquals(409,status(()->catalog.save(a,"brands",brand.get("id").longValue(),UUID.randomUUID().toString(),n)));update(a,"models",model,0);assertEquals(1L,vehicles.list(owner,1,20).get("total"));}
    @Test void parentAssociationAndSiblingIdentityCannotBeDuplicated(){var brand=create("brands",body("brands",0));var series=create("series",body("series",brand.get("id").longValue()));assertEquals(409,status(()->create("series",body("series",brand.get("id").longValue()))));var other=body("brands",0).put("name","另一品牌");var brand2=create("brands",other);var n=(ObjectNode)series.deepCopy();n.remove("id");n.put("brand_id",brand2.get("id").longValue());assertEquals(409,status(()->catalog.save(a,"series",series.get("id").longValue(),UUID.randomUUID().toString(),n)));}
    @Test void failedAuditRollsBackDirectoryAndIdempotency(){jdbc.execute("CREATE TRIGGER reject_catalog_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic-failure'");assertEquals(503,status(()->create("brands",body("brands",0))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM brand",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record",Integer.class));}
    @Test void simultaneousSameSiblingOnlyCreatesOne()throws Exception{var brand=create("brands",body("brands",0));var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try{var tasks=new ArrayList<Future<Integer>>();for(var actor:List.of(a,b))tasks.add(pool.submit(()->{ready.countDown();go.await();try{catalog.save(actor,"series",0,UUID.randomUUID().toString(),body("series",brand.get("id").longValue()));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();var results=new ArrayList<Integer>();for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));Collections.sort(results);assertEquals(List.of(200,409),results);}finally{pool.shutdownNow();}}
    @Test void independentPermissionsAndDisabledFeatureDenied(){jdbc.update("UPDATE operator_account SET can_catalog=0,can_review=1,can_onboard=1 WHERE id=1");assertEquals(403,status(()->catalog.list(a,"brands",1,20)));jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",b.session());assertEquals(401,status(()->catalog.list(b,"brands",1,20)));var tx=new DataSourceTransactionManager(jdbc.getDataSource());var disabled=new CatalogGovernance(jdbc,new WriteIntegrityService(jdbc,json,tx),json,new OperatorIdentity(jdbc,new NimbusJwtEncoder(new ImmutableSecret<>("synthetic-32-character-secret-key".getBytes())),tx),tx,false);assertEquals(503,status(()->disabled.list(a,"brands",1,20)));}
}
