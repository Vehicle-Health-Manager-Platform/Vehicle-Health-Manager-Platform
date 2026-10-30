package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.vehicle.VehicleOwner;
import com.autocare.platform.service.ServiceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ReservationOrders {
    final ReservationStore db;final ReservationExpiry expiry;
    public ReservationOrders(ReservationStore db,ReservationExpiry expiry){this.db=db;this.expiry=expiry;}
    public Map<String,Object> quote(VehicleOwner owner,long id){ServiceCatalog.validateId(id);return db.reads.execute(tx->{db.owner(owner,false);return db.quoteResult(db.quote(id,false));});}
    public JsonNode create(VehicleOwner owner,String key,ReservationInput.Booking input){
        return createWithCoupon(owner,key,input,null);
    }
    public JsonNode createCoupon(VehicleOwner owner,String key,CouponOrderInput.Booking input){
        return createWithCoupon(owner,key,input.booking(),input);
    }
    private JsonNode createWithCoupon(VehicleOwner owner,String key,ReservationInput.Booking input,CouponOrderInput.Booking coupon){
        var body=Map.of("merchant_project_id",input.quote(),"quote_version_id",input.version(),"vehicle_id",input.vehicle(),"slot_id",input.slot());
        return db.writes.execute(new Actor("user",owner.id()),"POST",coupon==null?"/api/order/create":"/api/order/coupon-create",key,coupon==null?db.mapper.valueToTree(body):coupon.body(),()->db.owner(owner,true),()->{
            // Availability is checked only for a new write; a cached creation
            // receipt remains replayable after a shop changes its offering.
            if(coupon!=null){var replay=CouponTransactions.creationReplay(db,owner.id(),key,coupon);if(replay!=null)return replay;}
            var q=db.quote(input.quote(),true);
            // Shop before vehicle agrees with pickup submission's lock order.
            db.one("SELECT id FROM vehicle WHERE id=? AND user_id=? AND is_deleted=0 FOR UPDATE",input.vehicle(),owner.id());
            if(ReservationStore.number(q,"version_id")!=input.version())throw new ReservationConflict(40901,"报价已更新，请重新确认");
            var slot=db.one("SELECT * FROM appointment_slot WHERE id=? AND merchant_id=? AND project_id=? AND is_deleted=0 FOR UPDATE",input.slot(),ReservationStore.number(q,"merchant_id"),ReservationStore.number(q,"project_id"));
            Instant now=db.now();if(ReservationStore.number(slot,"is_open")!=1 || !ReservationStore.instant(slot.get("starts_at")).isAfter(now))throw new ReservationConflict(40902,"时段已关闭或开始，请重新选择");
            // Expired holds are closed first, so the counter read below already excludes them.
            int expired=expiry.expireSlot(input.slot(),now);
            long booked=ReservationStore.number(slot,"reserved_count")-expired;
            if(booked>=ReservationStore.number(slot,"capacity"))throw new ReservationConflict(40903,"预约名额不足，请重新选择");
            Instant start=ReservationStore.instant(slot.get("starts_at")),deadline=now.plusSeconds(900);if(start.isBefore(deadline))deadline=start;
            var selected=coupon==null?null:CouponTransactions.prepare(db,owner.id(),coupon,q);
            if(selected!=null&&selected.validTo().isBefore(deadline))deadline=selected.validTo();
            var cash=selected==null?q.get("price"):selected.cash();
            String orderNo="R"+UUID.randomUUID().toString().replace("-","").substring(0,30);
            var project=Map.of("standard_project_id",q.get("project_id"),"project_name",q.get("project_name"),"service_content",q.get("service_content"));
            var merchant=Map.of("merchant_id",q.get("merchant_id"),"merchant_name",q.get("name"),"address",q.get("address"));
            var price=Map.of("merchant_project_id",input.quote(),"quote_version_id",input.version(),"version",q.get("version"),"price",q.get("price").toString());
            var appointment=Map.of("slot_id",input.slot(),"starts_at",start.toString(),"ends_at",ReservationStore.iso(slot.get("ends_at")));
            long id=db.insert("INSERT INTO `order`(order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status,appointment_at,project_snapshot,merchant_snapshot,price_snapshot,slot_id,expires_at,merchant_project_id,quote_version_id,appointment_snapshot,created_at,verify_code) VALUES(?,?,?,?,?,?,?,'PENDING_PAYMENT',?,?,?,?,?,?,?,?,?,?,?)",orderNo,owner.id(),input.vehicle(),q.get("merchant_id"),q.get("project_id"),q.get("price"),cash,ReservationStore.time(start),db.json(project),db.json(merchant),db.json(price),input.slot(),ReservationStore.time(deadline),input.quote(),input.version(),db.json(appointment),ReservationStore.time(now),AppointmentCodes.create());
            if(selected!=null)CouponTransactions.reserve(db,id,owner.id(),selected,deadline,key,coupon);
            // The commission step already reads and locks the newly persisted row after
            // coupon reservation. Reuse it for audit; the slot update does not change it.
            var persisted=CommissionLedger.booking(db,id);
            db.jdbc.update("UPDATE appointment_slot SET reserved_count=reserved_count+1 WHERE id=?",input.slot());
            var result=Map.<String,Object>of("order_id",id,"order_no",orderNo,"amount_due",cash.toString(),"status","PENDING_PAYMENT","expires_at",deadline.toString());return new Change("ORDER_CREATE","order",id,Map.of(),db.orderRow(persisted,true),result);
        });
    }
    public Map<String,Object> list(VehicleOwner owner,String status,int page,int size){
        ServiceCatalog.validate(null,page,size);if(status!=null && !OrderStatus.known(status))throw ReservationInput.bad();
        return db.reads.execute(tx->{db.owner(owner,false);String filter=" FROM `order` WHERE user_id=? AND is_deleted=0"+(status==null?"":" AND status=?");var args=new ArrayList<Object>();args.add(owner.id());if(status!=null)args.add(status);
            long count=db.jdbc.queryForObject("SELECT COUNT(*)"+filter,Long.class,args.toArray());args.add(size);args.add((page-1)*size);var rows=db.jdbc.queryForList("SELECT *"+filter+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray());return ReservationStore.page(rows.stream().map(row->db.orderRow(row,false)).toList(),count,page,size);
        });
    }
    public Map<String,Object> detail(VehicleOwner owner,long id){ServiceCatalog.validateId(id);return db.transactions.execute(tx->{
        db.owner(owner,true);var row=db.one("SELECT * FROM `order` WHERE id=? AND user_id=? AND is_deleted=0 FOR UPDATE",id,owner.id());
        var result=db.orderRow(row,true);
        if(OrderStatus.PAID.equals(row.get("status"))){
            String code=(String)row.get("verify_code");if(code==null||!code.matches("[0-9]{6}")){code=AppointmentCodes.create();db.jdbc.update("UPDATE `order` SET verify_code=? WHERE id=?",code,id);}
            result.put("appointment_code",code);
        }else if(OrderStatus.PENDING_VERIFY.equals(row.get("status")) && row.get("verify_code") instanceof String code && code.matches("[0-9]{6}")){
            result.put("appointment_code",code);
        }
        return result;
    });}
    public JsonNode cancel(VehicleOwner owner,String key,long id){
        var changed=new boolean[]{false};
        return db.writes.execute(new Actor("user",owner.id()),"POST","/api/order/cancel",key,db.mapper.valueToTree(Map.of("order_id",id)),()->{
            db.owner(owner,true);var row=db.one("SELECT merchant_id,slot_id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());db.lockShop(ReservationStore.number(row,"merchant_id"));db.one("SELECT id FROM appointment_slot WHERE id=? FOR UPDATE",row.get("slot_id"));db.one("SELECT id FROM `order` WHERE id=? AND user_id=? FOR UPDATE",id,owner.id());
        },()->{
            var row=db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id);var before=db.orderRow(row,true);String status=row.get("status").toString();
            if(!Set.of("PENDING_PAYMENT","CLOSED").contains(status))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"此订单状态不可取消");
            if(status.equals("PENDING_PAYMENT")){Instant now=db.now();String reason=row.get("expires_at")!=null && !ReservationStore.instant(row.get("expires_at")).isAfter(now)?"PAYMENT_EXPIRED":"OWNER_CANCELLED";expiry.closeOrder(id,ReservationStore.number(row,"slot_id"),reason,now);changed[0]=true;}
            var result=db.orderRow(db.one("SELECT * FROM `order` WHERE id=? FOR UPDATE",id),true);
            // The integrity service records a successful request even for a
            // no-op, but it is not a second state-transition audit.
            return new Change(changed[0]?"ORDER_CLOSE":"ORDER_CANCEL_REPLAY","order",id,before,result,result);
        });
    }
}
