package com.autocare.platform.merchant;

import com.autocare.platform.common.write.WriteIntegrityService;
import com.autocare.platform.gateway.identity.StaffCodeOperations;
import com.autocare.platform.service.MerchantActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 店长维护本店店员与技师：新增、启停即时失效、员工码受控签发与撤销。
 * 所有写操作都以调用者的 merchant_id 为准，跨店资源按不存在处理。
 */
public class MerchantStaff {
    private final JdbcTemplate jdbc;
    private final WriteIntegrityService integrity;
    private final ObjectMapper mapper;
    private final StaffCodeOperations codes;
    private final String appId;
    private final boolean enabled;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public MerchantStaff(JdbcTemplate jdbc, WriteIntegrityService integrity, ObjectMapper mapper,
                         StaffCodeOperations codes, String appId, boolean enabled,
                         PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.integrity = integrity;
        this.mapper = mapper;
        this.codes = codes;
        this.appId = appId;
        this.enabled = enabled;
        reads = new TransactionTemplate(manager);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setTimeout(15);
    }

    /** 开关默认关闭：迁移与页面就绪后再显式开启，关闭时全部接口（含本人查询）不可用。 */
    private void requireEnabled() {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "门店员工功能尚未开启");
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "员工不存在或不属于本店");
    }

    private static ResponseStatusException notManager() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "仅店长可维护本店员工与员工码");
    }

    /** 会话、员工行与门店必须同时有效；角色必须等于令牌角色。 */
    private void authorize(MerchantActor actor, boolean lock) {
        String tail = lock ? " FOR UPDATE" : "";
        var session = jdbc.query("SELECT id FROM auth_session WHERE id=? AND subject_type='staff_account' "
            + "AND subject_id=? AND role=? AND app_id='merchant-account' AND merchant_id=? "
            + "AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP()" + tail,
            (rs, n) -> rs.getString(1), actor.session(), actor.staffId(), actor.role(), actor.merchantId());
        var staff = jdbc.query("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role=? "
            + "AND status='ACTIVE' AND is_deleted=0" + tail,
            (rs, n) -> rs.getLong(1), actor.staffId(), actor.merchantId(), actor.role());
        var shop = jdbc.query("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0" + tail,
            (rs, n) -> rs.getLong(1), actor.merchantId());
        if (session.isEmpty() || staff.isEmpty() || shop.isEmpty() || !Instant.now().isBefore(actor.expires())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "门店登录已失效，请重新登录");
        }
    }

    private void requireManager(MerchantActor actor) {
        if (!actor.manager()) throw notManager();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean flag) return flag;
        return value instanceof Number number && number.intValue() != 0;
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() < 7) return null;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static Map<String, Object> view(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("staff_id", ((Number) source.get("id")).longValue());
        result.put("account", source.get("account"));
        result.put("role", source.get("role"));
        result.put("display_name", source.get("display_name"));
        result.put("phone_masked", mask((String) source.get("phone")));
        result.put("status", source.get("status"));
        result.put("employee_code_issued", truthy(source.get("code_issued")));
        result.put("wechat_bound", truthy(source.get("wechat_bound")));
        result.put("created_at", source.get("created_at") == null ? null : source.get("created_at").toString());
        return result;
    }

    private static final int MAX_SEQ = 9999;
    /**
     * 员工行投影：列表、回读与「新增响应」共用同一份，避免创建响应与列表行字段不一致
     * （少字段会让客户端把合法响应判成协议错误）。密码哈希与员工码明文永不出现。
     */
    private static final String PROJECTION = "SELECT s.id,s.account,s.role,s.display_name,s.phone,s.status,s.created_at,"
        + "(s.employee_code_hash IS NOT NULL) AS code_issued,EXISTS(SELECT 1 FROM staff_wechat_identity b "
        + "WHERE b.staff_account_id=s.id AND b.app_id=? AND b.status='ACTIVE' AND b.is_deleted=0 "
        + "AND b.unbound_at IS NULL) AS wechat_bound";

    public Map<String, Object> list(MerchantActor actor, String role, int page, int size) {
        requireEnabled();
        MerchantStaffInput.page(page, size);
        if (role != null) MerchantStaffInput.role(role);
        return reads.execute(status -> {
            authorize(actor, false);
            String where = " FROM staff_account s WHERE s.merchant_id=? AND s.role IN ('STAFF','TECHNICIAN') AND s.is_deleted=0"
                + (role == null ? "" : " AND s.role=?");
            List<Object> filter = new ArrayList<>();
            filter.add(actor.merchantId());
            if (role != null) filter.add(role);
            long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, filter.toArray());
            // SELECT 里的 app_id 占位符排在 WHERE 之前，参数顺序必须与之对齐。
            List<Object> args = new ArrayList<>();
            args.add(appId);
            args.addAll(filter);
            args.add(size);
            args.add((page - 1L) * size);
            var rows = jdbc.queryForList(PROJECTION + where + " ORDER BY s.id LIMIT ? OFFSET ?", args.toArray());
            return Map.of("items", rows.stream().map(MerchantStaff::view).toList(),
                "total", total, "page", page, "page_size", size);
        });
    }

    /** 按同一投影回读本店单行；新增后用它作为响应载荷，保证与列表行同形。 */
    private Map<String, Object> row(MerchantActor actor, long staffId) {
        var rows = jdbc.queryForList(PROJECTION + " FROM staff_account s WHERE s.id=? AND s.merchant_id=? AND s.is_deleted=0",
            appId, staffId, actor.merchantId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "新增员工后读取失败，请使用原幂等键重试");
        return view(rows.get(0));
    }

    private String account(long merchantId, String role) {
        String prefix = "STAFF".equals(role) ? "s" : "t";
        for (int seq = 1; seq <= MAX_SEQ; seq++) {
            String candidate = prefix + merchantId + "-" + seq;
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM staff_account WHERE account=?", Integer.class, candidate);
            if (used == null || used == 0) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "本店员工账号序号已用尽");
    }

    public JsonNode create(MerchantActor actor, String key, JsonNode body) {
        requireEnabled();
        var input = MerchantStaffInput.creation(body);
        String role = input.get("role").textValue();
        String displayName = input.get("display_name").textValue();
        String phone = input.has("phone") ? input.get("phone").textValue() : null;
        String password = input.get("password").textValue();
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("role", role);
        canonical.put("display_name", displayName);
        canonical.put("phone", phone == null ? "" : phone);
        return integrity.execute(new WriteIntegrityService.Actor("staff_account", actor.staffId()),
            "POST", "/api/merchant/staff", key, mapper.valueToTree(canonical),
            () -> {
                requireManager(actor);
                authorize(actor, true);
            },
            () -> {
                String generated = account(actor.merchantId(), role);
                var holder = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var statement = connection.prepareStatement("INSERT INTO staff_account "
                        + "(merchant_id,role,account,display_name,phone,password_hash,status,created_by) "
                        + "VALUES(?,?,?,?,?,?,'ACTIVE',?)", Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, actor.merchantId());
                    statement.setString(2, role);
                    statement.setString(3, generated);
                    statement.setString(4, displayName);
                    statement.setString(5, phone);
                    statement.setString(6, encoder.encode(password));
                    statement.setLong(7, actor.staffId());
                    return statement;
                }, holder);
                long staffId = holder.getKey().longValue();
                // 响应载荷用同一投影回读：与列表行逐字段同形，不含密码与手机号原文。
                var created = row(actor, staffId);
                return new WriteIntegrityService.Change("MERCHANT_STAFF_CREATE", "staff_account", staffId,
                    Map.of(), created, created);
            });
    }

    public JsonNode disable(MerchantActor actor, long staffId, String key) {
        requireEnabled();
        MerchantStaffInput.id(staffId);
        return integrity.execute(new WriteIntegrityService.Actor("staff_account", actor.staffId()),
            "POST", "/api/merchant/staff/" + staffId + "/disable", key, mapper.valueToTree(Map.of("staff_id", staffId)),
            () -> {
                requireManager(actor);
                authorize(actor, true);
            },
            () -> {
                var target = lockedTarget(actor, staffId);
                String before = (String) target.get("status");
                if (!"DISABLED".equals(before)) {
                    jdbc.update("UPDATE staff_account SET status='DISABLED', employee_code_hash=NULL WHERE id=?", staffId);
                }
                // 停用必须同时撤销既有会话，否则刷新仍能换出可用令牌。
                int revoked = jdbc.update("UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() "
                    + "WHERE subject_type='staff_account' AND subject_id=? AND revoked_at IS NULL", staffId);
                if ("TECHNICIAN".equals(target.get("role"))) {
                    jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED', unbound_at=UTC_TIMESTAMP() "
                        + "WHERE staff_account_id=? AND status='ACTIVE' AND is_deleted=0", staffId);
                }
                Map<String, Object> previous = Map.of("staff_id", staffId, "status", before);
                Map<String, Object> after = Map.of("staff_id", staffId, "status", "DISABLED", "sessions_revoked", revoked);
                return new WriteIntegrityService.Change("MERCHANT_STAFF_DISABLE", "staff_account", staffId,
                    previous, after, after);
            });
    }

    public JsonNode enable(MerchantActor actor, long staffId, String key) {
        requireEnabled();
        MerchantStaffInput.id(staffId);
        return integrity.execute(new WriteIntegrityService.Actor("staff_account", actor.staffId()),
            "POST", "/api/merchant/staff/" + staffId + "/enable", key, mapper.valueToTree(Map.of("staff_id", staffId)),
            () -> {
                requireManager(actor);
                authorize(actor, true);
            },
            () -> {
                var target = lockedTarget(actor, staffId);
                String before = (String) target.get("status");
                if (!"ACTIVE".equals(before)) {
                    jdbc.update("UPDATE staff_account SET status='ACTIVE' WHERE id=?", staffId);
                }
                Map<String, Object> previous = Map.of("staff_id", staffId, "status", before);
                Map<String, Object> after = Map.of("staff_id", staffId, "status", "ACTIVE");
                return new WriteIntegrityService.Change("MERCHANT_STAFF_ENABLE", "staff_account", staffId,
                    previous, after, after);
            });
    }

    /** 锁定本店目标员工行；跨店或非本店角色按不存在处理。 */
    private Map<String, Object> lockedTarget(MerchantActor actor, long staffId) {
        var rows = jdbc.query("SELECT role,status FROM staff_account WHERE id=? AND merchant_id=? AND is_deleted=0 "
                + "AND role IN ('STAFF','TECHNICIAN') FOR UPDATE",
            (rs, n) -> Map.<String, Object>of("role", rs.getString(1), "status", rs.getString(2)),
            staffId, actor.merchantId());
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    /**
     * 员工码是轮换语义而非可重放写：一次性明文码绝不进入幂等记录或审计。
     * 授权、轮换与审计放在同一事务里，避免检查与轮换之间出现窗口。
     */
    public Map<String, Object> issueCode(MerchantActor actor, long staffId) {
        requireEnabled();
        MerchantStaffInput.id(staffId);
        return writes.execute(status -> {
            requireManager(actor);
            authorize(actor, true);
            String code = codes.issueForMerchant(actor.merchantId(), staffId);
            audit(actor, "STAFF_CODE_ISSUE", staffId, Map.of("staff_id", staffId, "issued", false),
                Map.of("staff_id", staffId, "issued", true));
            // 一次性明文码只在本次响应中出现。
            return Map.of("staff_id", staffId, "employee_code", code, "single_use", true);
        });
    }

    public Map<String, Object> revokeCode(MerchantActor actor, long staffId) {
        requireEnabled();
        MerchantStaffInput.id(staffId);
        return writes.execute(status -> {
            requireManager(actor);
            authorize(actor, true);
            codes.revokeForMerchant(actor.merchantId(), staffId);
            audit(actor, "STAFF_CODE_REVOKE", staffId, Map.of("staff_id", staffId, "issued", true),
                Map.of("staff_id", staffId, "issued", false));
            return Map.of("staff_id", staffId, "employee_code_revoked", true);
        });
    }

    private void audit(MerchantActor actor, String action, long staffId, Map<String, Object> before,
                       Map<String, Object> after) {
        jdbc.update("INSERT INTO audit_log (actor_type,actor_id,action,resource_type,resource_id,before_state,"
            + "after_state,request_id) VALUES ('staff_account',?,?,'staff_account',?,?,?,?)",
            actor.staffId(), action, staffId, mapper.valueToTree(before).toString(),
            mapper.valueToTree(after).toString(), UUID.randomUUID().toString());
    }
}
