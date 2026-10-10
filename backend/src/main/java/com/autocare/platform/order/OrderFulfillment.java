package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.Actor;
import com.autocare.platform.common.write.WriteIntegrityService.Change;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/**
 * 商家通用履约动作服务；接车、车主决定与技师接单由各自业务服务处理。
 *
 * <p>一次迁移同时完成：矩阵判定、前置校验、行锁串行化、幂等去重，以及与业务同事务的
 * {@code order_status_transition} 审计。各业务服务共享 {@code OrderStatus} 矩阵与商家→时段→订单锁顺序。
 *
 * <p>前置条件用 {@code order} 表的履约时间列表达，由各自阶段写入：
 * 接车检查（A3）写 {@code check_in_completed_at}，车主确认（A4）写 {@code owner_confirmed_at}，
 * 派工（A5）写 {@code assigned_at}，报工（A6）写 {@code service_report_ready_at}。
 * 校验采用 fail-closed：前置条件缺失即拒绝，未接入校验的动作同样拒绝。
 */
public class OrderFulfillment {
    /** 接车检查未完成（Spec §8.2 已定义）。 */
    static final int CHECK_IN_REQUIRED=43001;
    /** 车主尚未确认接车；Spec §2.4 禁止在未确认时派工。 */
    static final int OWNER_CONFIRMATION_REQUIRED=43003;
    /** 尚未派工。 */
    static final int ASSIGNMENT_REQUIRED=43004;
    /** 施工报工未完成。 */
    static final int REPORT_REQUIRED=43005;
    /** 该动作的前置校验尚未接入；分阶段实现期间的显式拒绝，不是永久错误码。 */
    static final int GUARD_UNAVAILABLE=43006;
    /** 当前状态不允许该动作。 */
    static final int ILLEGAL_TRANSITION=40905;
    private final ReservationStore db;
    private final MerchantOrders orders;
    public OrderFulfillment(ReservationStore db,MerchantOrders orders){this.db=db;this.orders=orders;}

    /** 执行一次动作。{@code note} 可选，去除首尾空白后不超过 200 字。 */
    JsonNode apply(MerchantActor actor,String key,long id,String action,String note){
        ServiceCatalog.validateId(id);
        if(!OrderStatus.action(action))throw ReservationInput.bad();
        if(OrderStatus.COMPLETE.equals(action))throw new FulfillmentConflict(GUARD_UNAVAILABLE,"请通过核销验码入口完成订单");
        if(OrderStatus.FINISH_SERVICE.equals(action))throw new FulfillmentConflict(REPORT_REQUIRED,"请由被派工技师本人提交完整报工并质检签字");
        String text=note==null?null:note.strip();
        if(text!=null && (text.isEmpty() || text.length()>200))throw ReservationInput.bad();
        var body=db.mapper.valueToTree(text==null
            ?Map.of("order_id",id,"action",action)
            :Map.of("order_id",id,"action",action,"note",text));
        return db.writes.execute(new Actor("staff_account",actor.staffId()),"POST","/api/merchant/orders/"+id+"/actions",key,body,
            ()->lock(actor,id),()->change(actor,id,action,text));
    }

    // Lock order follows the expiry sweep: merchant, then slot, then order.
    private void lock(MerchantActor actor,long id){
        db.merchant(actor,true);
        var row=db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());
        db.lockShop(ReservationStore.number(row,"merchant_id"));
        Object slot=row.get("slot_id");
        if(slot!=null)db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",slot);
        db.one("SELECT id FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",id,actor.merchantId());
    }

    private Change change(MerchantActor actor,long id,String action,String note){
        var row=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);
        String from=String.valueOf(row.get("status"));
        String target=OrderStatus.target(action);
        // A repeat of an already applied action is not an error: return the
        // current projection with changed=false instead of a conflict.
        if(from.equals(target)){
            var current=orders.projection(row,true);
            return new Change("ORDER_ACTION_REPLAY","order",id,current,current,result(current,action,from,false),false);
        }
        if(!OrderStatus.can(from,target))throw new FulfillmentConflict(ILLEGAL_TRANSITION,"当前订单状态不支持该操作");
        guard(row,action);
        Instant now=db.now();
        var before=orders.projection(row,true);
        db.jdbc.update("UPDATE `order` SET status=? WHERE id=? AND status=?",target,id,from);
        db.jdbc.update("INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,note,occurred_at)"
            +" VALUES(?,?,?,?,?,?,?,?,?)",id,actor.merchantId(),from,target,OrderStatus.audit(action),"staff_account",actor.staffId(),note,ReservationStore.time(now));
        var after=orders.projection(db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id),true);
        return new Change(OrderStatus.audit(action),"order",id,before,after,result(after,action,from,true));
    }

    private static Map<String,Object> result(Map<String,Object> projection,String action,String from,boolean changed){
        var result=new LinkedHashMap<>(projection);
        result.put("action",action);
        result.put("from_status",from);
        result.put("changed",changed);
        return result;
    }

    private static void guard(Map<String,Object> row,String action){
        switch(action){
            case OrderStatus.RECEIVE -> required(row,"check_in_completed_at",CHECK_IN_REQUIRED,"接车检查未完成，请先完成接车检查");
            case OrderStatus.START_SERVICE -> {
                required(row,"owner_confirmed_at",OWNER_CONFIRMATION_REQUIRED,"车主尚未确认接车，不能开始施工");
                required(row,"assigned_at",ASSIGNMENT_REQUIRED,"尚未派工，不能开始施工");
            }
            case OrderStatus.FINISH_SERVICE -> required(row,"service_report_ready_at",REPORT_REQUIRED,"施工报工未完成，不能送核销");
            default -> throw new FulfillmentConflict(GUARD_UNAVAILABLE,"核销校验尚未接入，暂不能完成订单");
        }
    }

    private static void required(Map<String,Object> row,String column,int code,String message){
        if(row.get(column)==null)throw new FulfillmentConflict(code,message);
    }
}
