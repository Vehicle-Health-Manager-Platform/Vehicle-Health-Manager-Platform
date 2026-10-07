package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Merchant-facing projection. Never serialize an owner order row directly. */
public class MerchantOrders {
    private static final Set<String> STATUSES=Set.of("PENDING_PAYMENT","PAID","CLOSED");
    private static final Instant MYSQL_FIRST=Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MYSQL_LAST=Instant.parse("9999-12-31T23:59:59Z");
    private static final Map<String,Set<String>> SNAPSHOT_FIELDS=Map.of(
        "project_snapshot",Set.of("standard_project_id","project_name","service_content"),
        "merchant_snapshot",Set.of("merchant_id","merchant_name","address"),
        "appointment_snapshot",Set.of("slot_id","starts_at","ends_at"),
        "price_snapshot",Set.of("merchant_project_id","quote_version_id","version","price"));
    private static final Set<String> NUMBER_FIELDS=Set.of("standard_project_id","merchant_id","slot_id","merchant_project_id","quote_version_id","version");
    private final ReservationStore db;
    public MerchantOrders(ReservationStore db){this.db=db;}

    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"本店订单筛选参数无效");}
    private static Instant[] window(String date){
        if(date==null)return null;
        if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();
        try{
            LocalDate day=LocalDate.parse(date);
            Instant from=day.atStartOfDay(ReservationInput.ZONE).toInstant();
            Instant until=day.plusDays(1).atStartOfDay(ReservationInput.ZONE).toInstant();
            if(from.isBefore(MYSQL_FIRST)||until.isAfter(MYSQL_LAST))throw bad();
            return new Instant[]{from,until};
        }catch(DateTimeException error){throw bad();}
    }
    public Map<String,Object> list(MerchantActor actor,String status,String date,int page,int size){
        com.autocare.platform.service.ServiceCatalog.validate(null,page,size);
        if(status!=null&&!STATUSES.contains(status))throw bad();
        Instant[] range=window(date);
        return db.reads.execute(tx->{
            db.merchant(actor,false);
            String where=" FROM `order` WHERE merchant_id=? AND is_deleted=0"+(status==null?"":" AND status=?")+(range==null?"":" AND appointment_at>=? AND appointment_at<?");
            var arguments=new ArrayList<Object>();arguments.add(actor.merchantId());
            if(status!=null)arguments.add(status);
            if(range!=null){arguments.add(Timestamp.from(range[0]));arguments.add(Timestamp.from(range[1]));}
            long total=db.jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,arguments.toArray());
            arguments.add(size);arguments.add((page-1)*size);
            var rows=db.jdbc.queryForList("SELECT *"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",arguments.toArray());
            Set<Long> exceptions=exceptions(rows.stream().map(row->ReservationStore.number(row,"id")).toList());
            return ReservationStore.page(rows.stream().map(row->view(row,false,exceptions.contains(ReservationStore.number(row,"id")))).toList(),total,page,size);
        });
    }
    public Map<String,Object> detail(MerchantActor actor,long id){
        com.autocare.platform.service.ServiceCatalog.validateId(id);
        return db.reads.execute(tx->{
            db.merchant(actor,false);
            var row=db.one("SELECT * FROM `order` WHERE id=? AND merchant_id=? AND is_deleted=0",id,actor.merchantId());
            return view(row,true,!exceptions(List.of(id)).isEmpty());
        });
    }
    private Set<Long> exceptions(List<Long> ids){
        if(ids.isEmpty())return Set.of();
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        return new HashSet<>(db.jdbc.queryForList("SELECT DISTINCT order_id FROM payment_exception WHERE order_id IN ("+marks+") AND is_deleted=0",Long.class,ids.toArray()));
    }
    private Map<String,Object> view(Map<String,Object> row,boolean detail,boolean hasException){
        var base=db.orderRow(row,false);
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("order_id","order_no","status","amount_due","created_at","expires_at","closed_at","close_reason","payment_summary"))result.put(key,base.get(key));
        for(String key:List.of("project_snapshot","merchant_snapshot","appointment_snapshot"))result.put(key,snapshot((JsonNode)base.get(key),key));
        if(detail)result.put("price_snapshot",snapshot(db.parse(row.get("price_snapshot")),"price_snapshot"));
        result.put("has_payment_exception",hasException);
        return result;
    }
    private static Map<String,Object> snapshot(JsonNode source,String key){
        if(source==null||source.isNull()||!source.isObject())return null;
        var safe=new LinkedHashMap<String,Object>();
        for(String field:SNAPSHOT_FIELDS.get(key)){
            JsonNode value=source.get(field);
            if(value==null)continue;
            if(NUMBER_FIELDS.contains(field)){if(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>0)safe.put(field,value.longValue());}
            else if(value.isTextual())safe.put(field,value.textValue());
        }
        return safe;
    }
}
