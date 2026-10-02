package com.autocare.platform.gateway.identity;

import java.util.Optional;

public interface IdentityRepository {
    record Owner(long id, String phone, int status, boolean deleted) {}
    record Technician(long id, long staffId, long merchantId, String staffRole, String staffStatus,
                      boolean staffDeleted, int merchantStatus, boolean merchantDeleted) {
        public boolean active() {
            return "TECHNICIAN".equals(staffRole) && "ACTIVE".equals(staffStatus)
                && !staffDeleted && merchantStatus == 1 && !merchantDeleted;
        }
    }

    Optional<Owner> ownerByOpenid(String openid);
    Optional<Owner> ownerById(long id);
    Optional<String> ownerOpenidById(long id);
    Owner bindOwnerPhone(long id, String phone);
    Owner createOwnerOrRead(String openid);
    Optional<Technician> technicianByOpenid(String appId, String openid);
    Optional<Technician> technicianByBindingId(long bindingId);
    Technician bindTechnician(String appId, String openid, String employeeCode);
}
