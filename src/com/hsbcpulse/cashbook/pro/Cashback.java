package com.hsbcpulse.cashbook.pro;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 返现计算门面（v2.1.0 起规则来自 {@link RuleSet}，不再写死在代码里）。
 *
 * 保留原有静态 API 与常量，目的有二：
 * 1) 既有调用点（主页/编辑页/明细页/测试）无需改动；
 * 2) 内置规则与 v1.5.0 数值完全一致，LogicTest 56 项断言即为回归基线。
 *
 * 需要换规则时只调 {@link #setRules(RuleSet)}，无需改这里。
 */
public final class Cashback {

    // 以下常量为「内置规则」的镜像值，供 UI/测试直接引用；
    // 运行期请优先使用 payCap()/worldCap()/diningCap()/threshold()，以跟随服务端规则。
    public static final double BASE_RATE = 0.004;
    public static final double PAY_RATE = 0.02;
    public static final double WORLD_RATE = 0.02;
    public static final double DINING_RATE = 0.03;
    public static final double THRESHOLD = 1200.0;
    public static final double PAY_CAP = 80000.0;
    public static final double WORLD_CAP = 100000.0;
    public static final double DINING_MONTHLY_CAP = 80.0;

    /** 当前生效规则集：默认内置，App 启动后由 RulesStore 注入服务端规则。 */
    private static volatile RuleSet rules = RuleSet.builtin();

    /** 规则代次：每次注入新规则 +1，供 DB 缓存等下游判断是否需要重算。 */
    private static final java.util.concurrent.atomic.AtomicLong RULES_GEN =
            new java.util.concurrent.atomic.AtomicLong();

    private Cashback() {}

    // ---------- 规则注入 ----------

    public static RuleSet rules() {
        return rules;
    }

    public static long rulesGeneration() {
        return RULES_GEN.get();
    }

    /** 注入规则集；传 null 忽略，避免把状态弄坏。 */
    public static void setRules(RuleSet rs) {
        if (rs == null) return;
        rules = rs;
        RULES_GEN.incrementAndGet();
    }

    public static double threshold() {
        return rules.diningThreshold;
    }

    /** 取某类活动的年度签账额度（无则 0）。 */
    private static double spendCapOf(String type) {
        for (RewardActivity a : rules.activities) {
            if (a.type.equals(type) && RewardActivity.SCOPE_YEAR.equals(a.capScope)
                    && RewardActivity.LIMIT_SPEND.equals(a.capLimitType)) {
                return a.capAmount;
            }
        }
        return 0;
    }

    /** 取某类活动的月度返现额度（无则 0）。 */
    private static double rebateCapOf(String type) {
        for (RewardActivity a : rules.activities) {
            if (a.type.equals(type) && RewardActivity.SCOPE_MONTH.equals(a.capScope)
                    && RewardActivity.LIMIT_REBATE.equals(a.capLimitType)) {
                return a.capAmount;
            }
        }
        return 0;
    }

    public static double payCap() { return spendCapOf(RewardActivity.TYPE_PAY_BONUS); }

    public static double worldCap() { return spendCapOf(RewardActivity.TYPE_SHOP_WORLD); }

    public static double diningCap() { return rebateCapOf(RewardActivity.TYPE_DINING); }

    // ---------- 单笔计算 ----------

    public static double cashbackOf(Order o, boolean thresholdMet, boolean shopWorldActive,
                                    double payCapRemaining, double worldCapRemaining,
                                    double diningCapRemaining) {
        return RuleEngine.cashbackOf(o, rules, thresholdMet, shopWorldActive,
                payCapRemaining, worldCapRemaining, diningCapRemaining);
    }

    public static double cashbackOf(Order o, boolean thresholdMet, boolean shopWorldActive) {
        return cashbackOf(o, thresholdMet, shopWorldActive,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public static double cashbackOf(Order o, boolean thresholdMet, boolean shopWorldActive,
                                    double payCapRemaining, double worldCapRemaining) {
        return cashbackOf(o, thresholdMet, shopWorldActive,
                payCapRemaining, worldCapRemaining, Double.MAX_VALUE);
    }

    public static double[] components(Order o, boolean thresholdMet, boolean shopWorldActive,
                                      double payCapRemaining, double worldCapRemaining,
                                      double diningCapRemaining) {
        return RuleEngine.components(o, rules, thresholdMet, shopWorldActive,
                payCapRemaining, worldCapRemaining, diningCapRemaining);
    }

    public static double[] components(Order o, boolean thresholdMet, boolean shopWorldActive) {
        return components(o, thresholdMet, shopWorldActive,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    }

    // ---------- 年度批量计算 ----------

    public static Map<Long, OrderResult> computeYear(List<Order> yearOrders, boolean shopWorldActive) {
        return RuleEngine.computeYear(yearOrders, rules, shopWorldActive);
    }

    // ---------- 额度消耗 ----------

    public static double[] capUsageBefore(List<Order> sortedYearOrders, String date,
                                          long excludeId, boolean shopWorldActive,
                                          boolean monthThresholdMet) {
        return RuleEngine.capUsageBefore(sortedYearOrders, rules, date, excludeId,
                shopWorldActive, monthThresholdMet);
    }

    // ---------- 明细文案 ----------

    public static String breakdown(Order o, boolean thresholdMet, boolean shopWorldActive,
                                   double payCapRemaining, double worldCapRemaining,
                                   double diningCapRemaining) {
        RuleSet rs = rules;
        double net = o.netAmount();
        StringBuilder sb = new StringBuilder();
        if (o.hasRefund()) {
            sb.append(String.format(Locale.US, "净签账 %.2f（原 %.2f − 退款 %.2f）\n",
                    net, o.amount, o.refundAmount));
        }
        sb.append(String.format(Locale.US, "基础 %s%% ＝ %.2f",
                RuleEngine.pct(rs.baseRate), round2(net * rs.baseRate)));

        for (RewardActivity a : rs.activities) {
            if (!a.enabled || !a.appliesOn(o.date)) continue;
            String rate = RuleEngine.pct(a.rate);

            if (RewardActivity.TYPE_PAY_BONUS.equals(a.type)) {
                if (!a.methodEligible(o)) continue;
                String label = o.methodLabel();
                if (Order.PAY_JUMP_UNION.equals(o.payMethod) && o.payConfirmed) {
                    label = "自行确认 · " + label;
                }
                double eligible = RewardActivity.LIMIT_SPEND.equals(a.capLimitType)
                        ? Math.min(net, Math.max(0, payCapRemaining)) : net;
                sb.append(String.format(Locale.US, "\n支付加成 %s%%（%s）＝ %.2f",
                        rate, label, round2(eligible * a.rate)));
                if (a.capAmount > 0) {
                    double used = Math.max(0, a.capAmount - payCapRemaining);
                    if (used > 0 || eligible < net) {
                        sb.append(String.format(Locale.US, "\n  支付额度已用 %,.0f / %,.0f", used, a.capAmount));
                        if (eligible < net) {
                            sb.append("（本单仅 ").append(format(eligible)).append(" 享加成）");
                        }
                    }
                }
            } else if (RewardActivity.TYPE_SHOP_WORLD.equals(a.type)) {
                if (shopWorldActive && a.methodEligible(o)) {
                    double eligible = RewardActivity.LIMIT_SPEND.equals(a.capLimitType)
                            ? Math.min(net, Math.max(0, worldCapRemaining)) : net;
                    sb.append(String.format(Locale.US, "\n赏世界 %s%%（年度已报名）＝ %.2f",
                            rate, round2(eligible * a.rate)));
                    if (a.capAmount > 0) {
                        double used = Math.max(0, a.capAmount - worldCapRemaining);
                        if (used > 0 || eligible < net) {
                            sb.append(String.format(Locale.US, "\n  赏世界额度已用 %,.0f / %,.0f", used, a.capAmount));
                            if (eligible < net) {
                                sb.append("（本单仅 ").append(format(eligible)).append(" 享加成）");
                            }
                        }
                    }
                } else if (shopWorldActive) {
                    sb.append(String.format(Locale.US,
                            "\n赏世界 %s%% — 支付宝/微信默认不参与（可自行确认）", rate));
                } else {
                    sb.append(String.format(Locale.US, "\n赏世界 %s%% — 年度未报名，不生效", rate));
                }
            } else if (RewardActivity.TYPE_DINING.equals(a.type)) {
                if (!o.dining) continue;
                double need = a.minMonthlySpend > 0 ? a.minMonthlySpend : rs.diningThreshold;
                if (thresholdMet) {
                    double bonus = net * a.rate;
                    double eligible = RewardActivity.LIMIT_REBATE.equals(a.capLimitType)
                            ? Math.min(bonus, Math.max(0, diningCapRemaining)) : bonus;
                    sb.append(String.format(Locale.US, "\n餐饮加成 %s%%（当月已满 %,.0f）＝ %.2f",
                            rate, need, round2(eligible)));
                    if (a.capAmount > 0) {
                        double used = Math.max(0, a.capAmount - diningCapRemaining);
                        if (used > 0 || eligible < bonus) {
                            sb.append(String.format(Locale.US, "\n  餐饮月额度已用 %,.0f / %,.0f", used, a.capAmount));
                            if (eligible < bonus) {
                                sb.append("（本单仅 ").append(format(eligible)).append(" 享加成）");
                            }
                        }
                    }
                } else {
                    sb.append(String.format(Locale.US, "\n餐饮加成 %s%% — 当月未满 %,.0f，暂不生效",
                            rate, need));
                }
            }
        }
        return sb.toString();
    }

    public static String breakdown(Order o, boolean thresholdMet, boolean shopWorldActive) {
        return breakdown(o, thresholdMet, shopWorldActive,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
    }

    // ---------- 工具 ----------

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String format(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** 整数金额文案（阈值 1200 → "1200"）。 */
    public static String n0(double v) {
        return String.format(Locale.US, "%.0f", v);
    }

    /** 单笔订单批量计算结果。 */
    public static class OrderResult {
        public double cashback;
        /** [基础, 支付加成, 赏世界, 餐饮] */
        public double[] components = new double[4];
    }
}
