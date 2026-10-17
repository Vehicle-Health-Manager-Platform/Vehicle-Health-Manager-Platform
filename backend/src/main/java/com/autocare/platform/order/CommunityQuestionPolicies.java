package com.autocare.platform.order;

import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Non-real-time community admission, independent of private diagnosis and invitation policies. */
public class CommunityQuestionPolicies {
 final ReservationStore db;
 final OperatorIdentity operators;
 public CommunityQuestionPolicies(ReservationStore db,OperatorIdentity operators){this.db=db;this.operators=operators;}
 static org.springframework.web.server.ResponseStatusException conflict(){return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"问答受理、状态或版本已变化，请重新读取");}
 static JsonNode input(String raw,boolean update){return DiagnosisServicePolicies.input(raw,update);}
 void manage(OperatorActor a,boolean lock){operators.authorize(a,OperatorIdentity.Permission.COMMUNITY_MANAGE,lock);}
 void guard(){db.one("SELECT id FROM community_qa_control WHERE id=1 FOR UPDATE");}
 void writeManager(OperatorActor a){manage(a,false);guard();manage(a,true);}
 Map<String,Object> row(long id,boolean lock){return db.one("SELECT * FROM community_qa_service_policy WHERE id=?"+(lock?" FOR UPDATE":""),id);}
 static Map<String,Object> view(Map<String,Object> r){var out=new LinkedHashMap<String,Object>();for(String k:List.of("id","revision","status","mode","source_note"))out.put(k,r.get(k));for(String k:List.of("valid_from","valid_to","created_at","published_at"))out.put(k,iso(r.get(k)));return out;}
 static String hash(ReservationStore db,JsonNode b){var sorted=new TreeMap<String,Object>();b.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));return AuthTokens.sha256(db.json(sorted));}
 Map<String,Object> availability(boolean lock){
  var out=new LinkedHashMap<String,Object>();out.put("state","UNKNOWN");out.put("accepting",false);out.put("policy",null);
  var rows=db.jdbc.queryForList("SELECT * FROM community_qa_service_policy WHERE status='PUBLISHED'"+(lock?" FOR UPDATE":""));if(rows.size()!=1)return out;
  var p=rows.get(0);
  // Owner projection carries admission provenance, never the operator-only source note.
  var policy=new LinkedHashMap<String,Object>();for(String k:List.of("id","revision","mode"))policy.put(k,p.get(k));for(String k:List.of("valid_from","valid_to"))policy.put(k,iso(p.get(k)));out.put("policy",policy);
  long handlers=lock?db.jdbc.queryForList("SELECT id FROM operator_account WHERE status='ACTIVE' AND can_community_answer=1 ORDER BY id FOR UPDATE").size():db.jdbc.queryForObject("SELECT COUNT(*) FROM operator_account WHERE status='ACTIVE' AND can_community_answer=1",Long.class);
  var now=db.now();String state=instant(p.get("valid_from")).isAfter(now)?"NOT_STARTED":!instant(p.get("valid_to")).isAfter(now)?"EXPIRED":"PAUSED".equals(p.get("mode"))?"PAUSED":handlers==0?"SOURCE_UNAVAILABLE":p.get("mode").toString();
  out.put("state",state);out.put("accepting",Set.of("AVAILABLE","LEAVE_MESSAGE").contains(state));return out;
 }
 public Map<String,Object> current(VehicleOwner a){return db.reads.execute(tx->{db.owner(a,false);return availability(false);});}
 public Map<String,Object> list(OperatorActor a,int p){return db.reads.execute(tx->{manage(a,false);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM community_qa_service_policy",Long.class);return page(db.jdbc.queryForList("SELECT * FROM community_qa_service_policy ORDER BY id DESC LIMIT 20 OFFSET ?",(p-1)*20L).stream().map(CommunityQuestionPolicies::view).toList(),total,p,20);});}
 public Map<String,Object> handlers(OperatorActor a,int p){return db.reads.execute(tx->{manage(a,false);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM operator_account WHERE status='ACTIVE' AND can_community_answer=1",Long.class);return page(db.jdbc.queryForList("SELECT id,account AS label FROM operator_account WHERE status='ACTIVE' AND can_community_answer=1 ORDER BY id LIMIT 20 OFFSET ?",(p-1)*20L),total,p,20);});}
 public JsonNode save(OperatorActor a,Long id,String key,JsonNode b){
  String canonical=com.autocare.platform.common.write.WriteIntegrityService.normalizeKey(key),hash=hash(db,b);
  return db.writes.execute(new Actor("operator_account",a.id()),id==null?"POST":"PUT","/api/admin/community/questions/policies"+(id==null?"":"/"+id),key,b,()->{writeManager(a);if(id!=null)row(id,true);},()->{
   if(id==null){var old=db.jdbc.queryForList("SELECT id,submission_hash,creation_result FROM community_qa_service_policy WHERE created_by=? AND submission_key=?",a.id(),canonical);if(!old.isEmpty()){var r=old.get(0);if(!hash.equals(r.get("submission_hash")))throw ReservationInput.bad();var result=db.mapper.convertValue(db.parse(r.get("creation_result")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return new Change("COMMUNITY_POLICY_SAVE","community_qa_service_policy",number(r,"id"),result,result,result,false);}}
   if(!CouponInput.time(b.get("valid_to")).isAfter(db.now()))throw conflict();
   Map<String,Object> before=Map.of();long target;
   if(id==null)target=db.insert("INSERT INTO community_qa_service_policy(mode,source_note,valid_from,valid_to,created_by,created_at,submission_key,submission_hash) VALUES(?,?,?,?,?,?,?,?)",b.path("mode").asText(),b.path("source_note").asText(),time(CouponInput.time(b.get("valid_from"))),time(CouponInput.time(b.get("valid_to"))),a.id(),time(db.now()),canonical,hash);
   else{var r=row(id,true);if(!"DRAFT".equals(r.get("status"))||number(r,"revision")!=b.path("expected_revision").asLong())throw conflict();before=view(r);target=id;db.jdbc.update("UPDATE community_qa_service_policy SET mode=?,source_note=?,valid_from=?,valid_to=?,revision=revision+1 WHERE id=?",b.path("mode").asText(),b.path("source_note").asText(),time(CouponInput.time(b.get("valid_from"))),time(CouponInput.time(b.get("valid_to"))),id);}
   var after=view(row(target,true));if(id==null)db.jdbc.update("UPDATE community_qa_service_policy SET creation_result=? WHERE id=?",db.json(after),target);return new Change("COMMUNITY_POLICY_SAVE","community_qa_service_policy",target,before,after,after);
  });
 }
 public JsonNode status(OperatorActor a,long id,String key,JsonNode b){return db.writes.execute(new Actor("operator_account",a.id()),"PUT","/api/admin/community/questions/policies/"+id+"/status",key,b,()->{writeManager(a);row(id,true);},()->{
  var r=row(id,true);String to=b.path("status").asText();if(number(r,"revision")!=b.path("expected_revision").asLong()||!(to.equals("PUBLISHED")?"DRAFT".equals(r.get("status")):"STOPPED".equals(to)&&"PUBLISHED".equals(r.get("status"))))throw conflict();
  if(to.equals("PUBLISHED")&&(!instant(r.get("valid_to")).isAfter(db.now())||!db.jdbc.queryForList("SELECT id FROM community_qa_service_policy WHERE status='PUBLISHED' FOR UPDATE").isEmpty()||!"PAUSED".equals(r.get("mode"))&&db.jdbc.queryForList("SELECT id FROM operator_account WHERE status='ACTIVE' AND can_community_answer=1 ORDER BY id FOR UPDATE").isEmpty()))throw conflict();
  var before=view(r);db.jdbc.update("UPDATE community_qa_service_policy SET status=?,revision=revision+1,published_at=? WHERE id=?",to,to.equals("PUBLISHED")?time(db.now()):r.get("published_at"),id);var after=view(row(id,true));return new Change("COMMUNITY_POLICY_STATUS","community_qa_service_policy",id,before,after,after);
 });}
}
