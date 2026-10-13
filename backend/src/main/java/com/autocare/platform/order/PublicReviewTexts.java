package com.autocare.platform.order;

import com.autocare.platform.common.write.WriteIntegrityService.*;
import com.autocare.platform.gateway.identity.*;
import com.autocare.platform.service.ServiceCatalog;
import com.autocare.platform.vehicle.VehicleOwner;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class PublicReviewTexts {
    private final ReservationStore db;private final PublicRatings ratings;private final OperatorIdentity operators;private final boolean enabled;
    public PublicReviewTexts(ReservationStore db,OperatorIdentity operators,boolean enabled){this.db=db;this.operators=operators;this.enabled=enabled;ratings=new PublicRatings(db);}
    private void enabled(){if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"公开文本投稿与审核尚未开启");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"文本版本、授权或来源已变化，请刷新确认");}
    static void page(int page,int size){if(page<1||page>1000000||size<1||size>50)throw ReservationInput.bad();}
    private Map<String,Object> text(long review,boolean lock){var rows=db.jdbc.queryForList("SELECT * FROM public_review_text WHERE review_id=?"+(lock?" FOR UPDATE":""),review);return rows.isEmpty()?null:rows.get(0);}
    private boolean approved(Map<String,Object> t){if(t==null||!"APPROVED".equals(t.get("status"))||!PublicReviewTextInput.CONSENT.equals(t.get("consent_version")))return false;
        return db.jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text_moderation a JOIN public_review_text t ON t.review_id=a.review_id WHERE t.review_id=? AND a.decision='APPROVE' AND a.request_version+1=t.version AND a.public_id=t.public_id AND a.text_hash=t.text_hash AND t.text_hash=SHA2(t.public_text,256)",Long.class,t.get("review_id"))==1L;}
    private Map<String,Object> state(long order,long review){var t=text(review,false);boolean can=enabled&&ratings.eligible(review);
        var out=new LinkedHashMap<String,Object>();out.put("order_id",order);out.put("version",t==null?0L:ReservationStore.number(t,"version"));out.put("status",t==null?"NONE":t.get("status"));out.put("public_text",t==null?null:t.get("public_text"));out.put("reason_code",t==null?null:t.get("reason_code"));
        out.put("visible",can&&approved(t));out.put("can_submit",can);out.put("unavailable_reason",can?null:!enabled?"DISABLED":"PUBLIC_SOURCE_UNVERIFIED");return out;}
    public Map<String,Object> detail(VehicleOwner owner,long order){return db.reads.execute(tx->state(order,ReservationStore.number(ratings.owned(owner,order,false),"id")));}
    public Map<String,Object> preview(VehicleOwner owner,long order,JsonNode body){PublicReviewTextInput.fields(body,"public_text");String redacted=PublicReviewTextInput.redact(body.get("public_text"));
        return db.reads.execute(tx->{var r=ratings.owned(owner,order,false);enabled();if(!ratings.eligible(ReservationStore.number(r,"id")))throw conflict();return Map.of("public_text",redacted,"redaction_version",PublicReviewTextInput.REDACTION);});}
    private Map<String,Object> projection(Map<String,Object> t){return t==null?Map.of():Map.of("status",t.get("status"),"version",t.get("version"));}
    public JsonNode update(VehicleOwner owner,long order,String key,JsonNode raw){var b=PublicReviewTextInput.owner(raw);boolean submit=b.get("action").textValue().equals("SUBMIT");
        return db.writes.execute(new Actor("user",owner.id()),"PUT","/api/order/"+order+"/public-review-text",key,b,
            ()->{var r=ratings.owned(owner,order,true);if(submit){enabled();if(!ratings.eligible(ReservationStore.number(r,"id")))throw conflict();}},
            ()->{var r=ratings.owned(owner,order,true);long review=ReservationStore.number(r,"id");var t=text(review,true);long version=t==null?0:ReservationStore.number(t,"version");if(version!=b.get("expected_version").longValue())throw conflict();
                String content=submit?b.get("public_text").textValue():null;
                db.jdbc.update("INSERT INTO public_review_text(review_id,public_text,text_hash,consent_version,status,version,submitted_at) VALUES(?,?,SHA2(?,256),?,?,1,?) ON DUPLICATE KEY UPDATE public_text=VALUES(public_text),text_hash=VALUES(text_hash),consent_version=VALUES(consent_version),status=VALUES(status),version=version+1,reason_code=NULL,public_id=NULL,submitted_at=VALUES(submitted_at),published_at=NULL",review,content,content,submit?PublicReviewTextInput.CONSENT:null,submit?"PENDING":"WITHDRAWN",submit?ReservationStore.time(db.now()):null);
                return new Change("PUBLIC_REVIEW_TEXT_UPDATE","order_review",review,projection(t),projection(text(review,false)),state(order,review));
            });}
    public Map<String,Object> pending(OperatorActor actor,int page,int size){page(page,size);return db.reads.execute(tx->{operators.authorize(actor,true,false);enabled();
        var rows=db.jdbc.queryForList("SELECT review_id,version,public_text FROM public_review_text WHERE status='PENDING' AND consent_version=? ORDER BY review_id DESC LIMIT ? OFFSET ?",PublicReviewTextInput.CONSENT,size,(page-1)*size);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows){var item=new LinkedHashMap<>(row);item.put("can_approve",ratings.eligible(ReservationStore.number(row,"review_id")));items.add(item);}
        long total=db.jdbc.queryForObject("SELECT COUNT(*) FROM public_review_text WHERE status='PENDING' AND consent_version=?",Long.class,PublicReviewTextInput.CONSENT);
        return Map.of("items",items,"total",total,"page",page,"page_size",size);
    });}
    private Map<String,Object> lockReview(long review){var r=db.one("SELECT order_id FROM order_review WHERE id=?",review);db.one("SELECT id FROM `order` WHERE id=? FOR UPDATE",r.get("order_id"));return db.one("SELECT id,order_id FROM order_review WHERE id=? FOR UPDATE",review);}
    public JsonNode moderate(OperatorActor actor,long review,String key,JsonNode raw){ServiceCatalog.validateId(review);var b=PublicReviewTextInput.moderate(raw);long requested=b.get("expected_version").longValue();String decision=b.get("decision").textValue();String reason=b.get("reason_code").isNull()?null:b.get("reason_code").textValue();
        return db.writes.execute(new Actor("operator_account",actor.id()),"POST","/api/admin/review-texts/"+review+"/moderate",key,b,
            ()->{operators.authorize(actor,true,true);enabled();lockReview(review);var t=text(review,true);if(t==null||!PublicReviewTextInput.CONSENT.equals(t.get("consent_version")))throw conflict();
                long current=ReservationStore.number(t,"version");String target=decision.equals("APPROVE")?"APPROVED":"REJECTED";
                if(!(current==requested&&"PENDING".equals(t.get("status")))){if(current!=requested+1||!target.equals(t.get("status")))throw conflict();
                    var m=db.one("SELECT decision,reason_code FROM public_review_text_moderation WHERE review_id=? AND request_version=?",review,requested);if(!decision.equals(m.get("decision"))||!Objects.equals(reason,m.get("reason_code")))throw conflict();}
                if(decision.equals("APPROVE")&&(!ratings.eligible(review)||!PublicReviewTextInput.redact(db.mapper.valueToTree(t.get("public_text"))).equals(t.get("public_text"))))throw conflict();
            },
            ()->{var t=text(review,true);boolean changed=ReservationStore.number(t,"version")==requested;
                if(changed){String publicId=decision.equals("APPROVE")?UUID.randomUUID().toString():null;
                    db.jdbc.update("INSERT INTO public_review_text_moderation(review_id,request_version,text_hash,decision,reason_code,operator_id,decided_at,public_id) VALUES(?,?,?,?,?,?,?,?)",review,requested,t.get("text_hash"),decision,reason,actor.id(),ReservationStore.time(db.now()),publicId);
                    db.jdbc.update("UPDATE public_review_text SET status=?,version=version+1,reason_code=?,public_id=?,published_at=? WHERE review_id=?",decision.equals("APPROVE")?"APPROVED":"REJECTED",reason,publicId,publicId==null?null:ReservationStore.time(db.now()),review);
                }
                var after=text(review,false);return new Change("PUBLIC_REVIEW_TEXT_MODERATE","order_review",review,projection(t),projection(after),Map.of("review_id",review,"version",after.get("version"),"status",after.get("status")),changed);
            });}
    static String approvedFrom(){
        return PublicRatings.SOURCE+" JOIN public_review_text t ON t.review_id=r.id JOIN public_review_text_moderation a ON a.review_id=t.review_id AND a.request_version+1=t.version AND a.decision='APPROVE' AND a.public_id=t.public_id AND a.text_hash=t.text_hash WHERE t.status='APPROVED' AND t.consent_version='R4B2_TEXT_V1' AND t.text_hash=SHA2(t.public_text,256) AND t.published_at IS NOT NULL AND "+PublicRatings.VALID;
    }
    public Map<String,Object> list(VehicleOwner owner,long merchant,int page,int size){ServiceCatalog.validateId(merchant);page(page,size);return db.reads.execute(tx->{db.owner(owner,false);db.one("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0",merchant);
        if(!enabled)return Map.of("merchant_id",merchant,"items",List.of(),"total",0L,"page",page,"page_size",size);
        String from=approvedFrom()+" AND o.merchant_id=?";
        long total=db.jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,merchant);
        var items=db.jdbc.query("SELECT t.public_id,t.public_text,DATE_FORMAT(t.published_at,'%Y-%m-%d') AS published_date"+from+" ORDER BY t.submitted_at DESC,r.id DESC LIMIT ? OFFSET ?",(rs,n)->Map.of("public_id",rs.getString("public_id"),"public_text",rs.getString("public_text"),"published_date",rs.getString("published_date")),merchant,size,(page-1)*size);
        return Map.of("merchant_id",merchant,"items",items,"total",total,"page",page,"page_size",size);
    });}
}
