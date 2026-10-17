package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Explicit immutable revisions; source changes are never silently written over old grades. */
public class AssessmentSnapshots {
 public static final String PATH="/api/admin/assessment/snapshots";
 private final ReservationStore db;private final OperatorIdentity operators;private final AssessmentPolicies policies;final AssessmentFacts sources;
 public AssessmentSnapshots(ReservationStore db,OperatorIdentity operators,AssessmentPolicies policies,PlatformTransactionManager manager){this.db=db;this.operators=operators;this.policies=policies;this.sources=new AssessmentFacts(db,manager);}
 private void authorize(OperatorActor a,boolean lock){operators.authorize(a,OperatorIdentity.Permission.ASSESSMENT,lock);}
 private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"月份尚未结束或结算修订已变化，请重新读取");}
 private void ended(YearMonth month){if(AssessmentCalculation.start(month.plusMonths(1)).isAfter(db.now()))throw conflict();}
 private String hash(Object n){return AuthTokens.sha256(db.json(AssessmentPolicyInput.canonical(db.mapper.valueToTree(n))));}
 Map<String,Object> row(long id){return db.one("SELECT * FROM assessment_snapshot WHERE id=?",id);}
 Map<String,Object> view(Map<String,Object> r){var out=new LinkedHashMap<String,Object>();for(String k:List.of("id","merchant_id","period_month","revision","facts_hash","calculation_version"))out.put(k,r.get(k));out.put("timezone","Asia/Shanghai");out.put("policy",db.parse(r.get("policy_snapshot")));out.put("summary",db.parse(r.get("summary")));for(String k:List.of("captured_from","captured_to"))out.put(k,iso(r.get(k)));return out;}
 private void pageNumber(int p){if(p<1||p>1000000)throw ReservationInput.bad();}
 public Map<String,Object> list(OperatorActor a,int p){pageNumber(p);return db.reads.execute(tx->{authorize(a,false);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM assessment_snapshot",Long.class);return page(db.jdbc.queryForList("SELECT * FROM assessment_snapshot ORDER BY id DESC LIMIT 20 OFFSET ?",(p-1)*20L).stream().map(this::view).toList(),total,p,20);});}
 public Map<String,Object> detail(OperatorActor a,long id){com.autocare.platform.service.ServiceCatalog.validateId(id);return db.reads.execute(tx->{authorize(a,false);return view(row(id));});}
 public Map<String,Object> facts(OperatorActor a,long id,int p){com.autocare.platform.service.ServiceCatalog.validateId(id);pageNumber(p);return db.reads.execute(tx->{authorize(a,false);row(id);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM assessment_snapshot_fact WHERE snapshot_id=?",Long.class,id);return page(db.jdbc.queryForList("SELECT fact_json FROM assessment_snapshot_fact WHERE snapshot_id=? ORDER BY ordinal LIMIT 20 OFFSET ?",id,(p-1)*20L).stream().map(r->db.parse(r.get("fact_json"))).toList(),total,p,20);});}
 JsonNode policy(long shop,YearMonth month){return policy(shop,month,true);}
 JsonNode policy(long shop,YearMonth month,boolean lock){Instant start=AssessmentCalculation.start(month),end=AssessmentCalculation.start(month.plusMonths(1));var matches=db.jdbc.queryForList("SELECT * FROM assessment_policy WHERE merchant_id=? AND status IN ('PUBLISHED','STOPPED') AND valid_from<=? AND valid_to>=? AND published_at<=? AND ((status='PUBLISHED' AND stopped_at IS NULL) OR (status='STOPPED' AND stopped_at>=?)) ORDER BY id"+(lock?" FOR UPDATE":""),shop,time(start),time(end),time(start),time(end));if(matches.size()!=1)return db.mapper.nullNode();var r=matches.get(0);try{var result=db.mapper.valueToTree(policies.view(r));var body=db.mapper.createObjectNode();for(String k:List.of("merchant_id","acquisition_target","coupon_target","process_requirements","grade_thresholds","source_note","valid_from","valid_to"))body.set(k,result.get(k));AssessmentPolicyInput.body(body.toString(),false);if(!AssessmentPolicies.RULE.equals(r.get("rule_version")))return db.mapper.nullNode();((com.fasterxml.jackson.databind.node.ObjectNode)result).put("stopped_at",iso(r.get("stopped_at")));return result;}catch(ResponseStatusException invalid){return db.mapper.nullNode();}}
 public JsonNode create(OperatorActor a,String key,JsonNode body){String normalized=WriteIntegrityService.normalizeKey(key),digest=hash(body);YearMonth month=AssessmentCalculation.month(body.path("period_month").asText());long shop=body.path("merchant_id").asLong();return db.writes.execute(new Actor("operator_account",a.id()),"POST",PATH,key,body,()->authorize(a,true),()->{
  var previous=db.jdbc.queryForList("SELECT id,submission_hash,creation_result FROM assessment_snapshot WHERE created_by=? AND submission_key=?",a.id(),normalized);
  if(!previous.isEmpty()){var r=previous.get(0);if(!digest.equals(r.get("submission_hash")))throw ReservationInput.bad();var saved=db.parse(r.get("creation_result"));if(!saved.isObject())throw new IllegalStateException("Assessment result receipt missing");Map<String,Object> data=db.mapper.convertValue(saved,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return new Change("ASSESSMENT_MONTH_SETTLE","assessment_snapshot",number(r,"id"),Map.of(),Map.of(),data,false);}
  db.one("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",shop);ended(month);
  db.jdbc.update("INSERT INTO assessment_month(merchant_id,period_month,revision) VALUES(?,?,0) ON DUPLICATE KEY UPDATE merchant_id=VALUES(merchant_id)",shop,month.toString());long revision=number(db.one("SELECT revision FROM assessment_month WHERE merchant_id=? AND period_month=? FOR UPDATE",shop,month.toString()),"revision");if(revision!=body.path("expected_revision").asLong()||revision>=9007199254740990L)throw conflict();
  JsonNode policy=policy(shop,month);var batch=sources.collect(shop,month,policy);authorize(a,true);ended(month);var summary=AssessmentCalculation.summary(policy,batch.facts());String factsHash=hash(Map.of("policy",policy,"facts",batch.facts(),"summary",summary,"calculation_version",AssessmentCalculation.VERSION));
  long id=db.insert("INSERT INTO assessment_snapshot(merchant_id,period_month,revision,policy_snapshot,summary,facts_hash,captured_from,captured_to,created_by,submission_key,submission_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?)",shop,month.toString(),revision+1,policy.toString(),db.json(summary),factsHash,time(batch.from()),time(batch.to()),a.id(),normalized,digest);
  int ordinal=0;for(var fact:batch.facts())db.jdbc.update("INSERT INTO assessment_snapshot_fact(snapshot_id,ordinal,fact_json) VALUES(?,?,?)",id,++ordinal,db.json(fact));db.jdbc.update("UPDATE assessment_month SET revision=? WHERE merchant_id=? AND period_month=?",revision+1,shop,month.toString());var after=view(row(id));db.jdbc.update("UPDATE assessment_snapshot SET creation_result=? WHERE id=?",db.json(after),id);return new Change("ASSESSMENT_MONTH_SETTLE","assessment_snapshot",id,Map.of("revision",revision),after,after);
 });}
}
