package com.autocare.platform.gateway.identity;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Call inside the identity write transaction: merchant, staff, then bindings. */
final class TechnicianIdentityLocks {
    static void staff(JdbcTemplate jdbc,long id){
        var location=jdbc.queryForList("SELECT merchant_id FROM staff_account WHERE id=? AND role='TECHNICIAN'",id);
        if(location.isEmpty() || location.get(0).get("merchant_id")==null)throw unavailable();
        long shop=((Number)location.get(0).get("merchant_id")).longValue();
        if(jdbc.queryForList("SELECT id FROM merchant WHERE id=? FOR UPDATE",shop).isEmpty()
            || jdbc.queryForList("SELECT id FROM staff_account WHERE id=? AND merchant_id=? AND role='TECHNICIAN' FOR UPDATE",id,shop).isEmpty())throw unavailable();
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"员工或商家不可用");}
    private TechnicianIdentityLocks(){}
}
