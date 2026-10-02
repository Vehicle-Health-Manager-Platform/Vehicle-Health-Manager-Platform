package com.autocare.platform.gateway.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class JdbcMerchantIdentityRepository implements MerchantIdentityRepository {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public JdbcMerchantIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Merchant> byAccount(String account) {
        return find("s.account=?", account);
    }

    public Optional<Merchant> byId(long staffId) {
        return find("s.id=?", staffId);
    }

    private Optional<Merchant> find(String condition, Object value) {
        return jdbc.query("SELECT s.id,s.merchant_id,s.role,s.status,s.is_deleted,m.status,m.is_deleted,"
                + "s.phone,s.password_hash FROM staff_account s JOIN merchant m ON m.id=s.merchant_id WHERE "
                + condition, (rs, row) -> new Merchant(rs.getLong(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getBoolean(5), rs.getInt(6), rs.getBoolean(7),
                    rs.getString(8), rs.getString(9)), value).stream().findFirst();
    }

    @Transactional
    public void issueSmsCode(long staffId, String phone, String hash, Runnable deliver) {
        String purpose = purpose(staffId);
        jdbc.update("UPDATE sms_code SET consumed_at=UTC_TIMESTAMP() WHERE phone=? AND purpose=? "
            + "AND consumed_at IS NULL AND is_deleted=0", phone, purpose);
        jdbc.update("INSERT INTO sms_code (phone,purpose,code_hash,expires_at) VALUES (?,?,?,?)",
            phone, purpose, hash, Timestamp.from(Instant.now().plusSeconds(300)));
        // A provider failure rolls back the issued code together with the invalidation above.
        deliver.run();
    }

    @Transactional
    public boolean consumeSmsCode(long staffId, String phone, String code) {
        var rows = jdbc.query("SELECT id,code_hash FROM sms_code WHERE phone=? AND purpose=? "
                + "AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP() AND is_deleted=0 "
                + "ORDER BY id DESC LIMIT 1 FOR UPDATE",
            (rs, row) -> new Object[] {rs.getLong(1), rs.getString(2)}, phone, purpose(staffId));
        if (rows.isEmpty() || !encoder.matches(code, (String) rows.get(0)[1])) return false;
        return jdbc.update("UPDATE sms_code SET consumed_at=UTC_TIMESTAMP() WHERE id=? AND consumed_at IS NULL",
            rows.get(0)[0]) == 1;
    }

    private static String purpose(long staffId) {
        return "M" + staffId;
    }
}
