package com.autocare.platform.merchant;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.*;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class MerchantLocation {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final TransactionTemplate reads;
    public MerchantLocation(JdbcTemplate jdbc,WriteIntegrityService integrity,ObjectMapper mapper,boolean enabled,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.integrity=integrity;this.mapper=mapper;this.enabled=enabled;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);
    }
    private void authorize(MerchantActor actor,boolean lock) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"门店位置功能尚未开启");
        String tail=lock?" FOR UPDATE":"";
        var sessions=jdbc.queryForList("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' AND subject_id=? AND role=? AND app_id='merchant-account' AND merchant_id=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()"+tail,actor.session(),actor.staffId(),actor.role(),actor.merchantId());
        var staff=jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role=? AND status='ACTIVE' AND is_deleted=0"+tail,actor.staffId(),actor.merchantId(),actor.role());
        if(sessions.isEmpty() || staff.isEmpty() || !Instant.now().isBefore(actor.expires())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"门店登录已失效，请重新登录");
        if(jdbc.queryForList("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0"+tail,actor.merchantId()).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"本店已不可用");
    }
    private Map<String,Object> location(long merchant) {
        var rows=jdbc.queryForList("SELECT lng,lat,coordinate_system,source,version FROM merchant_location WHERE merchant_id=?",merchant);
        if(rows.isEmpty() || rows.get(0).get("lng")==null) return null;
        var row=rows.get(0);var r=new LinkedHashMap<String,Object>();
        r.put("lng",((java.math.BigDecimal)row.get("lng")).stripTrailingZeros().toPlainString());
        r.put("lat",((java.math.BigDecimal)row.get("lat")).stripTrailingZeros().toPlainString());
        r.put("coordinate_system",row.get("coordinate_system"));r.put("source",row.get("source"));r.put("version",((Number)row.get("version")).longValue());return r;
    }
    public Map<String,Object> read(MerchantActor actor) {
        return reads.execute(tx->{authorize(actor,false);var r=new LinkedHashMap<String,Object>();r.put("can_edit",actor.manager());r.put("location",location(actor.merchantId()));return r;});
    }
    public JsonNode update(MerchantActor actor,String key,LocationInput.Store input) {
        return integrity.execute(new WriteIntegrityService.Actor("staff_account",actor.staffId()),"PUT","/api/merchant/location",key,mapper.valueToTree(input.canonical()),
            ()->{if(!actor.manager())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅店长可维护门店位置");authorize(actor,true);},
            ()->{
                var before=location(actor.merchantId());
                jdbc.update("INSERT INTO merchant_location(merchant_id,lng,lat,coordinate_system,source,version,actor_staff_id) VALUES(?,?,?,?,?,1,?) ON DUPLICATE KEY UPDATE lng=VALUES(lng),lat=VALUES(lat),coordinate_system=VALUES(coordinate_system),source=VALUES(source),version=version+1,actor_staff_id=VALUES(actor_staff_id),updated_at=UTC_TIMESTAMP()",
                    actor.merchantId(),input.lng(),input.lat(),input.cleared()?null:"GCJ02",input.cleared()?null:"MERCHANT_CONFIRMED",actor.staffId());
                var after=location(actor.merchantId());var data=new LinkedHashMap<String,Object>();data.put("location",after);
                return new WriteIntegrityService.Change("MERCHANT_LOCATION_UPDATE","merchant",actor.merchantId(),before==null?Map.of():before,after==null?Map.of():after,data);
            });
    }
}
