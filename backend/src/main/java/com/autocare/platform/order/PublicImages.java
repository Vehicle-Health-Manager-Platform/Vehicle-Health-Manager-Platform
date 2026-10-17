package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.file.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Public copies are immutable PNG bytes, never signatures for private originals. */
public class PublicImages {
 static final String CONSENT="R8C_IMAGE_V1";
 private final ReservationStore db;
 private final OperatorIdentity operators;
 private final ExperienceModeration experiences;
 private final SignedObjectStore store;
 private final boolean enabled;
 public PublicImages(ReservationStore db,OperatorIdentity operators,ExperienceModeration experiences,SignedObjectStore store,boolean enabled){this.db=db;this.operators=operators;this.experiences=experiences;this.store=store;this.enabled=enabled;}
 private void enabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"图片独立公开尚未开启");}
 private void guard(){db.one("SELECT id FROM public_image_control WHERE id=1 FOR UPDATE");}
 static JsonNode input(String raw,String action){
  var b=CouponInput.parse(raw);
  if(Set.of("preview","submit").contains(action)){
   if(action.equals("preview"))CouponInput.exact(b,"experience_id","archive_id","source_file_id","copy_file_id");
   else CouponInput.exact(b,"experience_id","archive_id","source_file_id","copy_file_id","preview_hash","consent_version","consent");
   ExperienceModeration.publicId(b.path("experience_id").asText());
   for(String k:List.of("archive_id","source_file_id","copy_file_id"))CouponInput.id(b.get(k));
   if(b.path("source_file_id").asLong()==b.path("copy_file_id").asLong())throw ReservationInput.bad();
   if(action.equals("submit")&&(!b.path("preview_hash").isTextual()||!b.path("preview_hash").asText().matches("[a-f0-9]{64}")||!CONSENT.equals(b.path("consent_version").asText())||!b.path("consent").isBoolean()||!b.path("consent").asBoolean()))throw ReservationInput.bad();
  }else{
   CouponInput.exact(b,action.equals("moderate")?new String[]{"expected_revision","decision","reason_code"}:new String[]{"expected_revision"});CouponInput.id(b.get("expected_revision"));
   if(action.equals("moderate")){String d=b.path("decision").asText();if(!Set.of("APPROVE","REJECT").contains(d)||d.equals("APPROVE")&&!b.get("reason_code").isNull()||d.equals("REJECT")&&!PublicReviewTextInput.REASONS.contains(b.path("reason_code").asText()))throw ReservationInput.bad();}
  }return b;
 }
 private String hash(Object value){return CommunityQuestionSource.hash(db,value);}
 static String bytesHash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 private Map<String,Object> parent(long owner,String reference,boolean lock){
  var p=experiences.publishedSource(reference,lock);
  var c=db.one("SELECT c.vehicle_id,c.user_id FROM experience_card c JOIN experience_card_moderation m ON m.card_id=c.id WHERE m.public_id=?"+(lock?" FOR UPDATE":""),reference);
  if(number(c,"user_id")!=owner)throw missing();
  return Map.of("public",p,"vehicle_id",c.get("vehicle_id"));
 }
 private Map<String,Object> file(long owner,long id,boolean lock){var f=db.one("SELECT id,object_key,content_type,size_bytes,scan_status,updated_at FROM file_object WHERE id=? AND owner_type='user' AND owner_id=? AND is_deleted=0 AND scan_status='CLEAN'"+(lock?" FOR UPDATE":""),id,owner);f.put("updated_at",iso(f.get("updated_at")));return f;}
 private Map<String,Object> source(long owner,JsonNode b,boolean lock){
  var p=parent(owner,b.path("experience_id").asText(),lock);String tail=lock?" FOR UPDATE":"";
  var a=db.one("SELECT a.id,a.content,a.updated_at FROM vehicle_archive a JOIN vehicle_archive_file af ON af.archive_id=a.id WHERE a.id=? AND a.vehicle_id=? AND a.is_deleted=0 AND af.file_id=?"+tail,b.path("archive_id").asLong(),p.get("vehicle_id"),b.path("source_file_id").asLong());
  a.put("updated_at",iso(a.get("updated_at")));
  var original=file(owner,b.path("source_file_id").asLong(),lock);var copy=file(owner,b.path("copy_file_id").asLong(),lock);
  if(!Set.of("image/jpeg","image/png").contains(copy.get("content_type")))throw ReservationInput.bad();
  return Map.of("parent",p,"archive",a,"original",original,"copy",copy);
 }
 private JsonNode selectors(Map<String,Object> r){return db.mapper.valueToTree(Map.of("experience_id",r.get("experience_id"),"archive_id",r.get("archive_id"),"source_file_id",r.get("source_file_id"),"copy_file_id",r.get("copy_file_id")));}
 private record Prepared(String sourceHash,String previewHash,byte[] png,int width,int height,long model){}
 private Prepared prepare(VehicleOwner a,JsonNode b){
  enabled();var s=db.reads.execute(t->{db.owner(a,false);return source(a.id(),b,false);});
  var c=(Map<?,?>)s.get("copy");if(store==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"脱敏图片存储尚未配置");
  byte[] bytes;try{if(!store.isPrivate())throw new IllegalStateException();var stat=store.stat(c.get("object_key").toString());if(stat.sizeBytes()!=((Number)c.get("size_bytes")).longValue()||!stat.contentType().equals(c.get("content_type")))throw new IllegalStateException();bytes=store.read(c.get("object_key").toString(),FileValidator.MAX_BYTES);if(bytes.length!=stat.sizeBytes())throw new IllegalStateException();}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"脱敏副本暂不可读取");}
  var image=new PublicImageRenderer().render(bytes);String sourceHash=hash(s),previewHash=hash(Map.of("source",sourceHash,"png",bytesHash(image.png()),"width",image.width(),"height",image.height()));
  var p=(Map<?,?>)((Map<?,?>)s.get("parent")).get("public");return new Prepared(sourceHash,previewHash,image.png(),image.width(),image.height(),((Number)p.get("model_id")).longValue());
 }
 public Map<String,Object> sources(VehicleOwner a,String reference,int p){return db.reads.execute(t->{db.owner(a,false);enabled();var parent=parent(a.id(),reference,false);String sql=" FROM vehicle_archive a JOIN vehicle_archive_file af ON af.archive_id=a.id JOIN file_object f ON f.id=af.file_id AND f.owner_type='user' AND f.owner_id=? AND f.is_deleted=0 AND f.scan_status='CLEAN' WHERE a.vehicle_id=? AND a.is_deleted=0";long count=db.jdbc.queryForObject("SELECT COUNT(*)"+sql,Long.class,a.id(),parent.get("vehicle_id"));return page(db.jdbc.queryForList("SELECT a.id AS archive_id,f.id AS source_file_id"+sql+" ORDER BY a.id DESC,af.position LIMIT 20 OFFSET ?",a.id(),parent.get("vehicle_id"),(p-1)*20L),count,p,20);});}
 public Map<String,Object> preview(VehicleOwner a,JsonNode b){var p=prepare(a,b);return Map.of("preview_hash",p.previewHash(),"png_base64",Base64.getEncoder().encodeToString(p.png()),"width",p.width(),"height",p.height(),"model_id",p.model());}
 private Map<String,Object> row(long id,boolean lock){return db.one("SELECT * FROM public_image_application WHERE id=?"+(lock?" FOR UPDATE":""),id);}
 private Map<String,Object> receipt(Map<String,Object> r){return Map.of("application_id",r.get("id"),"revision",r.get("revision"),"status",r.get("status"));}
 private Map<String,Object> audit(Map<String,Object> r){var out=new LinkedHashMap<String,Object>(receipt(r));out.put("preview_hash",r.get("preview_hash"));out.put("public_id",r.get("public_id"));return out;}
 private void event(Map<String,Object> r){db.jdbc.update("INSERT INTO public_image_event(application_id,revision,status,preview_hash,public_id,occurred_at) VALUES(?,?,?,?,?,?)",r.get("id"),r.get("revision"),r.get("status"),r.get("preview_hash"),r.get("public_id"),"APPROVED".equals(r.get("status"))?r.get("published_at"):time(db.now()));}
 private boolean trusted(Map<String,Object> r){
  if(!CONSENT.equals(r.get("consent_version")))return false;
  String bound=hash(Map.of("source",r.get("source_hash"),"png",bytesHash((byte[])r.get("png")),"width",r.get("width"),"height",r.get("height")));
  if(!bound.equals(r.get("preview_hash")))return false;
  var events=db.jdbc.queryForList("SELECT * FROM public_image_event WHERE application_id=? ORDER BY revision DESC LIMIT 1",r.get("id"));
  if(events.size()!=1)return false;var e=events.get(0);
  if(number(e,"is_deleted")!=0||number(e,"revision")!=number(r,"revision")||!Objects.equals(e.get("status"),r.get("status"))||!Objects.equals(e.get("preview_hash"),r.get("preview_hash"))||!Objects.equals(e.get("public_id"),r.get("public_id"))||"APPROVED".equals(r.get("status"))&&!Objects.equals(iso(e.get("occurred_at")),iso(r.get("published_at"))))return false;
  var logs=db.jdbc.queryForList("SELECT after_state,is_deleted FROM audit_log WHERE resource_type='public_image_application' AND resource_id=? AND action IN ('PUBLIC_IMAGE_SUBMIT','PUBLIC_IMAGE_MODERATE','PUBLIC_IMAGE_WITHDRAW') ORDER BY id DESC LIMIT 1",r.get("id"));
  return logs.size()==1&&number(logs.get(0),"is_deleted")==0&&hash(db.parse(logs.get(0).get("after_state"))).equals(hash(audit(r)));
 }
 private boolean sourceValid(Map<String,Object> r){try{var s=source(number(r,"owner_id"),selectors(r),false);var parent=(Map<?,?>)((Map<?,?>)s.get("parent")).get("public");return ((Number)parent.get("model_id")).longValue()==number(r,"model_id")&&hash(s).equals(r.get("source_hash"));}catch(ResponseStatusException e){return false;}}
 private boolean visible(Map<String,Object> r){return enabled&&"APPROVED".equals(r.get("status"))&&r.get("public_id")!=null&&trusted(r)&&sourceValid(r);}
 private Map<String,Object> view(Map<String,Object> r,boolean own){var out=new LinkedHashMap<String,Object>(receipt(r));out.put("preview_hash",r.get("preview_hash"));out.put("experience_id",r.get("experience_id"));out.put("model_id",r.get("model_id"));out.put("width",r.get("width"));out.put("height",r.get("height"));out.put("created_at",iso(r.get("created_at")));out.put("reason_code",r.get("reason_code"));out.put("source_available",trusted(r)&&sourceValid(r));if(own){out.put("public_id",r.get("public_id"));out.put("visible",visible(r));}return out;}
 public Map<String,Object> own(VehicleOwner a,int p){return db.reads.execute(t->{db.owner(a,false);long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE owner_id=?",Long.class,a.id());return page(db.jdbc.queryForList("SELECT * FROM public_image_application WHERE owner_id=? ORDER BY id DESC LIMIT 20 OFFSET ?",a.id(),(p-1)*20L).stream().map(r->view(r,true)).toList(),count,p,20);});}
 public Map<String,Object> pending(OperatorActor a,int p){return db.reads.execute(t->{operators.authorize(a,OperatorIdentity.Permission.REVIEW,false);enabled();long count=db.jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE status='PENDING'",Long.class);return page(db.jdbc.queryForList("SELECT * FROM public_image_application WHERE status='PENDING' ORDER BY id DESC LIMIT 20 OFFSET ?",(p-1)*20L).stream().map(r->view(r,false)).toList(),count,p,20);});}
 public byte[] ownerPreview(VehicleOwner a,long id){return db.reads.execute(t->{db.owner(a,false);var r=row(id,false);if(number(r,"owner_id")!=a.id()||!trusted(r))throw missing();return (byte[])r.get("png");});}
 public byte[] reviewPreview(OperatorActor a,long id){return db.reads.execute(t->{operators.authorize(a,OperatorIdentity.Permission.REVIEW,false);enabled();var r=row(id,false);if(!"PENDING".equals(r.get("status"))||!trusted(r)||!sourceValid(r))throw missing();return (byte[])r.get("png");});}
 public JsonNode submit(VehicleOwner a,String key,JsonNode b){
  String canonical=WriteIntegrityService.normalizeKey(key),bodyHash=hash(b);
  // Replays must remain possible after the original file or feature becomes unavailable.
  var old=db.reads.execute(t->{db.owner(a,false);return db.jdbc.queryForList("SELECT id FROM public_image_application WHERE owner_id=? AND submission_key=?",a.id(),canonical);});
  Prepared prepared=old.isEmpty()?prepare(a,b):null;
  return db.writes.execute(new Actor("user",a.id()),"POST","/api/owner/community/images",key,b,()->{db.owner(a,false);guard();db.owner(a,true);},()->{
   var previous=db.jdbc.queryForList("SELECT * FROM public_image_application WHERE owner_id=? AND submission_key=? FOR UPDATE",a.id(),canonical);
   if(!previous.isEmpty()){var r=previous.get(0);if(!bodyHash.equals(r.get("submission_hash")))throw ReservationInput.bad();var data=db.mapper.convertValue(db.parse(r.get("creation_result")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});return new Change("PUBLIC_IMAGE_SUBMIT","public_image_application",number(r,"id"),data,data,data,false);}
   enabled();if(prepared==null||!prepared.previewHash().equals(b.path("preview_hash").asText())||!prepared.sourceHash().equals(hash(source(a.id(),b,true)))||!db.jdbc.queryForList("SELECT id FROM public_image_application WHERE owner_id=? AND experience_id=? AND active_source=? FOR UPDATE",a.id(),b.path("experience_id").asText(),b.path("source_file_id").asLong()).isEmpty())throw CommunityQuestionPolicies.conflict();
   long active=db.jdbc.queryForObject("SELECT COUNT(*) FROM public_image_application WHERE experience_id=? AND status IN ('PENDING','APPROVED')",Long.class,b.path("experience_id").asText());if(active>=20)throw CommunityQuestionPolicies.conflict();
   long id=db.insert("INSERT INTO public_image_application(owner_id,experience_id,model_id,archive_id,source_file_id,copy_file_id,source_hash,preview_hash,png,width,height,consent_version,created_at,submission_key,submission_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",a.id(),b.path("experience_id").asText(),prepared.model(),b.path("archive_id").asLong(),b.path("source_file_id").asLong(),b.path("copy_file_id").asLong(),prepared.sourceHash(),prepared.previewHash(),prepared.png(),prepared.width(),prepared.height(),CONSENT,time(db.now()),canonical,bodyHash);
   var r=row(id,true);var data=receipt(r);db.jdbc.update("UPDATE public_image_application SET creation_result=? WHERE id=?",db.json(data),id);event(r);return new Change("PUBLIC_IMAGE_SUBMIT","public_image_application",id,Map.of(),audit(r),data);
  });
 }
 public JsonNode withdraw(VehicleOwner a,long id,String key,JsonNode b){return db.writes.execute(new Actor("user",a.id()),"POST","/api/owner/community/images/"+id+"/withdraw",key,b,()->{db.owner(a,false);guard();db.owner(a,true);if(number(row(id,true),"owner_id")!=a.id())throw missing();},()->{
  var r=row(id,true);long expected=b.path("expected_revision").asLong();if("WITHDRAWN".equals(r.get("status"))&&number(r,"revision")==expected+1&&trusted(r)){var data=receipt(r);return new Change("PUBLIC_IMAGE_WITHDRAW","public_image_application",id,data,data,data,false);}
  if("WITHDRAWN".equals(r.get("status"))||number(r,"revision")!=expected)throw CommunityQuestionPolicies.conflict();var before=audit(r);db.jdbc.update("UPDATE public_image_application SET status='WITHDRAWN',revision=revision+1 WHERE id=?",id);var after=row(id,true);event(after);return new Change("PUBLIC_IMAGE_WITHDRAW","public_image_application",id,before,audit(after),receipt(after));
 });}
 public JsonNode moderate(OperatorActor a,long id,String key,JsonNode b){String target=b.path("decision").asText().equals("APPROVE")?"APPROVED":"REJECTED";return db.writes.execute(new Actor("operator_account",a.id()),"POST","/api/admin/community/images/"+id+"/moderate",key,b,()->{
  operators.authorize(a,OperatorIdentity.Permission.REVIEW,false);guard();operators.authorize(a,OperatorIdentity.Permission.REVIEW,true);enabled();var r=row(id,true);long expected=b.path("expected_revision").asLong();if(!trusted(r)||!(number(r,"revision")==expected&&"PENDING".equals(r.get("status"))||number(r,"revision")==expected+1&&target.equals(r.get("status"))&&Objects.equals(r.get("reason_code"),b.get("reason_code").isNull()?null:b.path("reason_code").asText())))throw CommunityQuestionPolicies.conflict();if(target.equals("APPROVED")&&!hash(source(number(r,"owner_id"),selectors(r),true)).equals(r.get("source_hash")))throw CommunityQuestionPolicies.conflict();
 },()->{var r=row(id,true);if(number(r,"revision")==b.path("expected_revision").asLong()+1){var data=receipt(r);return new Change("PUBLIC_IMAGE_MODERATE","public_image_application",id,data,data,data,false);}var before=audit(r);db.jdbc.update("UPDATE public_image_application SET status=?,revision=revision+1,reason_code=?,public_id=?,published_at=? WHERE id=?",target,b.get("reason_code").isNull()?null:b.path("reason_code").asText(),target.equals("APPROVED")?UUID.randomUUID().toString():null,target.equals("APPROVED")?time(db.now()):null,id);var after=row(id,true);event(after);return new Change("PUBLIC_IMAGE_MODERATE","public_image_application",id,before,audit(after),receipt(after));});}
 public Map<String,Object> list(VehicleOwner a,long vehicle,String reference){return db.reads.execute(t->{var model=experiences.recipient(a,vehicle);enabled();var parent=experiences.publishedSource(reference,false);if(!Objects.equals(model,parent.get("model_id")))throw missing();var items=new ArrayList<Map<String,Object>>();for(var r:db.jdbc.queryForList("SELECT * FROM public_image_application WHERE experience_id=? AND status='APPROVED' ORDER BY id DESC LIMIT 20",reference))if(visible(r))items.add(Map.of("public_id",r.get("public_id"),"width",r.get("width"),"height",r.get("height"),"published_at",iso(r.get("published_at"))));return Map.of("vehicle_id",vehicle,"experience_id",reference,"items",items);});}
 public byte[] bytes(VehicleOwner a,long vehicle,String publicId){ExperienceModeration.publicId(publicId);return db.reads.execute(t->{var model=experiences.recipient(a,vehicle);enabled();var r=db.one("SELECT * FROM public_image_application WHERE public_id=? AND model_id=?",publicId,model);if(!visible(r))throw missing();return (byte[])r.get("png");});}
}
