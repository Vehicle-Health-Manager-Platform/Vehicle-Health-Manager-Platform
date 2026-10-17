package com.autocare.platform.gateway.identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOperatorAdminCurationTest extends JdbcOperatorIdentityTest {
 String session(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",id,UUID.randomUUID().toString());return id;}
 MockEnvironment env(String action){return new MockEnvironment().withProperty("spring.main.web-application-type","none").withProperty("operator.action",action).withProperty("operator.id","1");}
 void run(MockEnvironment env){try(var context=new GenericApplicationContext()){context.refresh();new OperatorAdminCli(env,context,jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new ObjectMapper()).run(new DefaultApplicationArguments(new String[0]));}}
 @Test void curationGrantsAreIndependentAndRevokeSessions(){
  String old=session();run(env("community-curate-permission").withProperty("OPERATOR_CAN_COMMUNITY_CURATE","true"));assertEquals(1,jdbc.queryForObject("SELECT can_community_curate FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT can_price_monitor+can_analytics+can_catalog+can_points+can_assessment+can_invite+can_coupon+can_diagnose+can_diagnose_manage FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,old));
  String fresh=session();run(env("community-curate-permission").withProperty("OPERATOR_CAN_COMMUNITY_CURATE","false"));assertEquals(0,jdbc.queryForObject("SELECT can_community_curate FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,fresh));
 }
 @Test void curationLoginExposesOnlyExplicitCapability(){jdbc.update("UPDATE operator_account SET can_community_curate=1 WHERE id=1");code();var result=operators.login("synthetic-operator",password,"123456");var user=(java.util.Map<?,?>)result.get("user");assertEquals(java.util.Set.of("id","role","can_review","can_onboard","can_catalog","can_coupon","can_invite","can_diagnose","can_diagnose_manage","can_points","can_assessment","can_analytics","can_price_monitor","can_community_curate","can_community_answer","can_community_manage","can_knowledge"),user.keySet());assertEquals(true,user.get("can_community_curate"));assertEquals(false,user.get("can_analytics"));assertEquals(false,user.get("can_assessment"));assertEquals(false,user.get("can_points"));assertEquals(false,user.get("can_invite"));assertEquals(false,user.get("can_diagnose"));}
 @Test void explicitFlagsAndNoHttpAreRequired(){
  session();assertThrows(Exception.class,()->run(env("community-curate-permission")));assertThrows(Exception.class,()->run(env("community-curate-permission").withProperty("OPERATOR_CAN_COMMUNITY_CURATE","TRUE")));assertThrows(Exception.class,()->run(env("community-curate-permission").withProperty("spring.main.web-application-type","servlet").withProperty("OPERATOR_CAN_COMMUNITY_CURATE","true")));assertEquals(0,jdbc.queryForObject("SELECT can_community_curate FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NOT NULL",Integer.class));
 }
 @Test void auditFailureRollsBackPermissionAndSessionRevocation(){
  String id=session();jdbc.execute("CREATE TRIGGER reject_operator_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");assertThrows(Exception.class,()->run(env("community-curate-permission").withProperty("OPERATOR_CAN_COMMUNITY_CURATE","true")));assertEquals(0,jdbc.queryForObject("SELECT can_community_curate FROM operator_account WHERE id=1",Integer.class));assertNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,id));
 }
}
