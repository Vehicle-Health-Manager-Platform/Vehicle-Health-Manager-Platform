package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 取车对比：把接车基线与完工证据放在一起，回答"这辆车能不能交付"。
 *
 * <p>只读投影，不写库、不改订单状态：交付本身仍由核销完成（核销带资金与免佣语义），
 * 本接口只输出对照结果与缺口清单，避免第二次写路径与核销争夺订单状态。
 *
 * <p>可交付（{@code deliverable}）要求：接车单已被车主确认、防护照、过程照、完工照、
 * 报工质检签字齐备（或显式声明无故障件）、且订单处于 {@code PENDING_VERIFY}。
 * 已完成的订单仍可回看，但 {@code deliverable=false}。
 */
public class DeliveryCompare {
    /** 接车检查未完成，没有可对照的基线。 */
    public static final int BASELINE_MISSING=43001;
    private static final Set<String> DELIVERABLE_STATUSES=Set.of(OrderStatus.PENDING_VERIFY);
    private final ReservationStore db;
    public DeliveryCompare(ReservationStore db){this.db=db;}

    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}

    /** 本单车主或本店员工可看；越权读一律 404，不暴露订单是否存在。 */
    private void authorize(Object actor,long id){
        if(actor instanceof MerchantActor shop){
            db.merchant(shop,false);
            db.one("SELECT id FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,shop.merchantId());
            return;
        }
        if(actor instanceof VehicleOwner owner){
            db.owner(owner,false);
            db.one("SELECT id FROM `order` WHERE id=? AND user_id=? AND is_deleted=0",id,owner.id());
            return;
        }
        throw error(HttpStatus.FORBIDDEN,"无权查看取车对比");
    }

    public Map<String,Object> compare(Object actor,long id){
        return db.reads.execute(transaction->{
            authorize(actor,id);
            var order=db.one("SELECT * FROM `order` WHERE id=? AND is_deleted=0",id);
            var checks=db.jdbc.queryForList("SELECT * FROM pickup_check WHERE order_id=? AND is_deleted=0",id);
            if(checks.isEmpty())throw new FulfillmentConflict(BASELINE_MISSING,"接车检查尚未完成，无法生成取车对比");
            var check=checks.get(0);
            long checkId=ReservationStore.number(check,"id");
            var baseline=new LinkedHashMap<String,Object>();
            var photos=new ArrayList<String>();
            for(var row:db.jdbc.queryForList("SELECT photo_slot FROM pickup_check_file WHERE pickup_check_id=? ORDER BY photo_slot",checkId))
                photos.add(String.valueOf(row.get("photo_slot")));
            var damages=db.parse(check.get("damage_marks"));
            long mileage=check.get("mileage")==null?0:ReservationStore.number(check,"mileage");
            var base=db.parse(check.get("mileage_baseline"));
            baseline.put("pickup_check_id",checkId);
            baseline.put("mileage",mileage);
            baseline.put("mileage_delta",mileage-base.path("mileage").asLong());
            baseline.put("fuel_level",check.get("fuel_level"));
            baseline.put("damage_status",check.get("damage_status"));
            baseline.put("damage_count",damages.isArray()?damages.size():0);
            baseline.put("photo_count",photos.size());
            baseline.put("photo_slots",photos);
            long ownerConfirm=check.get("owner_confirm")==null?0:ReservationStore.number(check,"owner_confirm");
            baseline.put("owner_confirm",ownerConfirm);
            baseline.put("confirm_at",ReservationStore.iso(check.get("confirm_at")));

            var work=new LinkedHashMap<String,Object>();
            var protections=db.jdbc.queryForList("SELECT id,items FROM repair_protection WHERE order_id=? AND is_deleted=0",id);
            var protection=new LinkedHashMap<String,Object>();
            protection.put("submitted",!protections.isEmpty());
            protection.put("item_count",protections.isEmpty()?0:size(db.parse(protections.get(0).get("items"))));
            protection.put("photo",!db.jdbc.queryForList("SELECT id FROM service_evidence_file WHERE order_id=? AND kind='PROTECTION'",id).isEmpty());
            work.put("protection",protection);

            var submissions=db.jdbc.queryForList("SELECT * FROM service_report_submission WHERE order_id=?",id);
            var report=new LinkedHashMap<String,Object>();
            report.put("submitted",!submissions.isEmpty());
            long reportId=submissions.isEmpty()?0:ReservationStore.number(submissions.get(0),"report_id");
            Map<String,Object> technician=reportId==0?null:db.jdbc.queryForList("SELECT * FROM technician_report WHERE id=? AND order_id=? AND is_deleted=0",reportId,id).stream().findFirst().orElse(null);
            report.put("status",technician==null?"MISSING":ReservationStore.number(technician,"status")==1?"SIGNED":"SUBMITTED");
            report.put("no_fault_parts",!submissions.isEmpty()&&ReservationStore.number(submissions.get(0),"no_fault_parts")==1);
            report.put("no_parts",!submissions.isEmpty()&&ReservationStore.number(submissions.get(0),"no_parts")==1);
            report.put("signed_at",technician==null?null:ReservationStore.iso(technician.get("signed_at")));
            var evidence=new LinkedHashMap<String,Long>();
            for(var row:db.jdbc.queryForList("SELECT kind,COUNT(*) AS total FROM service_evidence_file WHERE order_id=? GROUP BY kind",id))
                evidence.put(String.valueOf(row.get("kind")),ReservationStore.number(row,"total"));
            for(String kind:List.of("PROCESS","FAULT","FINISH","SIGNATURE"))report.put(kind.toLowerCase(Locale.ROOT)+"_photos",evidence.getOrDefault(kind,0L));
            work.put("report",report);

            var missing=new ArrayList<String>();
            if(!PickupInspection.ownerConfirmed(ownerConfirm))missing.add("OWNER_CONFIRM");
            if(!Boolean.TRUE.equals(protection.get("photo")))missing.add("PROTECTION_PHOTO");
            if(!Boolean.TRUE.equals(report.get("submitted")))missing.add("REPORT");
            if((Long)report.get("process_photos")==0)missing.add("PROCESS");
            if((Long)report.get("finish_photos")==0)missing.add("FINISH");
            if((Long)report.get("fault_photos")==0&&!Boolean.TRUE.equals(report.get("no_fault_parts")))missing.add("FAULT");
            if((Long)report.get("signature_photos")==0)missing.add("SIGNATURE");

            String orderStatus=String.valueOf(order.get("status"));
            var result=new LinkedHashMap<String,Object>();
            result.put("order_id",id);result.put("order_status",orderStatus);
            result.put("result",missing.isEmpty()?"READY":"INCOMPLETE");
            result.put("deliverable",missing.isEmpty()&&DELIVERABLE_STATUSES.contains(orderStatus));
            result.put("baseline",baseline);result.put("work",work);result.put("missing",missing);
            return result;
        });
    }

    private static long size(com.fasterxml.jackson.databind.JsonNode node){return node!=null&&node.isArray()?node.size():0;}
}
