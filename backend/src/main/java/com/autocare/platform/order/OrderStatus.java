package com.autocare.platform.order;

import java.util.*;

/**
 * 订单履约状态的唯一权威定义。
 *
 * <p>取值、合法迁移、动作与目标状态的映射只在这里声明。数据库里的 {@code order.status}
 * 是结果，不是规则来源；前端只能请求"动作"，不能提交目标状态
 * （Spec §2.4：不允许前端直接设置状态）。
 *
 * <p>本类不依赖 Spring，也不访问数据库，便于对矩阵做穷举测试。
 */
public final class OrderStatus {
    /** 待支付；创建后 15 分钟或时段开始（取早）过期。 */
    public static final String PENDING_PAYMENT="PENDING_PAYMENT";
    /** 已付待接车。 */
    public static final String PAID="PAID";
    /** 已接车，等待车主确认与派工。 */
    public static final String RECEIVED="RECEIVED";
    /** 施工中。 */
    public static final String IN_SERVICE="IN_SERVICE";
    /** 施工完成待核销。 */
    public static final String PENDING_VERIFY="PENDING_VERIFY";
    /** 已完成；不可逆。 */
    public static final String COMPLETED="COMPLETED";
    /** 已取消/已关闭；不可逆。 */
    public static final String CLOSED="CLOSED";
    /** 争议中；解决后回到争议前的状态。 */
    public static final String DISPUTED="DISPUTED";

    /** 全部状态。列表筛选白名单的唯一来源。 */
    public static final Set<String> ALL=Set.of(PENDING_PAYMENT,PAID,RECEIVED,IN_SERVICE,PENDING_VERIFY,COMPLETED,CLOSED,DISPUTED);

    /** 商家侧动作：接车。 */
    public static final String RECEIVE="RECEIVE";
    /** 商家侧动作：开始施工（须车主已确认且已派工）。 */
    public static final String START_SERVICE="START_SERVICE";
    /** 商家侧动作：施工完成，送核销。 */
    public static final String FINISH_SERVICE="FINISH_SERVICE";
    /** 商家侧动作：核销成立，订单完成。 */
    public static final String COMPLETE="COMPLETE";

    /**
     * 完整迁移矩阵：目标状态 → 允许的来源状态集合。
     * 未列出的组合一律拒绝；{@code DISPUTED} 的回退由争议解决流程写入
     * （见订单状态迁移审计的上一状态），不经商家动作。
     */
    private static final Map<String,Set<String>> TRANSITIONS=Map.of(
        PENDING_PAYMENT,Set.of(),
        PAID,Set.of(PENDING_PAYMENT),
        RECEIVED,Set.of(PAID),
        IN_SERVICE,Set.of(RECEIVED),
        PENDING_VERIFY,Set.of(IN_SERVICE),
        COMPLETED,Set.of(PENDING_VERIFY),
        CLOSED,Set.of(PENDING_PAYMENT,PAID,RECEIVED,IN_SERVICE,PENDING_VERIFY),
        DISPUTED,Set.of(PAID,RECEIVED,IN_SERVICE,PENDING_VERIFY));

    /** 动作 → 目标状态、要求的来源状态、审计动作名。 */
    private record Move(String target,Set<String> sources,String audit){}
    private static final Map<String,Move> MOVES=Map.of(
        RECEIVE,new Move(RECEIVED,Set.of(PAID),"ORDER_CHECK_IN"),
        START_SERVICE,new Move(IN_SERVICE,Set.of(RECEIVED),"ORDER_SERVICE_START"),
        FINISH_SERVICE,new Move(PENDING_VERIFY,Set.of(IN_SERVICE),"ORDER_SERVICE_FINISH"),
        COMPLETE,new Move(COMPLETED,Set.of(PENDING_VERIFY),"ORDER_COMPLETE"));
    /** 商家动作的稳定展示顺序，与履约先后一致。 */
    private static final List<String> ACTION_ORDER=List.of(RECEIVE,START_SERVICE,FINISH_SERVICE,COMPLETE);

    private OrderStatus(){}

    // Map.of/Set.of 返回的不可变集合在 get/contains 传入 null 时会抛 NPE，
    // 而这些方法会被参数校验和测试直接调用，因此统一先做 null 短路。

    /** 是否为已声明状态。 */
    public static boolean known(String status){return status!=null && ALL.contains(status);}

    /** 是否为已声明动作。 */
    public static boolean action(String action){return action!=null && MOVES.containsKey(action);}

    /** 该动作映射到的目标状态；未声明的动作返回 {@code null}。 */
    public static String target(String action){if(action==null)return null;var move=MOVES.get(action);return move==null?null:move.target();}

    /** 该动作的审计动作名；未声明的动作返回 {@code null}。 */
    public static String audit(String action){if(action==null)return null;var move=MOVES.get(action);return move==null?null:move.audit();}

    /** {@code from → to} 是否在矩阵内。自反迁移不是合法迁移，另按"无状态变化"处理。 */
    public static boolean can(String from,String to){
        if(from==null||to==null)return false;
        Set<String> sources=TRANSITIONS.get(to);
        return sources!=null && !from.equals(to) && sources.contains(from);
    }

    /** 当前状态下商家可请求的动作，按履约先后排序；不含前置条件判定。 */
    public static List<String> actions(String status){
        if(status==null)return List.of();
        return ACTION_ORDER.stream().filter(action->!RECEIVE.equals(action)&&MOVES.get(action).sources().contains(status)).toList();
    }
}
