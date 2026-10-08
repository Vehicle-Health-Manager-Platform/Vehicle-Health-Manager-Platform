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
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("UPDATE staff_account SET employee_code_hash=? WHERE id=?", AuthTokens.sha256(code), staffId);
        revokeBindings(staffId);
        return code;
    }

    @Transactional
    public void revoke(long staffId) {
        TechnicianIdentityLocks.staff(jdbc,staffId);
        jdbc.update("UPDATE staff_account SET employee_code_hash=NULL WHERE id=?", staffId);
        revokeBindings(staffId);
    }

    private void revokeBindings(long staffId) {
        jdbc.update("UPDATE staff_wechat_identity SET status='REVOKED', unbound_at=UTC_TIMESTAMP() "
            + "WHERE staff_account_id=? AND status='ACTIVE' AND is_deleted=0", staffId);
    }
}
