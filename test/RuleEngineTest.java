import com.hsbcpulse.cashbook.pro.Cashback;
import com.hsbcpulse.cashbook.pro.MiniJson;
import com.hsbcpulse.cashbook.pro.Order;
import com.hsbcpulse.cashbook.pro.RewardActivity;
import com.hsbcpulse.cashbook.pro.RuleEngine;
import com.hsbcpulse.cashbook.pro.RuleSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎测试：验证「规则来自服务端」后的解析、校验、生效期、额度与注入行为。
 * 纯 JVM，不依赖 Android。
 */
public class RuleEngineTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        parseBuiltin();
        rejectBadRules();
        activityWindow();
        customRate();
        caps();
        optInAndConfirm();
        multiActivity();
        injection();
        jsonBasics();

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) System.exit(1);
    }

    // ---------- 内置规则 ----------

    private static void parseBuiltin() {
        RuleSet rs = RuleSet.builtin();
        check("内置规则版本", rs.version, "builtin-1.5.0");
        check("内置活动数量", rs.activities.size(), 3);
        check("内置基础费率", rs.baseRate, 0.004);
        check("内置餐饮门槛", rs.diningThreshold, 1200.0);
        check("内置支付额度", Cashback.payCap(), 80000.0);
        check("内置赏世界额度", Cashback.worldCap(), 100000.0);
        check("内置餐饮月额度", Cashback.diningCap(), 80.0);
        check("内置有报名活动", rs.hasOptInActivity(), true);
        // 与旧常量一致（回归基线）
        check("PAY_CAP 常量一致", Cashback.PAY_CAP, rs.findById("pay_bonus").capAmount);
    }

    // ---------- 脏数据必须被拒绝 ----------

    private static void rejectBadRules() {
        check("拒绝 schemaVersion 2", reject(rules("2", payAct("p", 0.02, "2026-01-01", "2026-12-31", ""))), true);
        check("拒绝空活动列表", reject(rules("1", "")), true);
        check("拒绝负费率", reject(rules("1", payAct("p", -0.1, "2026-01-01", "2026-12-31", ""))), true);
        check("拒绝未知类型", reject(rules("1", act("p", "UNKNOWN", 0.02, "2026-01-01", "2026-12-31", ""))), true);
        check("拒绝重复 id", reject(rules("1",
                payAct("dup", 0.02, "2026-01-01", "2026-12-31", "") + "," +
                        payAct("dup", 0.01, "2026-01-01", "2026-12-31", ""))), true);
        check("拒绝错误日期格式", reject(rules("1", payAct("p", 0.02, "2026/01/01", "2026-12-31", ""))), true);
        check("拒绝结束早于开始", reject(rules("1", payAct("p", 0.02, "2026-12-31", "2026-01-01", ""))), true);
        check("接受合法规则", reject(rules("1", payAct("p", 0.02, "2026-01-01", "2026-12-31", ""))), false);
    }

    // ---------- 生效期 ----------

    private static void activityWindow() {
        // 未来活动：提前下发，但今天不生效
        RuleSet future = RuleSet.parse(rules("1", payAct("future", 0.10, "2099-01-01", "2099-12-31", "")));
        check("未来活动已下发", future.findById("future") != null, true);
        check("未来活动标记 startsLater", future.findById("future").startsLater("2026-11-10"), true);
        double cbFuture = RuleEngine.cashbackOf(order(100, "2026-11-10", Order.PAY_APPLE, false, false),
                future, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        check("未来活动当期不生效", cbFuture, 0.4);

        // 过期活动：不再生效
        RuleSet expired = RuleSet.parse(rules("1", payAct("old", 0.10, "2020-01-01", "2020-12-31", "")));
        check("过期活动标记 expired", expired.findById("old").expiredOn("2026-11-10"), true);
        double cbOld = RuleEngine.cashbackOf(order(100, "2026-11-10", Order.PAY_APPLE, false, false),
                expired, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        check("过期活动不生效", cbOld, 0.4);

        // 区间内首尾都含
        RuleSet edge = RuleSet.parse(rules("1", payAct("edge", 0.02, "2026-11-01", "2026-11-30", "")));
        check("区间首日生效", RuleEngine.cashbackOf(order(100, "2026-11-01", Order.PAY_APPLE, false, false),
                edge, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 2.4);
        check("区间末日生效", RuleEngine.cashbackOf(order(100, "2026-11-30", Order.PAY_APPLE, false, false),
                edge, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 2.4);
        check("区间外不生效", RuleEngine.cashbackOf(order(100, "2026-12-01", Order.PAY_APPLE, false, false),
                edge, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0.4);
    }

    // ---------- 自定义费率 ----------

    private static void customRate() {
        RuleSet rs = RuleSet.parse(rules("1", payAct("p", 0.05, "2026-01-01", "2026-12-31", "")));
        check("自定义 5% 支付加成", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_APPLE, false, false),
                rs, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 5.4);
        // 未限定 payMethods 时对所有支付方式生效（含未选择）
        check("不限支付方式时全部适用", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_NONE, false, false),
                rs, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 5.4);

        // 限定支付方式后，名单外只享基础
        RuleSet only = RuleSet.parse(rules("1",
                payAct("p", 0.05, "2026-01-01", "2026-12-31", "\"payMethods\":[\"applepay\"]")));
        check("限定名单外只享基础", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_NONE, false, false),
                only, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0.4);
    }

    // ---------- 额度 ----------

    private static void caps() {
        // 年度签账额度 1000，费率 5%，两笔 800
        String a = "{\"id\":\"cap\",\"name\":\"封顶测试\",\"type\":\"PAY_BONUS\",\"rate\":0.05,"
                + "\"startsOn\":\"2026-01-01\",\"endsOn\":\"2026-12-31\","
                + "\"capScope\":\"YEAR\",\"capAmount\":1000,\"capLimitType\":\"SPEND\","
                + "\"payMethods\":[\"applepay\"]}";
        RuleSet rs = RuleSet.parse(rules("1", a));
        List<Order> orders = new ArrayList<>();
        Order o1 = order(800, "2026-01-10", Order.PAY_APPLE, false, false); o1.id = 1;
        Order o2 = order(800, "2026-02-10", Order.PAY_APPLE, false, false); o2.id = 2;
        orders.add(o1); orders.add(o2);
        Map<Long, Cashback.OrderResult> r = RuleEngine.computeYear(orders, rs, false);
        // o1: base 3.2 + 800*5% = 40 → 43.2
        check("年度额度第1笔全额", r.get(1L).cashback, 43.2);
        // o2: 仅剩 200 额度 → 3.2 + 10 = 13.2
        check("年度额度第2笔截断", r.get(2L).cashback, 13.2);

        // 月度返现额度 10，餐饮 3%，1000 → 30 截断到 10
        String d = "{\"id\":\"dcap\",\"name\":\"餐饮封顶\",\"type\":\"DINING\",\"rate\":0.03,"
                + "\"startsOn\":\"2026-01-01\",\"endsOn\":\"2026-12-31\","
                + "\"capScope\":\"MONTH\",\"capAmount\":10,\"capLimitType\":\"REBATE\","
                + "\"minMonthlySpend\":1200,\"diningOnly\":true}";
        RuleSet dr = RuleSet.parse(rules("1", d));
        double cb = RuleEngine.cashbackOf(order(1000, "2026-11-01", Order.PAY_NONE, false, true),
                dr, true, false, Double.MAX_VALUE, Double.MAX_VALUE, 10.0);
        check("月度返现额度截断", cb, 4.0 + 10.0);
    }

    // ---------- 报名与确认 ----------

    private static void optInAndConfirm() {
        String w = "{\"id\":\"w\",\"name\":\"赏世界\",\"type\":\"SHOP_WORLD\",\"rate\":0.02,"
                + "\"startsOn\":\"2026-01-01\",\"endsOn\":\"2026-12-31\","
                + "\"capScope\":\"YEAR\",\"capAmount\":100000,\"capLimitType\":\"SPEND\","
                + "\"excludeMethods\":[\"alipaywechat\"],\"confirmMethods\":[\"alipaywechat\"],"
                + "\"requiresOptIn\":true}";
        RuleSet rs = RuleSet.parse(rules("1", w));
        check("未报名不享赏世界", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_NONE, false, false),
                rs, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0.4);
        check("已报名享赏世界", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_NONE, false, false),
                rs, false, true, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 2.4);
        check("支付宝未确认不参与", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_ALIPAY_WECHAT, false, false),
                rs, false, true, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0.4);
        check("支付宝确认后参与", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_ALIPAY_WECHAT, true, false),
                rs, false, true, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 2.4);

        // 支付方式白名单
        String p = "{\"id\":\"p\",\"name\":\"仅云闪付\",\"type\":\"PAY_BONUS\",\"rate\":0.02,"
                + "\"startsOn\":\"2026-01-01\",\"endsOn\":\"2026-12-31\",\"payMethods\":[\"unionqr\"]}";
        RuleSet pr = RuleSet.parse(rules("1", p));
        check("白名单内生效", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_UNION_QR, false, false),
                pr, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 2.4);
        check("白名单外不生效", RuleEngine.cashbackOf(
                order(100, "2026-11-01", Order.PAY_APPLE, false, false),
                pr, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), 0.4);
    }

    // ---------- 同类活动叠加 ----------

    private static void multiActivity() {
        String a1 = payAct("p1", 0.02, "2026-01-01", "2026-12-31", "");
        String a2 = payAct("p2", 0.01, "2026-01-01", "2026-12-31", "");
        RuleSet rs = RuleSet.parse(rules("1", a1 + "," + a2));
        double[] comp = RuleEngine.components(order(100, "2026-11-01", Order.PAY_APPLE, false, false),
                rs, false, false, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        check("同类活动合并到同一分项", comp[1], 3.0);
        check("两个活动总数", rs.activities.size(), 2);
    }

    // ---------- 注入 Cashback ----------

    private static void injection() {
        Order o = order(100, "2026-11-01", Order.PAY_APPLE, false, false);
        check("注入前走内置规则", Cashback.cashbackOf(o, false, false), 2.4);

        RuleSet custom = RuleSet.parse(rules("1", payAct("p", 0.05, "2026-01-01", "2026-12-31", "")));
        long genBefore = Cashback.rulesGeneration();
        Cashback.setRules(custom);
        check("注入后走服务端规则", Cashback.cashbackOf(o, false, false), 5.4);
        check("规则代次递增", Cashback.rulesGeneration() > genBefore, true);
        check("阈值为服务端值", Cashback.threshold(), 1200.0);

        Cashback.setRules(RuleSet.builtin());
        check("恢复内置规则", Cashback.cashbackOf(o, false, false), 2.4);
    }

    // ---------- JSON 基础 ----------

    private static void jsonBasics() {
        check("百分比 2%", RuleEngine.pct(0.02), "2");
        check("百分比 0.4%", RuleEngine.pct(0.004), "0.4");
        check("百分比 1.5%", RuleEngine.pct(0.015), "1.5");
        Object v = MiniJson.parse("{\"a\":[1,true,\"x\\n\"],\"b\":null}");
        Map<String, Object> m = MiniJson.asMap(v);
        check("解析数字", MiniJson.num(m, "a", 0) == 0, true);
        check("解析空值", m.get("b") == null, true);
        check("解析字符串转义", MiniJson.asList(m.get("a")).get(2), "x\n");
    }

    // ---------- 构造工具 ----------

    private static String rules(String schema, String activities) {
        return "{\"schemaVersion\":" + schema + ",\"version\":\"test\",\"baseRate\":0.004,"
                + "\"diningThreshold\":1200,\"activities\":[" + activities + "]}";
    }

    private static String act(String id, String type, double rate, String start, String end, String extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"type\":\"" + type + "\","
                + "\"rate\":" + rate + ",\"startsOn\":\"" + start + "\",\"endsOn\":\"" + end + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String payAct(String id, double rate, String start, String end, String extra) {
        return act(id, "PAY_BONUS", rate, start, end, extra);
    }

    private static boolean reject(String json) {
        try {
            RuleSet.parse(json);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static Order order(double amount, String date, String pay, boolean confirmed, boolean dining) {
        Order o = new Order();
        o.amount = amount;
        o.date = date;
        o.payMethod = pay;
        o.payConfirmed = confirmed;
        o.dining = dining;
        return o;
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        report(name, ok, String.valueOf(expected), String.valueOf(actual));
    }

    private static void check(String name, double actual, double expected) {
        boolean ok = Math.abs(actual - expected) < 0.005;
        report(name, ok, String.valueOf(expected), String.valueOf(actual));
    }

    private static void check(String name, int actual, int expected) {
        report(name, actual == expected, String.valueOf(expected), String.valueOf(actual));
    }

    private static void report(String name, boolean ok, String expected, String actual) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " expected " + expected + " got " + actual);
        }
    }
}
