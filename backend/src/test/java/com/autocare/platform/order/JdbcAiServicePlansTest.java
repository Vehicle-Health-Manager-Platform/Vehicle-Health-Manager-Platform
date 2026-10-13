package com.autocare.platform.order;

import com.autocare.platform.ai.DeepSeekClient;
import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
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
import static org.mockito.Mockito.*;

@Testcontainers class JdbcAiServicePlansTest {
    @Container static MySQLContainer<?> mysql=new MySQLContainer<>("mysql:8.0");static JdbcTemplate jdbc;
    DeepSeekClient client;AiServicePlans plans;ReservationStore db;VehicleOwner owner;
    @BeforeAll static void schema()throws Exception{var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());jdbc=new JdbcTemplate(ds);try(var c=ds.getConnection();var paths=Files.list(Path.of("..","docs","sql","migrations"))){for(var p:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(c,new FileSystemResource(p));}}
    @BeforeEach void setup(){for(String t:List.of("standard_project","auth_session","user","audit_log","idempotency_record"))jdbc.update("DELETE FROM `"+t+"`");var manager=new DataSourceTransactionManager(jdbc.getDataSource());var mapper=new ObjectMapper();db=new ReservationStore(jdbc,mapper,new WriteIntegrityService(jdbc,mapper,manager),Clock.systemUTC(),manager);client=mock(DeepSeekClient.class);plans=new AiServicePlans(db,client,true);
        jdbc.update("INSERT INTO user(id,openid,status) VALUES(1,'synthetic-plan-owner',1)");String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'user',1,'OWNER','test-app',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",session,UUID.randomUUID().toString());owner=new VehicleOwner(1,session,Instant.now().plusSeconds(3600));project(1);project(2);
    }
    void project(long id){jdbc.update("INSERT INTO standard_project(id,category,project_name,service_content,base_price_low,base_price_high,status) VALUES(?,1,?,'当前公开服务内容',10,20,1)",id,"合成项目"+id);}
    AiServicePlans.Input input(List<Long> ids){return AiServicePlans.input(new ObjectMapper().valueToTree(Map.of("question","车辆出现异响","requested_project_ids",ids,"consent_to_provider",true)).toString());}
    int status(Runnable r){return assertThrows(ResponseStatusException.class,r::run).getStatusCode().value();}
    String answer(long id,String tier){return "{\"items\":[{\"project_id\":"+id+",\"tier\":\""+tier+"\",\"evidence_quote\":\"车辆出现异响\"}]}";}
    @Test void emptyAndManualFallbackPreserveOnlyOwnerSelections(){var r=plans.generate(owner,input(List.of(1L)));assertEquals("MANUAL",r.get("status"));var items=(List<?>)r.get("items");assertEquals("REQUESTED",((Map<?,?>)items.get(0)).get("tier"));assertEquals("OWNER_SELECTED",((Map<?,?>)items.get(0)).get("reason_code"));jdbc.update("DELETE FROM standard_project");assertEquals("EMPTY",plans.generate(owner,input(List.of())).get("status"));}
    @Test void aiCannotAuthorProjectMetadataAndSendsNoPrivateOrPriceFields(){when(client.configured()).thenReturn(true);when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());List<DeepSeekClient.Message> turns=call.getArgument(1);String payload=turns.get(0).content();assertFalse(payload.contains("price"));assertFalse(payload.contains("user_id"));assertFalse(payload.contains("vehicle_id"));return new DeepSeekClient.Completion(answer(2,"OPTIONAL"),"synthetic",0,0);});var r=plans.generate(owner,input(List.of(1L)));assertEquals("AI_READY",r.get("status"));var items=(List<?>)r.get("items");assertEquals(2,items.size());assertEquals("合成项目2",((Map<?,?>)items.get(1)).get("project_name"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));}
    @Test void inventedDuplicateRequestedMandatoryOrWrongEvidenceOutputsDegrade(){when(client.configured()).thenReturn(true);for(String b:List.of(answer(999,"RECOMMENDED"),answer(1,"RECOMMENDED"),answer(2,"MUST"),answer(2,"OPTIONAL").replace("车辆出现异响","诊断故障"),"{\"items\":[],\"price\":1}","{\"items\":[],\"items\":[]}{}")){when(client.complete(anyString(),anyList(),anyInt())).thenReturn(new DeepSeekClient.Completion(b,"synthetic",0,0));var r=plans.generate(owner,input(List.of(1L)));assertEquals("MANUAL",r.get("status"));assertEquals(1,((List<?>)r.get("items")).size());}}
    @Test void catalogMutationDuringIoDiscardsResultsAndStoppedRequestedRejects(){when(client.configured()).thenReturn(true);when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE standard_project SET project_name='已更新',revision=revision+1 WHERE id=2");return new DeepSeekClient.Completion(answer(2,"OPTIONAL"),"synthetic",0,0);});assertEquals("SOURCE_CHANGED",plans.generate(owner,input(List.of())).get("status"));jdbc.update("UPDATE standard_project SET status=0 WHERE id=1");assertEquals(404,status(()->plans.generate(owner,input(List.of(1L)))));}
    @Test void viewerRevocationDuringIoAndInactiveOwnerAreDenied(){when(client.configured()).thenReturn(true);when(client.complete(anyString(),anyList(),anyInt())).thenAnswer(call->{jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=?",owner.session());return new DeepSeekClient.Completion(answer(2,"OPTIONAL"),"synthetic",0,0);});assertEquals(401,status(()->plans.generate(owner,input(List.of()))));assertEquals(401,status(()->plans.catalog(owner)));}
    @Test void boundedCatalogIncludesExplicitSelectionOutsideFirstForty(){for(long i=3;i<=45;i++)project(i);var c=plans.catalog(owner);assertEquals(40,((List<?>)c.get("items")).size());assertEquals(45L,c.get("total"));var r=plans.generate(owner,input(List.of(45L)));assertEquals(40,r.get("candidate_count"));assertEquals(45L,((Number)((Map<?,?>)((List<?>)r.get("items")).get(0)).get("project_id")).longValue());}
    @Test void disabledFeatureDoesNotCallModelAndQuestionIsSanitized(){var disabled=new AiServicePlans(db,client,false);var in=AiServicePlans.input("{\"question\":\"请检查 联系13800000000\",\"requested_project_ids\":[1],\"consent_to_provider\":true}");assertFalse(in.question().contains("138"));assertEquals("MANUAL",disabled.generate(owner,in).get("status"));verifyNoInteractions(client);}
}
