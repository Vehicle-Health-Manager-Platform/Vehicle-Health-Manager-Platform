package com.autocare.platform.order;

import com.autocare.platform.service.MerchantActor;
import com.autocare.platform.vehicle.VehicleOwner;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import static com.autocare.platform.order.ReservationStore.*;

/** Durable event rewards. Reads never issue; every pair commits with stock and audit. */
public class InvitationRewards {
    private final ReservationStore db;
    private final CouponInventory coupons;
    private final boolean enabled;
    public InvitationRewards(ReservationStore db,CouponInventory coupons,boolean enabled){this.db=db;this.coupons=coupons;this.enabled=enabled;}

    /** Called inside the qualification transaction, never in a separate transaction. */
    void freeze(long invitee){
        var r=db.one("SELECT * FROM invite_registration WHERE user_id=?",invitee);
        if(!"QUALIFIED".equals(r.get("qualification_status")) || !"VERIFIED_WECHAT".equals(r.get("identity_source")) || !"ATTRIBUTED".equals(r.get("attribution_result")))return;
        // Lock only the policy here: no coupon/stock locks while phone qualification holds users.
        // A concurrent stop/publish is ordered by this lock; the saved policy never changes.
        var policies=db.jdbc.queryForList("SELECT * FROM invite_reward_policy WHERE scope=? AND status='PUBLISHED' AND published_at<=? AND valid_from<=? AND valid_to>? FOR SHARE",Invitations.kind(r.get("source_type").toString()),r.get("qualified_at"),r.get("qualified_at"),r.get("qualified_at"));
        var p=policies.isEmpty()?null:policies.get(0);
        long id=db.insert("INSERT INTO invite_reward_batch(record_id,invitee_id,source_type,source_id,merchant_id,policy_id,policy_revision,qualified_at,rule_version,state,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",r.get("record_id"),invitee,r.get("source_type"),r.get("source_id"),r.get("merchant_id"),p==null?null:p.get("id"),p==null?null:p.get("revision"),r.get("qualified_at"),"R6B_REWARD_V1",p==null?"UNKNOWN_POLICY":"READY",p==null?null:time(db.now()));
        if(p!=null){
            if("user".equals(r.get("source_type")))item(id,"INVITER",number(r,"source_id"),p,"inviter");
            item(id,"INVITEE",invitee,p,"invitee");
        }
        audit(id,"INVITE_REWARD_FREEZE",p==null?"UNKNOWN_POLICY":"READY");
    }
    private void item(long batch,String role,long user,Map<String,Object> p,String side){db.jdbc.update("INSERT INTO invite_reward_item(batch_id,role,user_id,coupon_id,publication_revision,terms_json) VALUES(?,?,?,?,?,?)",batch,role,user,p.get(side+"_coupon_id"),p.get(side+"_publication_revision"),p.get(side+"_terms_json").toString());}

    @Scheduled(fixedDelayString="${INVITE_REWARD_SWEEP_MS:60000}")
    public void sweep(){
        if(!enabled)return;
        var rows=db.jdbc.queryForList("SELECT id FROM invite_reward_batch WHERE state IN ('READY','WAIT_STOCK','WAIT_SOURCE','WAIT_ACCOUNT') AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 100",time(db.now()));
        for(var r:rows){long id=number(r,"id");try{process(id);}catch(org.springframework.dao.DataAccessException | ResponseStatusException e){
            // The failed issuance transaction is fully rolled back. Persist a bounded retry,
            // with no raw identity/provider/database details in the task or public response.
            db.transactions.executeWithoutResult(t->db.jdbc.update("UPDATE invite_reward_batch SET attempts=attempts+1,next_attempt_at=? WHERE id=? AND state IN ('READY','WAIT_STOCK','WAIT_SOURCE','WAIT_ACCOUNT')",time(db.now().plusSeconds(300)),id));
        }}
    }
    public void process(long id){
        db.transactions.executeWithoutResult(tx->{
            var b=db.one("SELECT * FROM invite_reward_batch WHERE id=? FOR UPDATE",id);
            if(!Set.of("READY","WAIT_STOCK","WAIT_SOURCE","WAIT_ACCOUNT").contains(b.get("state")))return;
            var items=db.jdbc.queryForList("SELECT * FROM invite_reward_item WHERE batch_id=? ORDER BY user_id,id",id);
            var registrations=db.jdbc.queryForList("SELECT * FROM invite_registration WHERE user_id=?",b.get("invitee_id"));
            if(registrations.isEmpty() || !evidence(b,registrations.get(0),items)){state(id,"INVALID_EVIDENCE");return;}
            // NOWAIT prevents a reward worker from waiting in reverse order against phone
            // registration or merchant writes. The durable sweep retries the whole pair.
            var users=new TreeSet<Long>();for(var i:items)users.add(number(i,"user_id"));if("user".equals(b.get("source_type")))users.add(number(b,"source_id"));
            for(long user:users){var rows=db.jdbc.queryForList("SELECT id,status,is_deleted FROM `user` WHERE id=? FOR UPDATE NOWAIT",user);if(rows.isEmpty()||number(rows.get(0),"status")!=1||number(rows.get(0),"is_deleted")!=0){state(id,"WAIT_ACCOUNT");return;}}
            if("staff_account".equals(b.get("source_type"))){var staff=db.jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role IN ('MERCHANT','STAFF') AND status='ACTIVE' AND is_deleted=0 FOR UPDATE NOWAIT",b.get("source_id"),b.get("merchant_id"));if(staff.isEmpty()){state(id,"WAIT_ACCOUNT");return;}}
            var couponIds=new TreeSet<Long>();var shops=new TreeSet<Long>();var projects=new TreeSet<Long>();
            if(b.get("merchant_id")!=null)shops.add(number(b,"merchant_id"));
            for(var i:items){couponIds.add(number(i,"coupon_id"));var terms=db.parse(i.get("terms_json"));projects.add(terms.path("project_id").longValue());var source=db.jdbc.queryForList(CouponInventory.SELECT+" WHERE c.id=?",i.get("coupon_id"));if(source.isEmpty()){state(id,"WAIT_SOURCE");return;}shops.add(number(source.get(0),"merchant_id"));if(!CouponInput.time(terms.get("valid_to")).isAfter(db.now())){state(id,"EXPIRED");return;}}
            for(long shop:shops){var rows=db.jdbc.queryForList("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",shop);if(rows.isEmpty()){state(id,"WAIT_SOURCE");return;}}
            for(long project:projects){var rows=db.jdbc.queryForList("SELECT id FROM standard_project WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",project);if(rows.isEmpty()){state(id,"WAIT_SOURCE");return;}}
            var sources=new HashMap<Long,Map<String,Object>>();
            for(long coupon:couponIds){try{sources.put(coupon,coupons.source(coupon,true));}catch(ResponseStatusException e){state(id,"WAIT_SOURCE");return;}}
            var quantities=new HashMap<Long,Integer>();
            for(var i:items){long coupon=number(i,"coupon_id");var s=sources.get(coupon);var terms=db.parse(i.get("terms_json"));if(!"PUBLISHED".equals(s.get("status"))||s.get("publication_revision")==null||number(s,"publication_revision")!=number(i,"publication_revision")||!terms.equals(db.parse(s.get("published_terms")))||!terms.equals(db.parse(s.get("terms_json")))||CouponInput.time(terms.get("valid_from")).isAfter(db.now())){state(id,"WAIT_SOURCE");return;}quantities.merge(coupon,1,Integer::sum);}
            for(var e:quantities.entrySet()){var s=sources.get(e.getKey());if(number(s,"total_quantity")-number(s,"issued_quantity")<e.getValue()){state(id,"WAIT_STOCK");return;}}
            // Source locks may have waited across the original expiry boundary. Recheck
            // after acquiring them and use one event time for both sides and completion.
            var deliveryAt=db.now();
            if(deliveryAt.isBefore(instant(b.get("qualified_at")))){state(id,"READY");return;}
            for(var i:items)if(!CouponInput.time(db.parse(i.get("terms_json")).get("valid_to")).isAfter(deliveryAt)){state(id,"EXPIRED");return;}
            for(var i:items){long coupon=number(i,"coupon_id"),user=number(i,"user_id"),uc=db.insert("INSERT INTO user_coupon(user_id,coupon_id,status) VALUES(?,?,'AVAILABLE')",user,coupon);
                db.jdbc.update("INSERT INTO coupon_issue(user_coupon_id,coupon_id,user_id,publication_revision,issued_at,origin,reward_item_id) VALUES(?,?,?,?,?,'INVITE',?)",uc,coupon,user,i.get("publication_revision"),time(deliveryAt),i.get("id"));
                db.jdbc.update("UPDATE coupon SET issued_quantity=issued_quantity+1 WHERE id=?",coupon);
                var s=coupons.row(coupon,true);
                db.jdbc.update("INSERT INTO coupon_stock_event(coupon_id,user_coupon_id,quantity_delta,stock_after,occurred_at) VALUES(?,?,-1,?,?)",coupon,uc,number(s,"total_quantity")-number(s,"issued_quantity"),time(deliveryAt));
                db.jdbc.update("UPDATE invite_reward_item SET user_coupon_id=? WHERE id=?",uc,i.get("id"));
            }
            db.jdbc.update("UPDATE invite_reward_batch SET state='COMPLETE',attempts=attempts+1,next_attempt_at=NULL,completed_at=? WHERE id=?",time(deliveryAt),id);audit(id,"INVITE_REWARD_COMPLETE","COMPLETE");
        });
    }
    private boolean evidence(Map<String,Object>b,Map<String,Object>r,List<Map<String,Object>> items){
        if(!"QUALIFIED".equals(r.get("qualification_status"))||!"VERIFIED_WECHAT".equals(r.get("identity_source"))||!"ATTRIBUTED".equals(r.get("attribution_result"))||!Objects.equals(b.get("record_id"),r.get("record_id"))||!Objects.equals(b.get("qualified_at"),r.get("qualified_at"))||!Objects.equals(b.get("source_type"),r.get("source_type"))||!Objects.equals(b.get("source_id"),r.get("source_id"))||!Objects.equals(b.get("merchant_id"),r.get("merchant_id")))return false;
        boolean owner="user".equals(b.get("source_type"));if(items.size()!=(owner?2:1))return false;
        var roles=new HashSet<String>();for(var i:items){String role=i.get("role").toString();roles.add(role);if(i.get("user_coupon_id")!=null||number(i,"user_id")!=(role.equals("INVITEE")?number(b,"invitee_id"):number(b,"source_id"))||(!owner&&role.equals("INVITER")))return false;}
        return roles.contains("INVITEE")&&(!owner||roles.contains("INVITER"))&&!db.jdbc.queryForList("SELECT user_id FROM invite_phone_evidence WHERE user_id=? AND app_id=? AND verified_at<=?",b.get("invitee_id"),r.get("app_id"),b.get("qualified_at")).isEmpty();
    }
    private void state(long id,String state){boolean terminal=Set.of("EXPIRED","INVALID_EVIDENCE").contains(state);db.jdbc.update("UPDATE invite_reward_batch SET state=?,attempts=attempts+1,next_attempt_at=? WHERE id=?",state,terminal?null:time(db.now().plusSeconds(300)),id);audit(id,"INVITE_REWARD_WAIT",state);}
    private void audit(long id,String action,String state){db.jdbc.update("INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id) VALUES('system',0,?,'invite_reward_batch',?,'{}',?,?)",action,id,db.json(Map.of("state",state,"rule_version","R6B_REWARD_V1")),UUID.randomUUID().toString());}

    public Map<String,Object> records(VehicleOwner a,int page){return db.reads.execute(tx->{db.owner(a,false);return records("b.invitee_id=? OR (b.source_type='user' AND b.source_id=?)",List.of(a.id(),a.id()),page,a.id());});}
    public Map<String,Object> records(MerchantActor a,int page){return db.reads.execute(tx->{db.merchant(a,false);return records("b.source_type='staff_account' AND b.source_id=? AND b.merchant_id=?",List.of(a.staffId(),a.merchantId()),page,null);});}
    private Map<String,Object> records(String where,List<Object> args,int page,Long owner){
        long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM invite_reward_batch b WHERE "+where,Long.class,args.toArray());var values=new ArrayList<>(args);values.add((page-1)*20L);
        var batches=db.jdbc.queryForList("SELECT b.* FROM invite_reward_batch b WHERE ("+where+") ORDER BY b.qualified_at DESC,b.id DESC LIMIT 20 OFFSET ?",values.toArray());
        var result=new ArrayList<Map<String,Object>>();for(var b:batches){var out=new LinkedHashMap<String,Object>();out.put("record_id",b.get("record_id"));out.put("source_type",Invitations.kind(b.get("source_type").toString()));out.put("state",b.get("state"));out.put("qualified_at",iso(b.get("qualified_at")));out.put("completed_at",iso(b.get("completed_at")));out.put("policy_revision",b.get("policy_revision"));out.put("rule_version",b.get("rule_version"));out.put("worker_enabled",enabled);
            var items=db.jdbc.queryForList("SELECT * FROM invite_reward_item WHERE batch_id=? ORDER BY role",b.get("id"));var detail=new ArrayList<Map<String,Object>>();for(var i:items){var item=new LinkedHashMap<String,Object>();boolean own=owner!=null&&number(i,"user_id")==owner;item.put("role",i.get("role"));item.put("own",own);item.put("terms",db.parse(i.get("terms_json")));item.put("user_coupon_id",own?i.get("user_coupon_id"):null);detail.add(item);}out.put("rewards",detail);result.add(out);}
        return page(result,total,page,20);
    }
}
