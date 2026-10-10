package com.autocare.platform.merchant;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本店对外资料：店长与店员均可读，只有店长可改。
 * 品类、区域、资质与经营状态属于入驻与运营范畴，不经本接口修改。
 */
public class MerchantProfile {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final TransactionTemplate reads;

    public MerchantProfile(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper,
                           boolean enabled, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.integrity = integrity;
        this.mapper = mapper;
        this.enabled = enabled;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 开关默认关闭；关闭时读与写都返回 50300。 */
    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门店员工功能尚未开启");
    }

    private void authorize(MerchantActor actor, boolean lock) {
        String tail = lock ? " FOR UPDATE" : "";
        var session = jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' "
            + "AND subject_id=? AND role=? AND app_id='merchant-account' AND merchant_id=? "
            + "AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + tail,
            (rs, n) -> rs.getString(1), actor.session(), actor.staffId(), actor.role(), actor.merchantId());
        var staff = jdbc.query("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role=? "
            + "AND status='ACTIVE' AND is_deleted=0" + tail,
            (rs, n) -> rs.getLong(1), actor.staffId(), actor.merchantId(), actor.role());
        if (session.isEmpty() || staff.isEmpty() || !Instant.now().isBefore(actor.expires())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "门店登录已失效，请重新登录");
        }
    }

    private static Map<String, Object> editable(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", row.get("name"));
        result.put("address", row.get("address"));
        result.put("contact_phone", row.get("contact_phone"));
        result.put("lng", text(row.get("lng")));
        result.put("lat", text(row.get("lat")));
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    public Map<String, Object> read(MerchantActor actor) {
        requireEnabled();
        return reads.execute(status -> {
            authorize(actor, false);
            var rows = jdbc.queryForList("SELECT id,name,address,contact_phone,lng,lat,merchant_type,region_code,status "
                + "FROM merchant WHERE id=? AND is_deleted=0", actor.merchantId());
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本店不存在或已停用");
            var row = rows.get(0);
            var result = new LinkedHashMap<String, Object>();
            result.put("merchant_id", ((Number) row.get("id")).longValue());
            result.putAll(editable(row));
            result.put("merchant_type", ((Number) row.get("merchant_type")).intValue());
            result.put("region_code", row.get("region_code"));
            result.put("status", ((Number) row.get("status")).intValue());
            result.put("can_edit", actor.manager());
            return result;
        });
    }

    public JsonNode update(MerchantActor actor, String key, JsonNode body) {
        requireEnabled();
        var input = MerchantStaffInput.profile(body);
        String name = input.get("name").textValue();
        String address = input.get("address").textValue();
        String phone = input.get("contact_phone").textValue();
        JsonNode lngNode = input.get("lng");
        JsonNode latNode = input.get("lat");
        String lng = lngNode.isNull() ? null : lngNode.decimalValue().toString();
        String lat = latNode.isNull() ? null : latNode.decimalValue().toString();
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("name", name);
        canonical.put("address", address);
        canonical.put("contact_phone", phone);
        canonical.put("lng", lng == null ? "" : lng);
        canonical.put("lat", lat == null ? "" : lat);
        return integrity.execute(new WriteIntegrityService.Actor("staff_account", actor.staffId()),
            "PUT", "/api/merchant/profile", key, mapper.valueToTree(canonical),
            () -> {
                if (!actor.manager()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅店长可修改本店资料");
                }
                authorize(actor, true);
                // 门店必须存在且在营，否则资料更新会写到一个不可用门店上。
                if (jdbc.queryForList("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",
                    actor.merchantId()).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本店不存在或已停用");
                }
            },
            () -> {
                var before = jdbc.queryForList("SELECT name,address,contact_phone,lng,lat FROM merchant WHERE id=?",
                    actor.merchantId()).get(0);
                jdbc.update("UPDATE merchant SET name=?,address=?,contact_phone=?,lng=?,lat=? WHERE id=?",
                    name, address, phone, lng, lat, actor.merchantId());
                var after = Map.<String, Object>of("name", name, "address", address, "contact_phone", phone,
                    "lng", lng, "lat", lat);
                return new WriteIntegrityService.Change("MERCHANT_PROFILE_UPDATE", "merchant", actor.merchantId(),
                    editable(before), after, after);
            });
    }
}
