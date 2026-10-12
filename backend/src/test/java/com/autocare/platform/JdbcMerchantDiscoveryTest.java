package com.autocare.platform;

import com.autocare.platform.service.*;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
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
class JdbcMerchantDiscoveryTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    final ObjectMapper mapper=new ObjectMapper();
    VehicleOwner owner;MerchantDiscovery discovery;MerchantQuotes quotes;
    @BeforeAll static void schema() throws Exception {
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()){
            for(String name:List.of("V001__baseline.sql","V003__auth_lifecycle.sql","V006__merchant_project_versions.sql","V019__experience_moderation.sql","V020__merchant_onboarding.sql","V022__catalog_governance.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs/sql/migrations",name)));
        }
    }
    @BeforeEach void setup(){
        for(String table:List.of("merchant_project_version","merchant_project","standard_project","auth_session","merchant","user"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'discovery-owner',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session(),UUID.randomUUID().toString());
        jdbc.update("INSERT INTO merchant(id,merchant_type,name,address,region_code,status,contact_phone,lng,lat) VALUES"+
            "(1,2,'测试门店A','测试地址A','440305',1,'13800000001',113,22),(2,2,'测试门店B','测试地址B','440306',1,'13800000002',114,23),"+
            "(3,2,'待审核店','测试地址C','440305',0,NULL,NULL,NULL),(4,2,'旧门店','旧门店地址','',1,NULL,NULL,NULL)");
        jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status) VALUES"+
            "(11,'项目A',1,'资料A',100,200,1),(12,'项目B',2,'资料B',100,200,1),(13,'停用项目',3,'资料C',100,200,0)");
        jdbc.update("INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) VALUES(101,1,11,110,1),(102,1,12,200,1),(103,1,13,100,1),(201,2,11,90,1),(301,3,11,80,1),(401,4,11,70,1)");
        jdbc.update("INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf) VALUES(1001,101,1,100,1),(1002,101,2,110,1),(1003,102,1,200,1),(1004,103,1,100,1),(2001,201,1,90,1),(3001,301,1,80,1),(4001,401,1,70,1)");
        var tx=new DataSourceTransactionManager(jdbc.getDataSource());var catalog=new ServiceCatalog(jdbc,tx);
        discovery=new MerchantDiscovery(jdbc,catalog,tx);quotes=new MerchantQuotes(jdbc,new WriteIntegrityService(jdbc,mapper,tx),mapper,catalog,tx);
    }
    com.fasterxml.jackson.databind.JsonNode detail(long merchant,Long project,int page,int size){return mapper.valueToTree(discovery.detail(owner,merchant,project,page,size));}
    int status(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    @Test void detailHasOnlyAllowedFieldsAndLatestQuotesWithStablePagination(){
        var data=detail(1,null,1,1);var shop=data.path("merchant");assertEquals(5,shop.size());
        assertEquals(Set.of("merchant_id","name","address","merchant_type","region_code"),mapper.convertValue(shop,Map.class).keySet());
        var offers=data.path("offers");assertEquals(2,offers.path("total").asInt());assertEquals(11,offers.path("items").get(0).path("standard_project_id").asInt());
        assertEquals("110.00",offers.path("items").get(0).path("price").asText());assertEquals(1002,offers.path("items").get(0).path("version_id").asInt());assertEquals(7,offers.path("items").get(0).size());
        assertEquals(12,detail(1,null,2,1).path("offers").path("items").get(0).path("standard_project_id").asInt());
        assertTrue(detail(1,null,3,1).path("offers").path("items").isEmpty());assertEquals(1,detail(1,11L,1,20).path("offers").path("total").asInt());
        assertFalse(data.toString().contains("13800000001"));assertFalse(data.toString().contains("contact_phone"));assertFalse(data.toString().contains("lng"));
    }
    @Test void regionFilteringAndLegacyAllRegionRemainCompatible(){
        assertEquals(3L,quotes.ownerList(owner,11,"price_asc",1,20).get("total"));
        var filtered=mapper.valueToTree(quotes.ownerList(owner,11,"price_desc",1,20,"440305"));assertEquals(1,filtered.path("total").asInt());assertEquals(1,filtered.path("items").get(0).path("merchant_id").asInt());
        assertEquals(0L,quotes.ownerList(owner,11,"price_asc",1,20,"110101").get("total"));
        assertTrue(detail(4,null,1,20).path("merchant").path("region_code").isNull());
        for(String region:List.of("","440305 ","44030","4403051","44030x","440305' OR 1=1"))assertEquals(400,status(()->quotes.ownerList(owner,11,"price_asc",1,20,region)));
    }
    @Test void disabledDeletedAndMissingSourcesCannotRemainBookable(){
        assertEquals(404,status(()->detail(3,null,1,20)));assertEquals(404,status(()->detail(1,13L,1,20)));assertEquals(404,status(()->detail(99,null,1,20)));
        jdbc.update("UPDATE merchant_project SET on_shelf=0 WHERE id=101");assertEquals(0,detail(1,11L,1,20).path("offers").path("total").asInt());
        jdbc.update("UPDATE merchant_project SET is_deleted=1 WHERE id=102");assertTrue(detail(1,null,1,20).path("offers").path("items").isEmpty());
        jdbc.update("UPDATE merchant SET is_deleted=1 WHERE id=1");assertEquals(404,status(()->detail(1,null,1,20)));assertEquals(0L,quotes.ownerList(owner,11,"price_asc",1,20,"440305").get("total"));
    }
    @Test void currentSessionAndActiveOwnerRequiredOnEveryRead(){
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());assertEquals(401,status(()->detail(1,null,1,20)));
        assertEquals(401,status(()->quotes.ownerList(owner,11,"price_asc",1,20,"440305")));
        jdbc.update("UPDATE auth_session SET revoked_at=NULL WHERE id=?",owner.session());jdbc.update("UPDATE user SET status=0 WHERE id=1");assertEquals(401,status(()->detail(1,null,1,20)));
    }
    @Test void parameterBoundariesAndProjectDeletionFailClosed(){
        assertEquals(400,status(()->detail(0,null,1,20)));assertEquals(400,status(()->detail(1,0L,1,20)));assertEquals(400,status(()->detail(1,null,0,20)));assertEquals(400,status(()->detail(1,null,1,101)));
        jdbc.update("UPDATE standard_project SET is_deleted=1 WHERE id=11");assertEquals(404,status(()->detail(1,11L,1,20)));assertEquals(1,detail(1,null,1,20).path("offers").path("total").asInt());
    }
}
