package com.autocare.platform.order;
import java.security.SecureRandom;
import java.util.Locale;
final class AppointmentCodes {
    private static final SecureRandom RANDOM=new SecureRandom();
    static String create(){return String.format(Locale.ROOT,"%06d",RANDOM.nextInt(1000000));}
    private AppointmentCodes(){}
}
