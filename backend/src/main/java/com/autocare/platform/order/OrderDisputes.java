package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/**
 * 争议处理记录、车主复核与恢复。
 *
 * <p>商家只能追加处理记录，**不能**宣布争议解决；只有车主本人复核接受后，订单才回到争议前的
 * 状态。恢复条件见规格 §1（R1–R5），缺一项即拒绝，历史遗留的异议（没有争议单）也一律拒绝，
 * 不猜历史。
 *
 * <p>锁顺序与既有业务同向：商家 → 商家行 → 时段 → 订单 → 接车单 → 争议单 → 记录
 * （派工链路里的派工行被跳过，不构成反向等待）；车主复核为 车主 → 商家 → 时段 → 订单 → 接车单 → 争议单。
 */
public class OrderDisputes {
    /** 争议未解决。 */
    public static final String OPEN="OPEN";
    /** 车主已复核接受，订单已恢复。 */
    public static final String RESOLVED="RESOLVED";
    /** 商家提交处理记录。 */
    public static final String HANDLE="HANDLE";
    /** 车主复核接受。 */
    public static final String ACCEPT="ACCEPT";
    /** 车主复核不接受。 */
    public static final String REJECT="REJECT";
    /** 订单存在未解决的争议，不能派工或接单。 */
    public static final int DISPUTE_BLOCKS_ASSIGNMENT=43007;
    /** 商家尚未提交处理记录，暂不能复核。 */
    public static final int HANDLING_REQUIRED=43008;
    private final ReservationStore db;
    public OrderDisputes(ReservationStore db){this.db=db;}
    private static FulfillmentConflict conflict(String message){return new FulfillmentConflict(40905,message);}

    private Map<String,Object> shopOrder(MerchantActor actor,long id){
        var row=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());
        if(row.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",row.get("slot_id"));
        return db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,actor.merchantId());
    }
    private Map<String,Object> order(long id,long userId,boolean lock){
        String tail=lock?" FOR UPDATE":"";
        return db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0"+tail,id,userId);
    }
    private Map<String,Object> dispute(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM order_dispute WHERE order_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private Map<String,Object> sheet(long id,boolean lock){
        var rows=db.jdbc.queryForList("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static int ownerConfirm(Map<String,Object> sheet){return sheet==null||!(sheet.get("owner_confirm") instanceof Number value)?0:value.intValue();}
    private long handled(long dispute){return db.jdbc.queryForObject("SELECT COUNT(*) FROM order_dispute_record WHERE dispute_id=? AND action=?",Long.class,dispute,HANDLE);}

    /** 响应投影：只有动作、状态与计数，绝不投影任何 actor_id。 */
    private Map<String,Object> view(Map<String,Object> order,Map<String,Object> dispute,Object ownerConfirm,boolean changed){
        var actions=db.jdbc.queryForList("SELECT action FROM order_dispute_record WHERE dispute_id=? ORDER BY id",dispute.get("id"));
        var result=new LinkedHashMap<String,Object>();
        result.put("dispute_id",ReservationStore.number(dispute,"id"));
        result.put("order_id",ReservationStore.number(order,"id"));
        result.put("dispute_status",dispute.get("status"));
        result.put("order_status",order.get("status"));
        result.put("owner_confirm",ownerConfirm);
        result.put("record_count",actions.size());
        result.put("last_action",actions.isEmpty()?null:actions.get(actions.size()-1).get("action"));
        result.put("updated_at",ReservationStore.iso(dispute.get("updated_at")));
        result.put("changed",changed);
        return result;
    }
    private Map<String,Object> requireOpen(Map<String,Object> order,long id,boolean lock){
        var dispute=dispute(id,lock);
        if(dispute==null)throw conflict("当前订单没有争议记录（历史异议），暂不支持在线恢复，请联系运营处理");
        if(!OrderStatus.DISPUTED.equals(order.get("status"))||!OPEN.equals(dispute.get("status")))throw conflict("争议已解决或订单状态已变化，请刷新后重试");
        return dispute;
    }

    /** 商家提交一条处理记录；可以多次，每次追加，不覆盖历史。 */
    public JsonNode handle(MerchantActor actor,String key,long id,String note){
        String text=note==null?null:note.strip();
        if(text==null||text.isEmpty()||text.length()>500)throw ReservationInput.bad();
        var body=db.mapper.valueToTree(Map.of("order_id",id,"note",text));
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/orders/"+id+"/dispute/handle",key,body,
            ()->{db.merchant(actor,true);shopOrder(actor,id);sheet(id,true);dispute(id,true);},
            ()->append(actor,id,text));
    }
    private Change append(MerchantActor actor,long id,String note){
        var order=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
        var dispute=requireOpen(order,id,true);
        var current=sheet(id,true);
        var before=view(order,dispute,ownerConfirm(current),false);
        Instant now=db.now();
        long record=db.insert("INSERT INTO order_dispute_record(dispute_id,order_id,action,actor_type,actor_id,note,created_at) VALUES(?,?,?,'staff_account',?,?,?)",
            dispute.get("id"),id,HANDLE,actor.staffId(),note,ReservationStore.time(now));
        var after=view(order,dispute,ownerConfirm(current),true);
        return new Change("ORDER_DISPUTE_HANDLE","order_dispute_record",record,before,after,after);
    }

    /** 车主复核。{@code ACCEPT} 恢复订单；{@code REJECT} 保持争议并追加记录。 */
    public JsonNode review(VehicleOwner owner,String key,long id,String decision,String note){
        if(decision==null||!(ACCEPT.equals(decision)||REJECT.equals(decision)))throw ReservationInput.bad();
        String text=note==null?null:note.strip();
        if(text!=null&&(text.isEmpty()||text.length()>500))throw ReservationInput.bad();
        if(REJECT.equals(decision)&&text==null)throw ReservationInput.bad();
        var body=new LinkedHashMap<String,Object>();
        body.put("order_id",id);body.put("decision",decision);if(text!=null)body.put("note",text);
        return db.writes.execute(new Actor("user",owner.id()),"POST","/api/check/pickup/dispute/review",key,db.mapper.valueToTree(body),
            ()->{
                db.owner(owner,true);
                var row=db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());
                db.lockShop(ReservationStore.number(row,"merchant_id"));
                if(row.get("slot_id")!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",row.get("slot_id"));
                order(id,owner.id(),true);sheet(id,true);dispute(id,true);
            },
            ()->decide(owner,id,decision,text));
    }
    private Change decide(VehicleOwner owner,long id,String decision,String note){
        var order=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
        var dispute=requireOpen(order,id,true);
        var current=sheet(id,true);
        if(current==null||ownerConfirm(current)!=PickupInspection.OWNER_DISPUTED)throw conflict("接车单决定与争议状态不一致，请刷新后重试");
        // R4: 车主只有在拿到商家的处理说明之后才能复核，避免"什么都没说就恢复"。
        if(ACCEPT.equals(decision)&&handled(ReservationStore.number(dispute,"id"))==0L)throw new FulfillmentConflict(HANDLING_REQUIRED,"商家尚未提交处理记录，暂不能复核");
        String target=String.valueOf(dispute.get("from_status"));
        // R2: 恢复目标只能回到"本来能变成争议"的状态，历史异常数据一律拒绝。
        if(ACCEPT.equals(decision)&&!OrderStatus.canResume(OrderStatus.DISPUTED,target))throw conflict("争议前状态不可恢复，请联系运营核对历史数据");
        var before=view(order,dispute,current.get("owner_confirm"),false);
        Instant now=db.now();
        long record=db.insert("INSERT INTO order_dispute_record(dispute_id,order_id,action,actor_type,actor_id,note,created_at) VALUES(?,?,?,'user',?,?,?)",
            dispute.get("id"),id,decision,owner.id(),note,ReservationStore.time(now));
        if(REJECT.equals(decision)){
            var after=view(order,dispute,current.get("owner_confirm"),true);
            return new Change("ORDER_DISPUTE_REJECT","order_dispute_record",record,before,after,after);
        }
        db.jdbc.update("UPDATE order_dispute SET status=?,resolved_at=?,resolved_by=? WHERE id=? AND status='OPEN'",RESOLVED,ReservationStore.time(now),owner.id(),dispute.get("id"));
        db.jdbc.update("UPDATE `order` SET status=?,owner_confirmed_at=? WHERE id=? AND status='DISPUTED'",target,ReservationStore.time(now),id);
        db.jdbc.update("UPDATE pickup_check SET owner_confirm=? WHERE id=?",PickupInspection.OWNER_RESOLVED,current.get("id"));
        db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,note,occurred_at) VALUES(?,?,'DISPUTED',?,?,'user',?,?,?)",
            id,order.get("merchant_id"),target,OrderStatus.DISPUTE_RESOLVE,owner.id(),note,ReservationStore.time(now));
        var after=view(db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id),dispute(id,true),PickupInspection.OWNER_RESOLVED,true);
        return new Change(OrderStatus.DISPUTE_RESOLVE,"order",id,before,after,after);
    }
}
