package com.autocare.platform.order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public class ReservationConflict extends ResponseStatusException {
    public final int code;
    public ReservationConflict(int code,String reason){super(HttpStatus.CONFLICT,reason);this.code=code;}
}
