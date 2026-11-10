import com.hsbcpulse.cashbook.pro.Order;
import com.hsbcpulse.cashbook.pro.Cashback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 纯 JVM 逻辑测试：验证返现计算规则（v2 支付方式单选 + 赏世界年度开关 + v1.5 年度额度）。 */
public class LogicTest {
    static int pass = 0, fail = 0;

    static void check(String name, double actual, double expected) {
        if (Math.abs(actual - expected) < 0.005) {
            pass++;
            System.out.println("PASS " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("FAIL " + name + " expected " + expected + " got " + actual);
        }
    }

    static void check(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (ok) {
            pass++;
            System.out.println("PASS " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("FAIL " + name + " expected " + expected + " got " + actual);
        }
    }

    static Order order(double amount, String pay, boolean confirmed, boolean dining) {
        Order o = new Order();
        o.amount = amount;
        o.payMethod = pay;
        o.payConfirmed = confirmed;
        o.dining = dining;
        return o;
    }

    public static void main(String[] args) {
        // 基础 0.4%
        check("base 0.4% on 100", Cashback.cashbackOf(order(100, Order.PAY_NONE, false, false), false, false), 0.4);

        // Apple Pay 自动 +2%
        check("applepay auto +2%", Cashback.cashbackOf(order(100, Order.PAY_APPLE, false, false), false, false), 2.4);

        // 云闪付QR 自动 +2%
        check("unionqr auto +2%", Cashback.cashbackOf(order(100, Order.PAY_UNION_QR, false, false), false, false), 2.4);

        // 支付宝/微信 无加成
        check("alipaywechat no bonus", Cashback.cashbackOf(order(100, Order.PAY_ALIPAY_WECHAT, false, false), false, false), 0.4);

        // 在线支付/实体卡 无加成
        check("online_card no bonus", Cashback.cashbackOf(order(100, Order.PAY_ONLINE_CARD, false, false), false, false), 0.4);

        // 跳转云闪付：未确认无加成
        check("jump_union unconfirmed", Cashback.cashbackOf(order(100, Order.PAY_JUMP_UNION, false, false), false, false), 0.4);

        // 跳转云闪付：自行确认 +2%
        check("jump_union confirmed", Cashback.cashbackOf(order(100, Order.PAY_JUMP_UNION, true, false), false, false), 2.4);

        // 旧值映射：wechat_union / app_union → jumpunion；online / physical → onlinecard
        check("legacy wechat_union maps", Order.normalizePayMethod("wechat_union").equals(Order.PAY_JUMP_UNION), true);
        check("legacy app_union maps", Order.normalizePayMethod("app_union").equals(Order.PAY_JUMP_UNION), true);
        check("legacy online maps", Order.normalizePayMethod("online").equals(Order.PAY_ONLINE_CARD), true);
        check("legacy physical maps", Order.normalizePayMethod("physical").equals(Order.PAY_ONLINE_CARD), true);

        // 非跳转类即使误勾确认也不加成（支付宝/微信 + confirmed）
        check("alipaywechat confirm ignored", Cashback.cashbackOf(order(100, Order.PAY_ALIPAY_WECHAT, true, false), false, false), 0.4);

        // 赏世界年度报名 +2%
        check("shopworld active", Cashback.cashbackOf(order(100, Order.PAY_NONE, false, false), false, true), 2.4);

        // 赏世界 + Apple Pay = 0.4+2+2
        check("shopworld + applepay", Cashback.cashbackOf(order(100, Order.PAY_APPLE, false, false), false, true), 4.4);

        // 餐饮未满额不加 3%
        check("dining under threshold", Cashback.cashbackOf(order(100, Order.PAY_NONE, false, true), false, false), 0.4);

        // 餐饮满额 +3%
        check("dining over threshold", Cashback.cashbackOf(order(100, Order.PAY_NONE, false, true), true, false), 3.4);

        // 全加成：applepay + 赏世界 + 餐饮满额 = 0.4+2+2+3 = 7.4%
        check("full combo", Cashback.cashbackOf(order(100, Order.PAY_APPLE, false, true), true, true), 7.4);

        // 人民币 1:1 同样计算：128.5 * 3.4%
        Order o8 = order(128.5, Order.PAY_NONE, false, true);
        o8.currency = "CNY";
        check("cny dining over threshold", Cashback.cashbackOf(o8, true, false), 128.5 * 0.034);

        // 跳转云闪付自行确认 + 赏世界 = 4.4%
        check("confirm + shopworld", Cashback.cashbackOf(order(50, Order.PAY_JUMP_UNION, true, false), false, true), 2.2);

        // 部分退款按净额计算：100 退 40 → 净 60 × 0.4% = 0.24
        Order r1 = order(100, Order.PAY_NONE, false, false);
        r1.refundAmount = 40;
        check("partial refund net cashback", Cashback.cashbackOf(r1, false, false), 0.24);

        // 全额退款 → 返现 0
        Order r2 = order(100, Order.PAY_NONE, false, false);
        r2.refundAmount = 100;
        check("full refund zero cashback", Cashback.cashbackOf(r2, false, false), 0.0);

        // 退款净额方法
        Order r3 = order(200, Order.PAY_NONE, false, false);
        r3.refundAmount = 50;
        check("netAmount = 150", r3.netAmount(), 150.0);

        // components 分项：applepay+赏世界 100 → base 0.4 / pay 2.0 / world 2.0 / dining 0
        double[] comp = Cashback.components(order(100, Order.PAY_APPLE, false, false), false, true);
        check("comp base", comp[0], 0.4);
        check("comp pay", comp[1], 2.0);
        check("comp world", comp[2], 2.0);
        check("comp dining", comp[3], 0.0);

        // ===== v1.5.0 年度额度测试 =====

        // shopWorldEligible：支付宝/微信默认不参与赏世界
        Order aw = order(100, Order.PAY_ALIPAY_WECHAT, false, false);
        check("alipay not shopworld eligible", aw.shopWorldEligible(), false);
        Order awc = order(100, Order.PAY_ALIPAY_WECHAT, true, false);
        check("alipay confirmed shopworld eligible", awc.shopWorldEligible(), true);
        check("apple shopworld eligible", order(100, Order.PAY_APPLE, false, false).shopWorldEligible(), true);
        check("online shopworld eligible", order(100, Order.PAY_ONLINE_CARD, false, false).shopWorldEligible(), true);

        // 支付宝/微信未确认：赏世界不生效
        check("alipay unconfirmed no world", Cashback.cashbackOf(order(100, Order.PAY_ALIPAY_WECHAT, false, false), false, true), 0.4);
        // 支付宝/微信已确认：赏世界生效（但无支付加成）
        check("alipay confirmed gets world", Cashback.cashbackOf(order(100, Order.PAY_ALIPAY_WECHAT, true, false), false, true), 2.4);

        // 支付额度封顶
        // 5000 净额，payCapRemaining=1000 → base 20 + pay 1000*2%=20 = 40
        check("pay cap partial", Cashback.cashbackOf(order(5000, Order.PAY_APPLE, false, false), false, false, 1000, 999999), 40.0);
        // payCapRemaining=0 → 只有 base
        check("pay cap exhausted", Cashback.cashbackOf(order(5000, Order.PAY_APPLE, false, false), false, false, 0, 999999), 20.0);
        // 赏世界额度封顶
        check("world cap partial", Cashback.cashbackOf(order(5000, Order.PAY_NONE, false, false), false, true, 999999, 1000), 20.0 + 20.0);
        check("world cap exhausted", Cashback.cashbackOf(order(5000, Order.PAY_NONE, false, false), false, true, 999999, 0), 20.0);

        // computeYear 批量计算：3 笔 ApplePay 各 30,000 = 90,000 → 前 80,000 享支付加成
        List<Order> yearOrders = new ArrayList<>();
        Order y1 = order(30000, Order.PAY_APPLE, false, false); y1.id = 1; y1.date = "2026-01-15";
        Order y2 = order(30000, Order.PAY_APPLE, false, false); y2.id = 2; y2.date = "2026-03-15";
        Order y3 = order(30000, Order.PAY_APPLE, false, false); y3.id = 3; y3.date = "2026-06-15";
        yearOrders.add(y1); yearOrders.add(y2); yearOrders.add(y3);
        Map<Long, Cashback.OrderResult> results = Cashback.computeYear(yearOrders, false);
        // y1: full 30000 in cap → base 120 + pay 600 = 720
        check("year y1 full cap", results.get(1L).cashback, 720.0);
        // y2: full 30000 in cap (total 60000 < 80000) → 720
        check("year y2 full cap", results.get(2L).cashback, 720.0);
        // y3: only 20000 in cap (80000-60000), 10000 overflow → base 120 + pay 20000*2%=400 = 520
        check("year y3 partial cap", results.get(3L).cashback, 520.0);

        // computeYear 赏世界额度：100,000 上限
        List<Order> worldOrders = new ArrayList<>();
        Order w1 = order(60000, Order.PAY_NONE, false, false); w1.id = 10; w1.date = "2026-01-15";
        Order w2 = order(60000, Order.PAY_NONE, false, false); w2.id = 11; w2.date = "2026-03-15";
        worldOrders.add(w1); worldOrders.add(w2);
        Map<Long, Cashback.OrderResult> wResults = Cashback.computeYear(worldOrders, true);
        // w1: full in cap → base 240 + world 1200 = 1440
        check("world y1 full cap", wResults.get(10L).cashback, 1440.0);
        // w2: only 40000 in cap (100000-60000) → base 240 + world 40000*2%=800 = 1040
        check("world y2 partial cap", wResults.get(11L).cashback, 1040.0);

        // capUsageBefore：同日期计入（>0 边界），excludeId 排除自身
        // y1(01-15: 30K) + y2(03-15: 30K) + y3(06-15: 30K→仅20K入额) = 80K
        double[] caps = Cashback.capUsageBefore(yearOrders, "2026-06-15", -1, false, false);
        check("cap usage incl same-day", caps[0], 80000.0);
        check("cap usage world zero", caps[1], 0.0);

        // 排除自身（编辑场景）：排除 y3 → 只有 y1+y2 = 60K
        double[] caps2 = Cashback.capUsageBefore(yearOrders, "2026-06-15", 3, false, false);
        check("cap usage exclude self", caps2[0], 60000.0);

        // 编辑 y2 → y1 计入，y3 超出日期 break → 30K
        double[] caps3 = Cashback.capUsageBefore(yearOrders, "2026-03-15", 2, false, false);
        check("cap usage edit y2", caps3[0], 30000.0);

        // 同日期多笔累计（bug 修复验证）：同一天 5 笔 ApplePay 各 20,000 = 100,000
        List<Order> sameDay = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Order od = order(20000, Order.PAY_APPLE, false, false);
            od.id = 100 + i;
            od.date = "2026-11-10";
            sameDay.add(od);
        }
        // 创建第 6 笔（新订单）：前 5 笔全部计入额度 → 已用 80,000（capped），剩余 0
        double[] sameCaps = Cashback.capUsageBefore(sameDay, "2026-11-10", -1, false, false);
        check("same-day cap accumulates", sameCaps[0], 80000.0);
        // computeYear 也验证：第 5 笔应该只能享 20,000×2% 的加成（前面已用 80K）
        Map<Long, Cashback.OrderResult> sdResults = Cashback.computeYear(sameDay, false);
        // 1st: 20K base80+pay400=480, 2nd: 480, 3rd: 480, 4th: 480
        // 5th: eligible=min(20K, 0)=0 → base 80 only = 80
        check("same-day 5th capped", sdResults.get(104L).cashback, 80.0);
        check("same-day 4th ok", sdResults.get(103L).cashback, 480.0);

        // ===== 餐饮月度上限 80 元 =====

        // 单笔餐饮 3000（满额）→ 3000×3% = 90 > 80 → 只享 80
        check("dining cap 80", Cashback.cashbackOf(
                order(3000, Order.PAY_NONE, false, true), true, false,
                Cashback.PAY_CAP, Cashback.WORLD_CAP, Cashback.DINING_MONTHLY_CAP), 12.0 + 80.0);
        // 单笔餐饮 1000（满额）→ 1000×3% = 30 < 80 → 全享30
        check("dining under cap", Cashback.cashbackOf(
                order(1000, Order.PAY_NONE, false, true), true, false,
                Cashback.PAY_CAP, Cashback.WORLD_CAP, Cashback.DINING_MONTHLY_CAP), 4.0 + 30.0);
        // 餐饮剩余额度仅 10 → 本单最多享 10
        check("dining partial cap", Cashback.cashbackOf(
                order(2000, Order.PAY_NONE, false, true), true, false,
                Cashback.PAY_CAP, Cashback.WORLD_CAP, 10.0), 8.0 + 10.0);
        // 餐饮额度用完 → 餐饮加成 0
        check("dining exhausted", Cashback.cashbackOf(
                order(2000, Order.PAY_NONE, false, true), true, false,
                Cashback.PAY_CAP, Cashback.WORLD_CAP, 0.0), 8.0);

        // computeYear 餐饮月度累计：同月 3 笔各 2000 餐饮（满额）→ 每笔 60 加成
        // 第 1 笔 60、第 2 笔 20（80-60）、第 3 笔 0（80 用完）
        List<Order> diningOrders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Order od = order(2000, Order.PAY_NONE, false, true);
            od.id = 200 + i;
            od.date = "2026-11-1" + (i + 1);
            diningOrders.add(od);
        }
        // 月总额 6000 > 1200，thresholdMet = true
        Map<Long, Cashback.OrderResult> dResults = Cashback.computeYear(diningOrders, false);
        // 1st: base 8 + dining 60 = 68
        check("dining month 1st", dResults.get(200L).cashback, 68.0);
        // 2nd: base 8 + dining 20 = 28
        check("dining month 2nd", dResults.get(201L).cashback, 28.0);
        // 3rd: base 8 + dining 0 = 8
        check("dining month 3rd capped", dResults.get(202L).cashback, 8.0);

        // 餐饮月度 capUsageBefore：同月 2 笔各 2000 餐饮已用 60+20=80
        double[] dCaps = Cashback.capUsageBefore(diningOrders, "2026-11-13", -1, false, true);
        check("dining usage before 3rd", dCaps[2], 80.0);

        System.out.println("---- " + pass + " passed, " + fail + " failed ----");
        if (fail > 0) System.exit(1);
    }
}
