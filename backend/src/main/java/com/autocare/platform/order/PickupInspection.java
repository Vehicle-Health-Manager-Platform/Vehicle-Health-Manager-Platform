package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.file.*;
import com.autocare.platform.gateway.identity.AuthTokens;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Immutable pickup evidence and its PAID -> RECEIVED transition commit together. */
public class PickupInspection {
    /** 车主尚未决定。 */
    public static final int OWNER_UNDECIDED=0;
    /** 车主已确认接车单。 */
    public static final int OWNER_CONFIRMED=1;
    /** 车主已提出异议，订单进入 DISPUTED。 */
    public static final int OWNER_DISPUTED=2;
    /** 异议经车主复核接受商家处理，订单已恢复；见争议处理规格 E3。 */
    public static final int OWNER_RESOLVED=3;
    private final ReservationStore db;
    public PickupInspection(ReservationStore db){this.db=db;}
    /**
     * 接车确认是否成立：正常确认（1）与争议解决后的恢复（3）都算。
     * 派工/接单前置只问"车主是否已同意继续"，不问同意是哪条路径来的。
     */
    public static boolean ownerConfirmed(Object value){return value instanceof Number number && (number.intValue()==OWNER_CONFIRMED || number.intValue()==OWNER_RESOLVED);}
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private Map<String,Object> shopOrder(MerchantActor actor,long id,boolean lock){
        db.merchant(actor,lock);
        var row=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());
        if(lock){if(row.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",row.get("slot_id"));row=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,actor.merchantId());}
        return row;
    }
    private Map<String,Object> baseline(Map<String,Object> order,boolean lock){
        var vehicle=db.one("SELECT current_mileage FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),order.get("vehicle_id"),order.get("user_id"));
        var archives=db.jdbc.queryForList("SELECT id,content FROM vehicle_archive WHERE vehicle_id=? AND is_deleted=0 AND input_type IN (1,3) AND JSON_TYPE(JSON_EXTRACT(content,'$.mileage'))='INTEGER' ORDER BY recorded_at DESC,id DESC LIMIT 1",order.get("vehicle_id"));
        if(!archives.isEmpty()){var a=archives.get(0);return Map.of("source","ARCHIVE","archive_id",a.get("id"),"mileage",db.parse(a.get("content")).path("mileage").intValue());}
        return Map.of("source","VEHICLE","mileage",vehicle.get("current_mileage")==null?0:vehicle.get("current_mileage"));
    }
    public Map<String,Object> context(MerchantActor actor,long id){return db.reads.execute(tx->{var o=shopOrder(actor,id,false);if(!OrderStatus.PAID.equals(o.get("status")))throw error(HttpStatus.CONFLICT,"当前订单不可接车");return Map.of("order_id",id,"order",db.orderRow(o,true),"mileage_baseline",baseline(o,false));});}
    private void verifyCode(MerchantActor actor,PickupInput input){
        long start=Math.floorDiv(db.now().getEpochSecond(),600)*600;
        String hash=AuthTokens.sha256(actor.staffId()+":"+input.orderId());
        int outcome=db.transactions.execute(tx->{
            var o=shopOrder(actor,input.orderId(),true);
            var window=ReservationStore.time(Instant.ofEpochSecond(start));
            db.jdbc.update("INSERT IGNORE INTO auth_rate_limit(scope,key_hash,window_start,attempts) VALUES ('pickup_code',?,?,0)",hash,window);
            int attempts=db.jdbc.queryForObject("SELECT attempts FROM auth_rate_limit WHERE scope='pickup_code' AND key_hash=? AND window_start=? FOR UPDATE",Integer.class,hash,window);
            if(attempts>=5)return 429;
            if(!input.code().equals(o.get("verify_code"))){db.jdbc.update("UPDATE auth_rate_limit SET attempts=attempts+1 WHERE scope='pickup_code' AND key_hash=? AND window_start=?",hash,window);return 422;}
            return 0;
        });
        if(outcome==429)throw new UploadHttpException(429,"预约码校验过于频繁，请稍后重试",Math.max(1,(int)(start+600-db.now().getEpochSecond())));
        if(outcome!=0)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"预约码无效");
    }
    public JsonNode submit(MerchantActor actor,String key,PickupInput input){
        com.autocare.platform.common.write.WriteIntegrityService.normalizeKey(key);
        verifyCode(actor,input);
        var body=db.mapper.valueToTree(input); // Replace the only credential before hashing/caching.
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("code",AuthTokens.sha256(input.code()));
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/check/pickup/submit",key,body,
            ()->{var o=shopOrder(actor,input.orderId(),true);if(!input.code().equals(o.get("verify_code")))throw error(HttpStatus.UNPROCESSABLE_ENTITY,"预约码无效");},
            ()->save(actor,input));
    }
    private Change save(MerchantActor actor,PickupInput input){
        long id=input.orderId();var o=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
        if(!OrderStatus.can(String.valueOf(o.get("status")),OrderStatus.RECEIVED))throw error(HttpStatus.CONFLICT,"当前订单不可接车，已提交的接车单不可修改");
        var base=baseline(o,true);long prior=((Number)base.get("mileage")).longValue();
        if(input.mileage()<prior&&input.mileageReason()==null)throw error(HttpStatus.BAD_REQUEST,"里程低于比较基线，请填写原因");
        Instant now=db.now();var appointment=db.parse(o.get("appointment_snapshot"));
        if(appointment.hasNonNull("starts_at")&&Math.abs(Duration.between(Instant.parse(appointment.get("starts_at").asText()),now).getSeconds())>7200&&input.arrivalReason()==null)throw error(HttpStatus.BAD_REQUEST,"到店时间偏差超过两小时，请填写原因");
        for(long file:input.photos().values().stream().sorted().toList()){
            var files=db.jdbc.queryForList("SELECT id FROM file_object WHERE id=? AND owner_type='staff_account' AND owner_id=? AND scan_status='CLEAN' AND is_deleted=0 FOR UPDATE",file,actor.staffId());
            if(files.isEmpty()||db.jdbc.queryForObject("SELECT COUNT(*) FROM pickup_check_file WHERE file_id=?",Integer.class,file)>0)throw error(HttpStatus.UNPROCESSABLE_ENTITY,"图片非本人安全文件或已用于接车单");
        }
        var around=new LinkedHashMap<String,Long>();for(String slot:PickupInput.SLOTS.subList(0,5))around.put(slot,input.photos().get(slot));
        long check=db.insert("INSERT INTO pickup_check(order_id,merchant_id,staff_id,around_photos,dashboard_photo_id,mileage,mileage_source,mileage_baseline,mileage_reason,fuel_level,interior_photos,damage_status,damage_marks,arrived_at,arrival_reason,owner_confirm) VALUES(?,?,?,?,?,?,'MANUAL',?,?,?,?,?,?,?,?,0)",
            id,actor.merchantId(),actor.staffId(),db.json(around),input.photos().get("DASHBOARD"),input.mileage(),db.json(base),input.mileageReason(),input.fuel(),db.json(List.of(input.photos().get("INTERIOR"))),input.damageStatus(),db.json(input.damages()),ReservationStore.time(now),input.arrivalReason());
        for(String slot:PickupInput.SLOTS)db.jdbc.update("INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES (?,?,?)",check,input.photos().get(slot),slot);
        db.jdbc.update("UPDATE `order` SET status='RECEIVED',check_in_completed_at=? WHERE id=? AND status='PAID'",ReservationStore.time(now),id);
        db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES(?,?,'PAID','RECEIVED','ORDER_CHECK_IN','staff_account',?,?)",id,actor.merchantId(),actor.staffId(),ReservationStore.time(now));
        var result=projection(db.one("SELECT * FROM pickup_check WHERE id=?",check));
        return new Change("ORDER_CHECK_IN","order",id,db.orderRow(o,true),result,result);
    }
    private void authorize(Object actor,long id){
        if(actor instanceof MerchantActor shop){shopOrder(shop,id,false);return;}
        if(actor instanceof VehicleOwner owner){db.owner(owner,false);db.one("SELECT id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());return;}
        throw error(HttpStatus.FORBIDDEN,"无权查看接车单");
    }
    public Map<String,Object> detail(Object actor,long id){return db.reads.execute(tx->{authorize(actor,id);return projection(db.one("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0",id));});}
    public JsonNode decide(VehicleOwner owner,String key,long id,String decision,String reason){
        if(decision==null||!Set.of("CONFIRM","DISPUTE").contains(decision))throw error(HttpStatus.BAD_REQUEST,"接车单操作无效");
        String note=reason==null?null:reason.strip();
        if(("DISPUTE".equals(decision)&&(note==null||note.isEmpty())) || (note!=null&&(note.isEmpty()||note.length()>500)) || ("CONFIRM".equals(decision)&&note!=null))
            throw error(HttpStatus.BAD_REQUEST,"异议原因须为 1–500 字；确认时不填写原因");
        var body=note==null?Map.of("order_id",id,"decision",decision):Map.of("order_id",id,"decision",decision,"reason",note);
        return db.writes.execute(new Actor("user",owner.id()),"POST","/api/check/pickup/confirm",key,db.mapper.valueToTree(body),()->{
            db.owner(owner,true);
            var row=db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());
            db.lockShop(ReservationStore.number(row,"merchant_id"));
            if(row.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",row.get("slot_id"));
            db.one("SELECT id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0 FOR UPDATE",id,owner.id());
        },()->{
            var order=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
            var sheet=db.one("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0 FOR UPDATE",id);
            if(!OrderStatus.RECEIVED.equals(order.get("status")) || ((Number)sheet.get("owner_confirm")).intValue()!=0 || order.get("check_in_completed_at")==null)
                throw new FulfillmentConflict(40905,"当前接车单不可确认或提出异议，请刷新后查看");
            Instant now=db.now();boolean disputed="DISPUTE".equals(decision);
            String target=disputed?OrderStatus.DISPUTED:OrderStatus.RECEIVED;
            String action=disputed?"ORDER_PICKUP_DISPUTE":"ORDER_PICKUP_CONFIRM";
            var before=projection(sheet);
            db.jdbc.update("UPDATE pickup_check SET owner_confirm=?,confirm_at=?,dispute_reason=? WHERE id=?",disputed?2:1,ReservationStore.time(now),note,sheet.get("id"));
            if(disputed){
                if(!OrderStatus.can(OrderStatus.RECEIVED,target))throw new FulfillmentConflict(40905,"当前订单不可提出异议");
                db.jdbc.update("UPDATE `order` SET status='DISPUTED' WHERE id=? AND status='RECEIVED'",id);
                db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,note,occurred_at) VALUES(?,?,'RECEIVED','DISPUTED',?,'user',?,?,?)",id,order.get("merchant_id"),action,owner.id(),note,ReservationStore.time(now));
                // 争议单在此建立，A5.6 的商家处理与车主复核才有据可依。本阶段之前的历史异议
                // 没有争议单，恢复一律拒绝（fail-closed），由人工处理，不猜历史。
                db.jdbc.update("INSERT INTO order_dispute(order_id,merchant_id,pickup_check_id,reason,from_status,status,opened_by,opened_at) VALUES(?,?,?,?,?,'OPEN',?,?)",
                    id,order.get("merchant_id"),sheet.get("id"),note,String.valueOf(order.get("status")),owner.id(),ReservationStore.time(now));
            }else db.jdbc.update("UPDATE `order` SET owner_confirmed_at=? WHERE id=? AND status='RECEIVED'",ReservationStore.time(now),id);
            var after=projection(db.one("SELECT * FROM pickup_check WHERE id=?",sheet.get("id")));
            return new Change(action,"pickup_check",ReservationStore.number(sheet,"id"),before,after,after);
        });
    }
    private Map<String,Object> projection(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();long check=ReservationStore.number(row,"id");
        String status=String.valueOf(db.one("SELECT status FROM `order` WHERE id=?",row.get("order_id")).get("status"));
        result.put("pickup_check_id",check);result.put("order_id",row.get("order_id"));result.put("status",status);result.put("owner_confirm",row.get("owner_confirm"));result.put("confirm_at",ReservationStore.iso(row.get("confirm_at")));result.put("dispute_reason",row.get("dispute_reason"));result.put("arrived_at",ReservationStore.iso(row.get("arrived_at")));
        result.put("mileage",row.get("mileage"));result.put("mileage_source",row.get("mileage_source"));var base=db.parse(row.get("mileage_baseline"));result.put("mileage_baseline",base);result.put("mileage_delta",((Number)row.get("mileage")).longValue()-base.path("mileage").asLong());
        result.put("mileage_reason",row.get("mileage_reason"));result.put("arrival_reason",row.get("arrival_reason"));result.put("fuel_level",row.get("fuel_level"));result.put("damage_status",row.get("damage_status"));result.put("damages",db.parse(row.get("damage_marks")));
        var photos=new LinkedHashMap<String,Object>();for(var file:db.jdbc.queryForList("SELECT photo_slot,file_id FROM pickup_check_file WHERE pickup_check_id=?",check))photos.put(file.get("photo_slot").toString(),file.get("file_id"));result.put("photos",photos);
        result.put("dispute",disputeView(ReservationStore.number(row,"order_id"),status));return result;
    }
    /**
     * 争议时间线。只投影动作、说明与时间；车主与商家员工的身份 ID 一律不出现在响应里，
     * 过滤只保证 HTTP 响应干净，落库载荷由安全测试单独核对。
     */
    private Map<String,Object> disputeView(long order,String orderStatus){
        var rows=db.jdbc.queryForList("SELECT * FROM order_dispute WHERE order_id=? AND is_deleted=0",order);
        if(rows.isEmpty())return null;
        var dispute=rows.get(0);long id=ReservationStore.number(dispute,"id");
        var records=new ArrayList<Map<String,Object>>();boolean handled=false;
        for(var record:db.jdbc.queryForList("SELECT action,note,created_at FROM order_dispute_record WHERE dispute_id=? ORDER BY id",id)){
            handled|=OrderDisputes.HANDLE.equals(record.get("action"));
            var item=new LinkedHashMap<String,Object>();item.put("action",record.get("action"));item.put("note",record.get("note"));item.put("created_at",ReservationStore.iso(record.get("created_at")));records.add(item);
        }
        var view=new LinkedHashMap<String,Object>();
        view.put("dispute_id",id);view.put("status",dispute.get("status"));view.put("reason",dispute.get("reason"));view.put("from_status",dispute.get("from_status"));
        view.put("opened_at",ReservationStore.iso(dispute.get("opened_at")));view.put("resolved_at",ReservationStore.iso(dispute.get("resolved_at")));
        view.put("records",records);
        // 只影响按钮显示；能否复核始终由写接口在事务内重新判定。
        view.put("can_review",OrderDisputes.OPEN.equals(dispute.get("status")) && OrderStatus.DISPUTED.equals(orderStatus) && handled);
        return view;
    }
    public FileMetadataRepository.Actor fileOwner(Object actor,long order,long file){return db.reads.execute(tx->{authorize(actor,order);
        var row=db.one("SELECT f.owner_type,f.owner_id FROM pickup_check p JOIN pickup_check_file r ON r.pickup_check_id=p.id JOIN file_object f ON f.id=r.file_id WHERE p.order_id=? AND p.is_deleted=0 AND f.id=? AND f.is_deleted=0 AND f.scan_status='CLEAN'",order,file);
        return new FileMetadataRepository.Actor(row.get("owner_type").toString(),ReservationStore.number(row,"owner_id"));});}
}
