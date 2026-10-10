package com.autocare.platform.gateway.identity;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Trusted operator workflow; never exposed as an HTTP endpoint. */
public class StaffCodeOperations {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public StaffCodeOperations(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public String issue(long staffId) {
        TechnicianIdentityLocks.staff(jdbc,staffId);
        var rows = jdbc.query("SELECT s.role,s.status,s.is_deleted,m.status,m.is_deleted FROM staff_account s "
                + "JOIN merchant m ON m.id=s.merchant_id WHERE s.id=? FOR UPDATE",
            (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3),
                rs.getInt(4), rs.getBoolean(5)}, staffId);
        if (rows.size() != 1 || !"TECHNICIAN".equals(rows.get(0)[0]) || !"ACTIVE".equals(rows.get(0)[1])
            || (boolean) rows.get(0)[2] || (int) rows.get(0)[3] != 1 || (boolean) rows.get(0)[4]) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用");
        }
        return rotate(staffId);
    }

    @Transactional
    public void revoke(long staffId) {
        TechnicianIdentityLocks.staff(jdbc,staffId);
        clear(staffId);
    }

    /**
     * 店长受控签发：先按本店归属过滤，跨店目标按不存在处理，不去触碰其他门店的锁。
     * 锁序沿用 商家 → 员工 → 绑定。
     */
    @Transactional
    public String issueForMerchant(long merchantId, long staffId) {
        lockOwnedTechnician(merchantId, staffId);
        return rotate(staffId);
    }

    /** 店长受控撤销：清空员工码并撤销该技师全部有效微信绑定。 */
    @Transactional
    public void revokeForMerchant(long merchantId, long staffId) {
        lockOwnedTechnician(merchantId, staffId);
        clear(staffId);
    }

    private void lockOwnedTechnician(long merchantId, long staffId) {
        var shop = jdbc.query("SELECT id FROM merchant WHERE id=? AND status=1 AND is_deleted=0 FOR UPDATE",
            (rs, row) -> rs.getLong(1), merchantId);
        var staff = jdbc.query("SELECT role,status FROM staff_account WHERE id=? AND merchant_id=? AND is_deleted=0 FOR UPDATE",
            (rs, row) -> new Object[] {rs.getString(1), rs.getString(2)}, staffId, merchantId);
        if (shop.isEmpty() || staff.isEmpty() || !"TECHNICIAN".equals(staff.get(0)[0])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "员工不存在或不属于本店");
        }
        if (!"ACTIVE".equals(staff.get(0)[1])) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "员工已停用，不能签发或撤销员工码");
        }
    }

    private String rotate(long staffId) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE staff_account SET employee_code_hash=? WHERE id=?", AuthTokens.sha256(code), staffId);
        revokeBindings(staffId);
        return code;
    }

    private void clear(long staffId) {
        jdbc.update("UPDATE staff_account SET employee_code_hash=NULL WHERE id=?", staffId);
        revokeBindings(staffId);
    }

    private void revokeBindings(long staffId) {
        jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED', unbound_at=UTC_TIMESTAMP() "
            + "WHERE staff_account_id=? AND status='ACTIVE' AND is_deleted=0", staffId);
    }
}
