package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条返现活动（服务端下发的规则单元）。
 *
 * 活动不再写死在代码里：费率、有效期、额度、资格条件全部来自规则文件。
 * 未到开始日期的活动会被保留但不生效（支持提前下发），过了结束日期自动失效。
 */
public class RewardActivity {

    /** 活动类型 —— 决定返现分项归到哪一格（见 RuleEngine 的 COMP_*）。 */
    public static final String TYPE_PAY_BONUS = "PAY_BONUS";
    public static final String TYPE_SHOP_WORLD = "SHOP_WORLD";
    public static final String TYPE_DINING = "DINING";

    /** 额度作用域。 */
    public static final String SCOPE_NONE = "NONE";
    public static final String SCOPE_YEAR = "YEAR";
    public static final String SCOPE_MONTH = "MONTH";

    /** 额度限制对象：SPEND=限制可享加成的签账额；REBATE=限制返现金额本身。 */
    public static final String LIMIT_SPEND = "SPEND";
    public static final String LIMIT_REBATE = "REBATE";

    public String id = "";
    public String name = "";
    public String type = TYPE_PAY_BONUS;
    /** 加成费率，如 0.02 表示 2%。 */
    public double rate = 0;
    /** 生效区间（含首尾），yyyy-MM-dd。 */
    public String startsOn = "";
    public String endsOn = "";
    /** 额度：作用域 / 上限 / 限制对象。 */
    public String capScope = SCOPE_NONE;
    public double capAmount = 0;
    public String capLimitType = LIMIT_SPEND;
    /** 该活动要求当月累计签账达到该值才生效（0=不要求）。 */
    public double minMonthlySpend = 0;
    /** 仅餐饮类订单可享。 */
    public boolean diningOnly = false;
    /** 适用的支付方式；空列表表示不限。 */
    public List<String> payMethods = new ArrayList<>();
    /** 这些支付方式需用户「自行确认」后才适用。 */
    public List<String> confirmMethods = new ArrayList<>();
    /** 这些支付方式排除在外（若同时在 confirmMethods 中，则以确认为准）。 */
    public List<String> excludeMethods = new ArrayList<>();
    /** 是否需要用户报名（如赏世界年度报名）。 */
    public boolean requiresOptIn = false;
    public boolean enabled = true;

    public RewardActivity() {}

    /**
     * 活动在指定日期是否处于有效期（含首尾）。
     * 日期为空/异常时不做日期过滤（兼容历史数据与纯逻辑调用）。
     */
    public boolean appliesOn(String date) {
        if (!enabled) return false;
        if (date == null || date.length() < 10) return true;
        if (!startsOn.isEmpty() && date.compareTo(startsOn) < 0) return false;
        if (!endsOn.isEmpty() && date.compareTo(endsOn) > 0) return false;
        return true;
    }

    /** 支付方式资格判断。 */
    public boolean methodEligible(Order o) {
        String m = Order.normalizePayMethod(o.payMethod);
        if (!payMethods.isEmpty() && !payMethods.contains(m)) return false;
        boolean exempt = confirmMethods.contains(m);
        if (!exempt && excludeMethods.contains(m)) return false;
        if (exempt && !o.payConfirmed) return false;
        return true;
    }

    /** 是否已开始（用于「即将生效」提示）。 */
    public boolean startsLater(String today) {
        return enabled && !startsOn.isEmpty() && today != null && today.compareTo(startsOn) < 0;
    }

    public boolean expiredOn(String today) {
        return !endsOn.isEmpty() && today != null && today.compareTo(endsOn) > 0;
    }
}
