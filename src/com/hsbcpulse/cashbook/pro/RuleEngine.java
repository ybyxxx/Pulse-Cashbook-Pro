package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则驱动版返现计算引擎（纯 JVM，无 Android 依赖）。
 *
 * 与旧实现的区别：费率、额度、有效期、资格条件全部来自 {@link RuleSet}，
 * 新增活动只需服务端加一条记录，客户端不用改代码。
 *
 * 分项固定四格：基础 / 支付加成 / 赏世界 / 餐饮，由活动 type 决定归格，
 * 因此图表与明细页的展示结构保持不变。
 */
public final class RuleEngine {

    public static final int COMP_BASE = 0;
    public static final int COMP_PAY = 1;
    public static final int COMP_WORLD = 2;
    public static final int COMP_DINING = 3;

    private RuleEngine() {}

    // ---------- 批量年度计算 ----------

    /**
     * 按日期升序处理某一年的订单，累计消耗各活动的额度。
     *
     * @param shopWorldOptIn 用户是否已报名需要报名的活动（赏世界年度开关）
     */
    public static Map<Long, Cashback.OrderResult> computeYear(List<Order> orders, RuleSet rs,
                                                              boolean shopWorldOptIn) {
        List<Order> sorted = sortByDate(orders);
        Map<String, Double> monthly = monthlySpend(sorted);
        Map<String, Double> usage = new HashMap<>();
        Map<Long, Cashback.OrderResult> out = new HashMap<>();

        for (Order o : sorted) {
            double net = o.netAmount();
            double[] comp = new double[4];
            comp[COMP_BASE] = net * rs.baseRate;

            double monthTotal = get(monthly, monthOf(o.date));
            for (RewardActivity a : rs.activities) {
                if (!applies(a, o, shopWorldOptIn, monthTotal)) continue;
                comp[slotOf(a.type)] += apply(o, a, net, usage, monthOf(o.date));
            }

            Cashback.OrderResult r = new Cashback.OrderResult();
            r.components = new double[]{
                    round2(comp[0]), round2(comp[1]), round2(comp[2]), round2(comp[3])
            };
            r.cashback = round2(r.components[0] + r.components[1] + r.components[2] + r.components[3]);
            out.put(o.id, r);
        }
        return out;
    }

    // ---------- 单笔计算（编辑页预览） ----------

    public static double cashbackOf(Order o, RuleSet rs, boolean thresholdMet, boolean shopWorldOptIn,
                                    double payRemaining, double worldRemaining, double diningRemaining) {
        double[] c = components(o, rs, thresholdMet, shopWorldOptIn,
                payRemaining, worldRemaining, diningRemaining);
        return round2(c[0] + c[1] + c[2] + c[3]);
    }

    /**
     * 四项构成。remaining 按分项给出（支付 / 赏世界 / 餐饮），
     * 同一分项若有多个活动则按顺序消耗同一份额度。
     */
    public static double[] components(Order o, RuleSet rs, boolean thresholdMet, boolean shopWorldOptIn,
                                      double payRemaining, double worldRemaining, double diningRemaining) {
        double net = o.netAmount();
        double[] comp = new double[4];
        comp[COMP_BASE] = round2(net * rs.baseRate);

        double payLeft = Math.max(0, payRemaining);
        double worldLeft = Math.max(0, worldRemaining);
        double diningLeft = Math.max(0, diningRemaining);

        for (RewardActivity a : rs.activities) {
            if (!a.enabled || !a.appliesOn(o.date)) continue;
            if (a.requiresOptIn && !shopWorldOptIn) continue;
            if (a.diningOnly && !o.dining) continue;
            if (a.minMonthlySpend > 0 && !thresholdMet) continue;
            if (!a.methodEligible(o)) continue;

            int slot = slotOf(a.type);
            double rebate;
            if (RewardActivity.LIMIT_SPEND.equals(a.capLimitType)
                    && !RewardActivity.SCOPE_NONE.equals(a.capScope)) {
                double remain = slot == COMP_PAY ? payLeft : slot == COMP_WORLD ? worldLeft : diningLeft;
                double eligible = Math.min(net, Math.max(0, remain));
                rebate = eligible * a.rate;
                if (slot == COMP_PAY) payLeft -= eligible;
                else if (slot == COMP_WORLD) worldLeft -= eligible;
                else diningLeft -= eligible;
            } else if (!RewardActivity.SCOPE_NONE.equals(a.capScope) && a.capAmount > 0) {
                double remain = slot == COMP_PAY ? payLeft : slot == COMP_WORLD ? worldLeft : diningLeft;
                rebate = Math.min(net * a.rate, Math.max(0, remain));
                if (slot == COMP_PAY) payLeft -= rebate;
                else if (slot == COMP_WORLD) worldLeft -= rebate;
                else diningLeft -= rebate;
            } else {
                rebate = net * a.rate;
            }
            comp[slot] = round2(comp[slot] + rebate);
        }
        return comp;
    }

    // ---------- 编辑前的额度消耗 ----------

    /**
     * 指定日期（含当日）之前的额度消耗。
     *
     * @return [支付加成已用签账, 赏世界已用签账, 当月餐饮已用返现]
     */
    public static double[] capUsageBefore(List<Order> sortedYearOrders, RuleSet rs, String date,
                                          long excludeId, boolean shopWorldOptIn,
                                          boolean monthThresholdMet) {
        List<Order> sorted = sortByDate(sortedYearOrders);
        Map<String, Double> monthly = monthlySpend(sorted);
        Map<String, Double> usage = new HashMap<>();
        String targetMonth = monthOf(date);

        for (Order o : sorted) {
            if (o.id == excludeId) continue;
            if (o.date != null && date != null && o.date.compareTo(date) > 0) break;
            double net = o.netAmount();
            double monthTotal = get(monthly, monthOf(o.date));
            for (RewardActivity a : rs.activities) {
                if (!applies(a, o, shopWorldOptIn, monthTotal)) continue;
                apply(o, a, net, usage, monthOf(o.date));
            }
        }

        double pay = 0, world = 0, dining = 0;
        for (RewardActivity a : rs.activities) {
            if (RewardActivity.SCOPE_NONE.equals(a.capScope)) continue;
            String scopeKey = RewardActivity.SCOPE_MONTH.equals(a.capScope) ? targetMonth : "Y";
            double used = get(usage, a.id + "|" + scopeKey);
            if (RewardActivity.TYPE_PAY_BONUS.equals(a.type)) pay += used;
            else if (RewardActivity.TYPE_SHOP_WORLD.equals(a.type)) world += used;
            else if (RewardActivity.TYPE_DINING.equals(a.type)) dining += used;
        }
        if (!monthThresholdMet) dining = 0;
        return new double[]{pay, world, dining};
    }

    // ---------- 内部 ----------

    private static boolean applies(RewardActivity a, Order o, boolean shopWorldOptIn, double monthTotal) {
        if (!a.enabled || !a.appliesOn(o.date)) return false;
        if (a.requiresOptIn && !shopWorldOptIn) return false;
        if (a.diningOnly && !o.dining) return false;
        if (a.minMonthlySpend > 0 && monthTotal < a.minMonthlySpend) return false;
        return a.methodEligible(o);
    }

    /** 计算单笔活动返现并累计额度占用。 */
    private static double apply(Order o, RewardActivity a, double net,
                                Map<String, Double> usage, String month) {
        double rebate = net * a.rate;
        if (RewardActivity.SCOPE_NONE.equals(a.capScope) || a.capAmount <= 0) return rebate;

        String scopeKey = RewardActivity.SCOPE_MONTH.equals(a.capScope) ? month : "Y";
        String key = a.id + "|" + scopeKey;
        double used = get(usage, key);

        if (RewardActivity.LIMIT_SPEND.equals(a.capLimitType)) {
            double eligible = Math.min(net, Math.max(0, a.capAmount - used));
            rebate = eligible * a.rate;
            usage.put(key, used + eligible);
        } else {
            double allowed = Math.min(rebate, Math.max(0, a.capAmount - used));
            rebate = allowed;
            usage.put(key, used + allowed);
        }
        return rebate;
    }

    static List<Order> sortByDate(List<Order> orders) {
        List<Order> sorted = new ArrayList<>(orders);
        sorted.sort((a, b) -> {
            int cmp = String.valueOf(a.date).compareTo(String.valueOf(b.date));
            if (cmp != 0) return cmp;
            return Long.compare(a.createdAt, b.createdAt);
        });
        return sorted;
    }

    private static Map<String, Double> monthlySpend(List<Order> orders) {
        Map<String, Double> map = new HashMap<>();
        for (Order o : orders) {
            String k = monthOf(o.date);
            map.put(k, get(map, k) + o.netAmount());
        }
        return map;
    }

    static String monthOf(String date) {
        return date != null && date.length() >= 7 ? date.substring(0, 7) : "";
    }

    static int slotOf(String type) {
        if (RewardActivity.TYPE_SHOP_WORLD.equals(type)) return COMP_WORLD;
        if (RewardActivity.TYPE_DINING.equals(type)) return COMP_DINING;
        return COMP_PAY;
    }

    private static double get(Map<String, Double> map, String key) {
        Double v = map.get(key);
        return v == null ? 0 : v;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 费率转百分比文案：0.02 → "2"，0.004 → "0.4"。 */
    public static String pct(double rate) {
        double p = rate * 100;
        if (Math.abs(p - Math.rint(p)) < 1e-9) return String.valueOf((long) Math.rint(p));
        String s = String.format(Locale.US, "%.2f", p);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
