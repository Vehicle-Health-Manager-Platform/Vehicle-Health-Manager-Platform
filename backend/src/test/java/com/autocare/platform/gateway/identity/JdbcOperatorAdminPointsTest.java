package com.autocare.platform.gateway.identity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class JdbcOperatorAdminPointsTest extends JdbcOperatorIdentityTest {
 String session(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES(?,'operator_account',1,'OPERATOR','operator-account',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR))",id,UUID.randomUUID().toString());return id;}
 MockEnvironment env(String action){return new MockEnvironment().withProperty("spring.main.web-application-type","none").withProperty("operator.action",action).withProperty("operator.id","1");}
 void run(MockEnvironment env){try(var context=new GenericApplicationContext()){context.refresh();new OperatorAdminCli(env,context,jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new ObjectMapper()).run(new DefaultApplicationArguments(new String[0]));}}
 @Test void pointsGrantsAreIndependentAndRevokeSessions(){
  String old=session();run(env("points-permission").withProperty("OPERATOR_CAN_POINTS","true"));assertEquals(1,jdbc.queryForObject("SELECT can_points FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT can_invite+can_coupon+can_diagnose+can_diagnose_manage FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,old));
  String fresh=session();run(env("points-permission").withProperty("OPERATOR_CAN_POINTS","false"));assertEquals(0,jdbc.queryForObject("SELECT can_points FROM operator_account WHERE id=1",Integer.class));assertNotNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,fresh));
 }
 @Test void pointsLoginExposesOnlyExplicitCapability(){jdbc.update("UPDATE operator_account SET can_points=1 WHERE id=1");code();var result=operators.login("synthetic-operator",password,"123456");var user=(java.util.Map<?,?>)result.get("user");assertEquals(true,user.get("can_points"));assertEquals(false,user.get("can_invite"));assertEquals(false,user.get("can_diagnose"));}
 @Test void explicitFlagsAndNoHttpAreRequired(){
  session();assertThrows(Exception.class,()->run(env("points-permission")));assertThrows(Exception.class,()->run(env("points-permission").withProperty("OPERATOR_CAN_POINTS","TRUE")));assertThrows(Exception.class,()->run(env("points-permission").withProperty("spring.main.web-application-type","servlet").withProperty("OPERATOR_CAN_POINTS","true")));assertEquals(0,jdbc.queryForObject("SELECT can_points FROM operator_account WHERE id=1",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE revoked_at IS NOT NULL",Integer.class));
 }
 @Test void auditFailureRollsBackPermissionAndSessionRevocation(){
  String id=session();jdbc.execute("CREATE TRIGGER reject_operator_audit BEFORE INSERT ON audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic failure'");assertThrows(Exception.class,()->run(env("points-permission").withProperty("OPERATOR_CAN_POINTS","true")));assertEquals(0,jdbc.queryForObject("SELECT can_points FROM operator_account WHERE id=1",Integer.class));assertNull(jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id=?",java.sql.Timestamp.class,id));
 }
}
