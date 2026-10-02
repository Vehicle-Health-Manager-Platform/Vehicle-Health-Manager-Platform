package com.autocare.platform.gateway.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

public class JdbcIdentityRepository implements IdentityRepository {
    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Owner> ownerByOpenid(String openid) {
        return jdbc.query("SELECT id, phone, status, is_deleted FROM `user` WHERE openid=?",
            (rs, row) -> new Owner(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)), openid)
            .stream().findFirst();
    }

    public Optional<Owner> ownerById(long id) {
        return jdbc.query("SELECT id, phone, status, is_deleted FROM `user` WHERE id=?",
            (rs, row) -> new Owner(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)), id)
            .stream().findFirst();
    }

    public Owner createOwnerOrRead(String openid) {
        // The database unique key resolves simultaneous first logins for one openid.
        jdbc.update("INSERT INTO `user` (openid) VALUES (?) ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)", openid);
        return ownerByOpenid(openid).orElseThrow();
    }

    public Optional<Technician> technicianByOpenid(String appId, String openid) {
        return technicians("b.app_id=? AND b.openid=?", appId, openid);
    }

    public Optional<Technician> technicianByBindingId(long bindingId) {
        return technicians("b.id=?", bindingId);
    }

    private Optional<Technician> technicians(String condition, Object... args) {
        return jdbc.query("SELECT b.id, s.id, s.merchant_id, s.role, s.status, s.is_deleted, m.status, m.is_deleted "
            + "FROM staff_wechat_identity b JOIN staff_account s ON s.id=b.staff_account_id "
            + "JOIN merchant m ON m.id=s.merchant_id WHERE b.status='ACTIVE' AND b.is_deleted=0 AND " + condition,
            (rs, row) -> new Technician(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getBoolean(6), rs.getInt(7), rs.getBoolean(8)), args).stream().findFirst();
    }

    @Transactional
    public Technician bindTechnician(String appId, String openid, String employeeCode) {
        if (employeeCode == null || employeeCode.length() < 8 || employeeCode.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "员工码无效");
        }
        String hash = sha256(employeeCode);
        var staff = jdbc.query("SELECT s.id, s.role, s.status, s.is_deleted, s.merchant_id, m.status, m.is_deleted "
            + "FROM staff_account s JOIN merchant m ON m.id=s.merchant_id WHERE s.employee_code_hash=? FOR UPDATE",
            (rs, row) -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getLong(5), rs.getInt(6), rs.getBoolean(7)}, hash).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "员工码无效或账号不可用"));
        if (!"TECHNICIAN".equals(staff[1]) || !"ACTIVE".equals(staff[2]) || (boolean) staff[3]
            || (int) staff[5] != 1 || (boolean) staff[6]) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "员工或商家不可用");
        }
        try {
            jdbc.update("INSERT INTO staff_wechat_identity (app_id, openid, staff_account_id) VALUES (?,?,?)",
                appId, openid, staff[0]);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "微信身份或员工账号已绑定");
        }
        return technicianByOpenid(appId, openid).orElseThrow();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
