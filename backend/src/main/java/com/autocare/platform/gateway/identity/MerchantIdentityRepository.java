package com.autocare.platform.gateway.identity;

import java.util.Optional;

public interface MerchantIdentityRepository {
    record Merchant(long staffId, long merchantId, String role, String staffStatus,
                    boolean staffDeleted, int merchantStatus, boolean merchantDeleted,
                    String phone, String passwordHash) {
        /** 店长（MERCHANT）与店员（STAFF）共用门店账号登录与刷新口径。 */
        public boolean active() {
            return ("MERCHANT".equals(role) || "STAFF".equals(role)) && "ACTIVE".equals(staffStatus)
                && !staffDeleted && merchantStatus == 1 && !merchantDeleted;
        }
        public boolean manager() {
            return "MERCHANT".equals(role);
        }
    }

    Optional<Merchant> byAccount(String account);
    Optional<Merchant> byId(long staffId);
    void issueSmsCode(long staffId, String phone, String hash, Runnable deliver);
    boolean consumeSmsCode(long staffId, String phone, String code);
}
