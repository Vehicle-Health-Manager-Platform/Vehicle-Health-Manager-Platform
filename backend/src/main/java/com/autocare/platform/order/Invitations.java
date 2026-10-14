package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.*;
import static com.autocare.platform.order.ReservationStore.*;

public class Invitations {
    private final ReservationStore db;
    private final boolean verifiedEnabled;
    public Invitations(ReservationStore db,boolean verifiedEnabled){this.db=db;this.verifiedEnabled=verifiedEnabled;}
    public Map<String,Object> code(VehicleOwner a){return db.reads.execute(tx->{db.owner(a,false);return codeView("user",a.id());});}
    public Map<String,Object> code(MerchantActor a){return db.reads.execute(tx->{db.merchant(a,false);checkShop(a.staffId(),a.merchantId());return codeView("staff_account",a.staffId());});}
    public JsonNode create(VehicleOwner a,String key,JsonNode body){return create(new Actor("user",a.id()),null,key,body,()->db.owner(a,true));}
    public JsonNode create(MerchantActor a,String key,JsonNode body){return create(new Actor("staff_account",a.staffId()),a.merchantId(),key,body,()->{db.merchant(a,true);checkShop(a.staffId(),a.merchantId());});}
    private void checkShop(long staff,long shop){if(!db.jdbc.queryForList("SELECT id FROM invite_code WHERE actor_type='staff_account' AND actor_id=? AND merchant_id<>?",staff,shop).isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"原推广码门店已变更，请联系管理员");}
    private JsonNode create(Actor a,Long shop,String key,JsonNode body,Runnable guard){
        String path=a.type().equals("user")?"/api/invite/code":"/api/merchant/invite/code";
        return db.writes.execute(a,"POST",path,key,body,guard,()->{
            var rows=db.jdbc.queryForList("SELECT id FROM invite_code WHERE actor_type=? AND actor_id=? FOR UPDATE",a.type(),a.id());
            boolean fresh=rows.isEmpty();
            long id=fresh?db.insert("INSERT INTO invite_code(code,actor_type,actor_id,merchant_id,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString().replace("-",""),a.type(),a.id(),shop,time(db.now())):number(rows.get(0),"id");
            var after=Map.<String,Object>of("available",true,"source_type",kind(a.type()));
            return new Change("INVITE_CODE_CREATE","invite_code",id,fresh?Map.of("available",false):after,after,codeView(a.type(),a.id()),fresh);
        });
    }
    private Map<String,Object> codeView(String type,long id){
        var rows=db.jdbc.queryForList("SELECT * FROM invite_code WHERE actor_type=? AND actor_id=?",type,id);
        var result=new LinkedHashMap<String,Object>();
        if(rows.isEmpty()) result.put("code",null);
        else {var r=rows.get(0);String code=r.get("code").toString();result.put("code",Map.of("value",code,"source_type",kind(type),"created_at",iso(r.get("created_at")),"landing_path","/pages/welfare/invite-entry?code="+code,"rule_version",InvitationRegistration.RULE));}
        return result;
    }
    public Map<String,Object> records(VehicleOwner a,int page){return db.reads.execute(tx->{db.owner(a,false);return records("user",a.id(),null,page);});}
    public Map<String,Object> records(MerchantActor a,int page){return db.reads.execute(tx->{db.merchant(a,false);return records("staff_account",a.staffId(),a.merchantId(),page);});}
    private Map<String,Object> records(String type,long id,Long shop,int page){
        String where=" FROM invite_registration WHERE source_type=? AND source_id=? AND attribution_result='ATTRIBUTED'"+(shop==null?"":" AND merchant_id=?");
        Object[] args=shop==null?new Object[]{type,id}:new Object[]{type,id,shop};
        long total=db.jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,args);
        var paged=new ArrayList<Object>(Arrays.asList(args));paged.add((page-1)*20L);
        var rows=db.jdbc.queryForList("SELECT *"+where+" ORDER BY registered_at DESC,user_id DESC LIMIT 20 OFFSET ?",paged.toArray());
        return page(rows.stream().map(this::record).toList(),total,page,20);
    }
    Map<String,Object> record(Map<String,Object> r){
        var n=new LinkedHashMap<String,Object>();
        n.put("record_id",r.get("record_id"));n.put("source_type",r.get("source_type")==null?null:kind(r.get("source_type").toString()));
        n.put("identity_source",r.get("identity_source"));n.put("attribution_result",r.get("attribution_result"));n.put("qualification_status",r.get("qualification_status"));n.put("qualification_reason",r.get("qualification_reason"));
        n.put("registered_at",iso(r.get("registered_at")));n.put("qualified_at",iso(r.get("qualified_at")));n.put("reward_month",r.get("reward_month"));n.put("rule_version",r.get("rule_version"));return n;
    }
    public Map<String,Object> registration(VehicleOwner a){return db.reads.execute(tx->{db.owner(a,false);var rows=db.jdbc.queryForList("SELECT * FROM invite_registration WHERE user_id=?",a.id());var n=new LinkedHashMap<String,Object>();n.put("registration",rows.isEmpty()?null:record(rows.get(0)));return n;});}
    public Map<String,Object> progress(VehicleOwner a){return db.reads.execute(tx->{db.owner(a,false);return progress("user",a.id(),null);});}
    public Map<String,Object> progress(MerchantActor a){return db.reads.execute(tx->{db.merchant(a,false);return progress("staff_account",a.staffId(),a.merchantId());});}
    private Map<String,Object> progress(String type,long id,Long shop){
        String month=db.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString().substring(0,7);
        String scope=" FROM invite_registration WHERE source_type=? AND source_id=?"+(shop==null?"":" AND merchant_id=?");
        var args=new ArrayList<Object>(List.of(type,id));if(shop!=null)args.add(shop);
        var monthly=new ArrayList<Object>(args);monthly.add(month);
        long count=db.jdbc.queryForObject("SELECT COUNT(*)"+scope+" AND qualification_status='QUALIFIED' AND reward_month=?",Long.class,monthly.toArray());
        var n=new LinkedHashMap<String,Object>(cycle(count));n.put("month",month);n.put("timezone","Asia/Shanghai");n.put("verified_identity_enabled",verifiedEnabled);n.put("rule_version",InvitationRegistration.RULE);
        for(String state:List.of("PENDING","UNKNOWN","TEST","REJECTED")){var selected=new ArrayList<Object>(args);selected.add(state);n.put(state.toLowerCase(Locale.ROOT)+"_total",db.jdbc.queryForObject("SELECT COUNT(*)"+scope+" AND qualification_status=?",Long.class,selected.toArray()));}
        return n;
    }
    public static Map<String,Object> cycle(long count){
        long within=count%10;int tier=within<1?1:within<3?2:within<6?3:4;
        int start=new int[]{0,0,1,3,6}[tier],end=new int[]{0,1,3,6,10}[tier];
        return Map.of("qualified_count",count,"completed_cycles",count/10,"tier",tier,"tier_count",within-start,"tier_target",tier,"next_threshold",count-within+end);
    }
    static String kind(String type){return "user".equals(type)?"OWNER":"STAFF";}
}
