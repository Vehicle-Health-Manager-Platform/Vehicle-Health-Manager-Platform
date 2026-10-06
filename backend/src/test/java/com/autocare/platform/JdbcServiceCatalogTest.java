package com.autocare.platform;

import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
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
class JdbcServiceCatalogTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    ServiceCatalog catalog;
    VehicleOwner owner;
    @BeforeAll static void schema() throws Exception {
        var source=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        jdbc=new JdbcTemplate(source);
        try(var connection=source.getConnection()) {
            for(String file:new String[]{"V001__baseline.sql","V003__auth_lifecycle.sql"})
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));
        }
    }
    @BeforeEach void setup() {
        for(String table:new String[]{"standard_project","auth_session","user"}) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'catalog-owner',1)");
        owner=new VehicleOwner(1,UUID.randomUUID().toString(),Instant.now().plusSeconds(600));
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) "
            + "VALUES(?,'user',1,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",owner.session(),UUID.randomUUID().toString());
        for(int id=1;id<=5;id++) jdbc.update("INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high,status,is_deleted) "
            + "VALUES(?, ?, ?, '测试内容',0.10,99999999.99,?,?)",id,"项目"+id,id==2?2:1,id==4?0:1,id==5?1:0);
        catalog=new ServiceCatalog(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Test void categoryPagingAndVisibilityShareFilters() {
        var all=catalog.list(owner,null,1,2);
        assertEquals(3L,all.get("total"));
        assertEquals(List.of(1L,2L),((List<?>)all.get("items")).stream().map(row->((Map<?,?>)row).get("id")).toList());
        assertEquals(1,((List<?>)catalog.list(owner,null,2,2).get("items")).size());
        assertEquals(2L,catalog.list(owner,1,1,20).get("total"));
        assertEquals(0L,catalog.list(owner,6,1,20).get("total"));
        assertTrue(((List<?>)catalog.list(owner,null,100,20).get("items")).isEmpty());
    }
    @Test void detailPreservesDecimalsAndHidesInactiveProjects() {
        var detail=catalog.detail(owner,1);
        assertEquals("0.10",detail.get("base_price_low"));
        assertEquals("99999999.99",detail.get("base_price_high"));
        assertNull(detail.get("quality_standard"));
        for(long id:new long[]{4,5,999}) assertEquals(404,assertThrows(ResponseStatusException.class,()->catalog.detail(owner,id)).getStatusCode().value());
    }
    @Test void revokedExpiredAndDisabledOwnersFail() {
        jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->catalog.list(owner,null,1,20)).getStatusCode().value());
        jdbc.update("UPDATE auth_session SET revoked_at=NULL,expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE id=?",owner.session());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->catalog.detail(owner,1)).getStatusCode().value());
        jdbc.update("UPDATE auth_session SET expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR) WHERE id=?",owner.session());
        jdbc.update("UPDATE user SET status=0 WHERE id=1");
        assertEquals(401,assertThrows(ResponseStatusException.class,()->catalog.detail(owner,1)).getStatusCode().value());
    }
}
