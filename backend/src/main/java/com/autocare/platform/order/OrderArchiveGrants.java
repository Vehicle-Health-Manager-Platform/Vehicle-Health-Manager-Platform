package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.file.FileMetadataRepository;
import com.autocare.platform.vehicle.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

/** Explicit consent for the current task and exact record version, never all customer files. */
public class OrderArchiveGrants {
 private final ServiceTaskScope scope;private final ReservationStore db;
 public OrderArchiveGrants(ServiceTaskScope scope){this.scope=scope;this.db=scope.db;}
 static JsonNode input(String raw){var b=CouponInput.parse(raw);String status=b.path("status").asText();if(!Set.of("ACTIVE","REVOKED").contains(status))throw ReservationInput.bad();if(status.equals("ACTIVE")){CouponInput.exact(b,"status","expected_revision","source_version");if(!b.path("source_version").isTextual()||!b.path("source_version").asText().matches("[0-9a-f]{64}"))throw ReservationInput.bad();}else CouponInput.exact(b,"status","expected_revision");CouponInput.version(b.get("expected_revision"));return b;}
 private Map<String,Object> grant(long order,long archive,boolean lock){var rows=db.jdbc.queryForList("SELECT * FROM order_archive_grant WHERE order_id=? AND archive_id=?"+(lock?" FOR UPDATE":""),order,archive);return rows.isEmpty()?null:rows.get(0);}
 private record Source(Map<String,Object> view,String version,Map<Long,FileMetadataRepository.Actor> files){}
 private JsonNode canonical(JsonNode n){if(n.isObject()){var out=db.mapper.createObjectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);for(String k:names)out.set(k,canonical(n.get(k)));return out;}if(n.isArray()){var out=db.mapper.createArrayNode();n.forEach(v->out.add(canonical(v)));return out;}return n;}
 private Source source(Map<String,Object> o,long archive,boolean lock){
  var a=db.one("SELECT *,DATE_FORMAT(recorded_at,'%Y-%m-%d') AS recorded_date FROM vehicle_archive WHERE id=? AND vehicle_id=? AND is_deleted=0 AND archive_type IN (1,2,10) AND input_type IN (1,3,4)"+(lock?" FOR UPDATE":""),archive,o.get("vehicle_id"));
  long owner=number(o,"user_id");var body=db.parse(a.get("content"));if(!body.isObject()||number(a,"input_type")==4&&(!body.path("source").path("user_id").isIntegralNumber()||body.path("source").path("user_id").asLong()!=owner))throw missing();
  var structured=db.jdbc.queryForList("SELECT fields_json,revision FROM archive_structured_detail WHERE archive_id=? AND user_id=? AND vehicle_id=? AND archive_type=? AND is_deleted=0"+(lock?" FOR UPDATE":""),archive,owner,o.get("vehicle_id"),a.get("archive_type"));
  JsonNode fields=structured.isEmpty()?db.mapper.createObjectNode():db.parse(structured.get(0).get("fields_json"));var safeFields=db.mapper.createObjectNode();
  var keys=switch((int)number(a,"archive_type")){case 1->List.of("service_items","performed_mileage","service_date","oil_spec","parts_used","next_due_date","next_due_mileage");case 2->List.of("repair_items","performed_mileage","repair_date","problem_description","parts_used","result_notes");default->List.of("fault_code","fault_description","observed_on","fault_status","cleared_on","attention_level");};
  if(fields.isObject())try{new ArchiveStructuredInput(0,(ObjectNode)fields).validateFor((int)number(a,"archive_type"));for(String k:keys)if(fields.has(k))safeFields.set(k,fields.get(k));}catch(org.springframework.web.server.ResponseStatusException e){safeFields.removeAll();}
  var fileIds=db.jdbc.queryForList("SELECT file_id FROM vehicle_archive_file WHERE archive_id=? ORDER BY position,file_id",Long.class,archive);
  var hash=db.mapper.createObjectNode();hash.set("content",body);hash.set("structured",fields);hash.put("structured_revision",structured.isEmpty()?0:number(structured.get(0),"revision"));hash.set("files",db.mapper.valueToTree(fileIds));for(String k:List.of("id","vehicle_id","archive_type","input_type","recorded_date"))hash.set(k,db.mapper.valueToTree(a.get(k)));
  String version=com.autocare.platform.gateway.identity.AuthTokens.sha256(canonical(hash).toString());
  var actors=new HashMap<Long,FileMetadataRepository.Actor>();String safe="f.scan_status='CLEAN' AND f.is_deleted=0 AND f.content_type IN ('image/jpeg','image/png') AND f.size_bytes BETWEEN 1 AND 10485760";
  List<Map<String,Object>> files;
  if(number(a,"input_type")!=4)files=db.jdbc.queryForList("SELECT f.id,f.owner_type,f.owner_id FROM vehicle_archive_file af JOIN file_object f ON f.id=af.file_id WHERE af.archive_id=? AND f.owner_type='user' AND f.owner_id=? AND "+safe,archive,owner);
  else files=db.jdbc.queryForList("SELECT f.id,f.owner_type,f.owner_id FROM vehicle_archive a JOIN vehicle_archive_file af ON af.archive_id=a.id JOIN service_archive_job j ON j.archive_id=a.id AND j.status='DONE' JOIN `order` o ON o.id=j.order_id AND o.user_id=? AND o.vehicle_id=a.vehicle_id AND o.is_deleted=0 JOIN service_evidence_file e ON e.order_id=o.id AND e.file_id=af.file_id AND e.record_id=JSON_EXTRACT(a.content,'$.source.report_id') AND e.kind IN ('PROCESS','FAULT','FINISH') JOIN technician_report t ON t.id=e.record_id AND t.order_id=o.id AND t.is_deleted=0 AND t.status=1 AND t.signed_at IS NOT NULL JOIN file_object f ON f.id=af.file_id AND f.owner_type='staff_account' AND f.owner_id=t.technician_id WHERE a.id=? AND "+safe,owner,archive);
  for(var f:files)actors.put(number(f,"id"),new FileMetadataRepository.Actor(f.get("owner_type").toString(),number(f,"owner_id")));
  var out=new LinkedHashMap<String,Object>();out.put("archive_id",archive);out.put("archive_type",a.get("archive_type"));out.put("recorded_date",a.get("recorded_date"));out.put("title",body.path("title").isTextual()?body.path("title").asText():"");out.put("notes",body.path("notes").isTextual()?body.path("notes").asText():"");out.put("mileage",body.path("mileage").isIntegralNumber()?body.path("mileage").longValue():null);out.put("fields",safeFields);out.put("files",fileIds.stream().map(f->Map.of("file_id",f,"available",actors.containsKey(f))).toList());
  return new Source(out,version,actors);
 }
 public Map<String,Object> candidates(VehicleOwner a,long order,int page){return db.reads.execute(tx->{var o=scope.ownerOrder(a,order,false);scope.vehicle(o,false);
  String where=" FROM vehicle_archive WHERE vehicle_id=? AND is_deleted=0 AND archive_type IN (1,2,10) AND (input_type IN (1,3) OR (input_type=4 AND JSON_EXTRACT(content,'$.source.user_id')=?))";
  long total=db.jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,o.get("vehicle_id"),a.id());var rows=db.jdbc.queryForList("SELECT id"+where+" ORDER BY recorded_at DESC,id DESC LIMIT 20 OFFSET ?",o.get("vehicle_id"),a.id(),(page-1)*20L);var items=new ArrayList<Map<String,Object>>();
  for(var row:rows){long archive=number(row,"id");var s=source(o,archive,false);var g=grant(order,archive,false);String state=g==null||number(g,"owner_id")!=a.id()?"NONE":!"ACTIVE".equals(g.get("status"))?"REVOKED":!s.version().equals(g.get("source_version"))?"SOURCE_CHANGED":"ACTIVE";var item=new LinkedHashMap<String,Object>();item.put("record",s.view());item.put("source_version",s.version());item.put("grant_revision",g==null?0:number(g,"revision"));item.put("grant_state",state);items.add(item);}
  return Map.of("order_id",order,"service_active",scope.serviceActive(o),"records",page(items,total,page,20));
 });}
 public JsonNode set(VehicleOwner a,long order,long archive,String key,JsonNode b){
  com.autocare.platform.service.ServiceCatalog.validateId(archive);
  return db.writes.execute(new Actor("user",a.id()),"PUT","/api/order/"+order+"/archive-grants/"+archive,key,b,()->{var o=scope.ownerOrder(a,order,true);if(b.path("status").asText().equals("ACTIVE")){if(!scope.serviceActive(o))throw missing();scope.vehicle(o,true);source(o,archive,true);}grant(order,archive,true);},()->{
   var o=scope.ownerOrder(a,order,true);var g=grant(order,archive,true);long revision=g==null?0:number(g,"revision");if(revision!=b.path("expected_revision").longValue())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"授权版本已变化，请重新读取");
   String target=b.path("status").asText();String version=target.equals("ACTIVE")?source(o,archive,true).version():null;if(target.equals("ACTIVE")&&!version.equals(b.path("source_version").asText()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"档案内容已变化，请重新确认");if(g==null&&target.equals("REVOKED"))throw missing();
   long id=g==null?db.insert("INSERT INTO order_archive_grant(order_id,archive_id,owner_id,status,source_version,granted_at) VALUES(?,?,?,'ACTIVE',?,?)",order,archive,a.id(),version,time(db.now())):number(g,"id");
   if(g!=null)db.jdbc.update("UPDATE order_archive_grant SET owner_id=?,status=?,source_version=?,revision=revision+1,granted_at=?,revoked_at=? WHERE id=?",a.id(),target,version,target.equals("ACTIVE")?time(db.now()):g.get("granted_at"),target.equals("REVOKED")?time(db.now()):null,id);
   var after=Map.<String,Object>of("order_id",order,"archive_id",archive,"grant_revision",revision+1,"grant_state",target);
   return new Change("ORDER_ARCHIVE_GRANT","order_archive_grant",id,g==null?Map.of():Map.of("revision",revision,"status",g.get("status")),after,after);
  });
 }
 private Source granted(Map<String,Object> o,long archive){var g=grant(number(o,"id"),archive,false);if(g==null||!"ACTIVE".equals(g.get("status"))||number(g,"owner_id")!=number(o,"user_id"))throw missing();var s=source(o,archive,false);if(!s.version().equals(g.get("source_version")))throw missing();return s;}
 public Map<String,Object> context(Object actor,long order,int page){return db.reads.execute(tx->{var o=scope.task(actor,order);long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM order_archive_grant WHERE order_id=? AND owner_id=? AND status='ACTIVE'",Long.class,order,o.get("user_id"));var rows=db.jdbc.queryForList("SELECT archive_id FROM order_archive_grant WHERE order_id=? AND owner_id=? AND status='ACTIVE' ORDER BY id LIMIT 20 OFFSET ?",order,o.get("user_id"),(page-1)*20L);var items=new ArrayList<Map<String,Object>>();
  for(var row:rows){long archive=number(row,"archive_id");var item=new LinkedHashMap<String,Object>();item.put("archive_id",archive);try{item.put("record",granted(o,archive).view());item.put("state","ACTIVE");}catch(org.springframework.web.server.ResponseStatusException e){if(e.getStatusCode().value()!=404)throw e;item.put("record",null);item.put("state","SOURCE_UNAVAILABLE");}items.add(item);}
  var out=new LinkedHashMap<String,Object>();out.put("order_id",order);out.put("order_status",o.get("status"));out.put("vehicle",scope.basic(o));out.put("project",MerchantOrders.snapshot(db.parse(o.get("project_snapshot")),"project_snapshot"));var pickup=db.jdbc.queryForList("SELECT mileage FROM pickup_check WHERE order_id=? AND merchant_id=? AND is_deleted=0",order,o.get("merchant_id"));out.put("arrival_mileage",pickup.size()==1?pickup.get(0).get("mileage"):null);out.put("archives",page(items,total,page,20));return out;
 });}
 public FileMetadataRepository.Actor fileOwner(Object actor,long order,long archive,long file){return db.reads.execute(tx->{var o=scope.task(actor,order);var a=granted(o,archive).files().get(file);if(a==null)throw missing();return a;});}
 public FileMetadataRepository.Actor ownerPreview(VehicleOwner actor,long order,long archive,long file){return db.reads.execute(tx->{var o=scope.ownerOrder(actor,order,false);scope.vehicle(o,false);var a=source(o,archive,false).files().get(file);if(a==null)throw missing();return a;});}
}
