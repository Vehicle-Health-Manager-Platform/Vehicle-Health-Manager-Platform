package com.autocare.platform.order;
import java.time.Clock;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RefundChannels {
    final RefundChannel local;
    public RefundChannels(Environment env,PaymentChannels payments,Clock clock){
        if(env.getProperty("PAYMENT_LOCAL_TEST_ENABLED",Boolean.class,false)){payments.callback();local=new LocalTestRefundChannel(env.getProperty("PAYMENT_LOCAL_TEST_SECRET",""),clock,env.getProperty("PAYMENT_LOCAL_TEST_QUERY_URL",""));}else local=null;
    }
    RefundChannels(RefundChannel channel){local=channel;}
    public RefundChannel get(String channel){if("WECHAT".equals(channel))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"正式微信退款尚未配置");if(!"LOCAL_TEST".equals(channel))throw ReservationInput.bad();if(local==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"测试退款渠道未开放");return local;}
    public RefundChannel callback(){if(local==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"测试退款通知未开放");return local;}
}
