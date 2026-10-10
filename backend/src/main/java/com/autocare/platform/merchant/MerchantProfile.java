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

    /**
     * 坐标列是 DECIMAL(10,7)，直接 toString 会补满 7 位小数（113.2644000），
     * 而写响应里的坐标来自请求 JSON（113.2644）——同一个值两种格式会让客户端对不上。
     * 读与写都经这里规范化。
     */
    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof java.math.BigDecimal decimal) {
            var stripped = decimal.stripTrailingZeros();
            // stripTrailingZeros 会把 100 表示成 1E+2，必须按 scale 还原成普通写法。
            return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
        }
        return value.toString();
    }

    /**
     * 写响应的载荷。必须用 null 容忍的容器：`Map.of` 不接受 null 值，
     * 门店未填经纬度时（lng/lat 为 null）会在成功路径上抛 NPE 变成 500。
     */
    private static Map<String, Object> writable(String name, String address, String phone, String lng, String lat) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("address", address);
        result.put("contact_phone", phone);
        result.put("lng", lng);
        result.put("lat", lat);
        return result;
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
        // 坐标经 text() 规范化后再入幂等载荷：113.2644 与 113.26440000 是同一个意图，
        // 不应因为尾随 0 不同就算成两次不同的写。
        String lng = lngNode.isNull() ? null : text(lngNode.decimalValue());
        String lat = latNode.isNull() ? null : text(latNode.decimalValue());
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
                var after = writable(name, address, phone, lng, lat);
                return new WriteIntegrityService.Change("MERCHANT_PROFILE_UPDATE", "merchant", actor.merchantId(),
                    editable(before), after, after);
            });
    }
}
