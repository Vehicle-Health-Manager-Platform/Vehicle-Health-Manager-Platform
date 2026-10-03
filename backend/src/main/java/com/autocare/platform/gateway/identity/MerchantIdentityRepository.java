package com.autocare.platform.gateway.identity;

import java.util.Optional;

public interface MerchantIdentityRepository {
    record Merchant(long staffId, long merchantId, String role, String staffStatus,
                    boolean staffDeleted, int merchantStatus, boolean merchantDeleted,
                    String phone, String passwordHash) {
        public boolean active() {
            return "MERCHANT".equals(role) && "ACTIVE".equals(staffStatus)
                && !staffDeleted && merchantStatus == 1 && !merchantDeleted;
        }
    }

    Optional<Merchant> byAccount(String account);
    Optional<Merchant> byId(long staffId);
    void issueSmsCode(long staffId, String phone, String hash, Runnable deliver);
    boolean consumeSmsCode(long staffId, String phone, String code);
}
