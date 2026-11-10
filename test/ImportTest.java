import com.hsbcpulse.cashbook.pro.Cashback;
import com.hsbcpulse.cashbook.pro.CsvExport;
import com.hsbcpulse.cashbook.pro.ImportParser;
import com.hsbcpulse.cashbook.pro.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 备份恢复测试：导出→解析往返一致性、脏数据跳过、去重键。纯 JVM。 */
public class ImportTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        roundTrip();
        badFiles();
        rowValidation();
        dedupe();
        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) System.exit(1);
    }

    // ---------- 导出→恢复 往返 ----------

    private static void roundTrip() {
        List<Order> origin = new ArrayList<>();
        origin.add(order("2026-11-01", 128.5, 0, "CNY", Order.PAY_APPLE, false, true, "午餐, 含小费", 1001L));
        origin.add(order("2026-10-05", 2000, 300, "HKD", Order.PAY_UNION_QR, true, false, "带\"引号\"\n和换行", 1002L));
        origin.add(order("2026-09-30", 50, 0, "CNY", Order.PAY_ALIPAY_WECHAT, false, false, "", 1003L));

        Map<Long, Cashback.OrderResult> results = Cashback.computeYear(origin, false);
        String json = CsvExport.toJson(origin, results, new LinkedHashMap<Integer, Boolean>(),
                "2026-01-01", "Pulse 主卡", 1763000000000L);

        ImportParser.Result r = ImportParser.parse(json);
        check("往返：解析成功", r.ok, true);
        check("往返：条数一致", r.count(), 3);
        check("往返：无跳过", r.skipped, 0);
        check("往返：卡名", r.cardName, "Pulse 主卡");
        check("往返：下卡日期", r.cardIssueDate, "2026-01-01");
        check("往返：导出时间非空", r.exportedAt.isEmpty(), false);
        check("往返：版本号", r.appVersion, CsvExport.APP_VERSION);

        // 逐字段比对（导出按日期升序，恢复后顺序一致）
        Order a = r.orders.get(0);
        check("往返：最早日期在前", a.date, "2026-09-30");
        Order c = r.orders.get(2);
        check("往返：日期", c.date, "2026-11-01");
        check("往返：金额", c.amount, 128.5);
        check("往返：支付方式", c.payMethod, Order.PAY_APPLE);
        check("往返：餐饮标记", c.dining, true);
        check("往返：备注含逗号", c.note, "午餐, 含小费");
        check("往返：createdAt", c.createdAt, 1001L);

        Order b = r.orders.get(1);
        check("往返：退款金额", b.refundAmount, 300.0);
        check("往返：币种", b.currency, "HKD");
        check("往返：确认标记", b.payConfirmed, true);
        check("往返：备注换行保留", b.note.contains("\n"), true);
        check("往返：净额", b.netAmount(), 1700.0);
    }

    // ---------- 结构错误 ----------

    private static void badFiles() {
        check("空内容报错", ImportParser.parse("").ok, false);
        check("非法 JSON 报错", ImportParser.parse("{oops").ok, false);
        check("根不是对象报错", ImportParser.parse("[1,2,3]").ok, false);
        check("缺少 orders 报错", ImportParser.parse("{\"app\":\"x\"}").ok, false);

        ImportParser.Result r = ImportParser.parse("{\"orders\":[]}");
        check("空 orders 可解析", r.ok, true);
        check("空 orders 条数为 0", r.count(), 0);
    }

    // ---------- 逐行校验 ----------

    private static void rowValidation() {
        String json = "{\"cardName\":\"测试卡\",\"orders\":["
                + "{\"date\":\"2026-11-01\",\"amount\":100,\"payMethod\":\"wechat_union\"},"      // 旧支付方式值
                + "{\"date\":\"2026/11/02\",\"amount\":100},"                                    // 日期格式错
                + "{\"date\":\"2026-11-03\",\"amount\":0},"                                      // 金额为 0
                + "{\"date\":\"2026-11-04\",\"amount\":-5},"                                     // 负金额
                + "{\"date\":\"2026-11-05\",\"amount\":100,\"refund\":500},"                     // 退款超原额
                + "{\"date\":\"2026-11-06\",\"amount\":100,\"currency\":\"USD\"},"               // 未知币种
                + "\"not an object\""                                                           // 非对象
                + "]}";
        ImportParser.Result r = ImportParser.parse(json);
        check("混合数据解析成功", r.ok, true);
        check("只保留合法条目", r.count(), 3);
        check("跳过异常条目数", r.skipped, 4);
        check("旧值 wechat_union 归一", r.orders.get(0).payMethod, Order.PAY_JUMP_UNION);
        check("退款截断到原额", r.orders.get(1).refundAmount, 100.0);
        check("未知币种回落 CNY", r.orders.get(2).currency, "CNY");
        check("净额为 0（全额退款）", r.orders.get(1).netAmount(), 0.0);
    }

    // ---------- 去重键 ----------

    private static void dedupe() {
        Order a = order("2026-11-01", 100, 0, "CNY", Order.PAY_APPLE, false, false, "", 2001L);
        Order same = order("2026-11-01", 100, 0, "CNY", Order.PAY_APPLE, false, false, "", 2001L);
        Order later = order("2026-11-01", 100, 0, "CNY", Order.PAY_APPLE, false, false, "", 2002L);
        Order otherAmount = order("2026-11-01", 100.01, 0, "CNY", Order.PAY_APPLE, false, false, "", 2001L);

        check("同日期同额同时间 → 同键", ImportParser.key(a), ImportParser.key(same));
        check("创建时间不同 → 不同键", ImportParser.key(a).equals(ImportParser.key(later)), false);
        check("金额不同 → 不同键", ImportParser.key(a).equals(ImportParser.key(otherAmount)), false);
    }

    // ---------- 工具 ----------

    private static Order order(String date, double amount, double refund, String currency,
                               String pay, boolean confirmed, boolean dining, String note, long createdAt) {
        Order o = new Order();
        o.date = date;
        o.amount = amount;
        o.refundAmount = refund;
        o.currency = currency;
        o.payMethod = pay;
        o.payConfirmed = confirmed;
        o.dining = dining;
        o.note = note;
        o.createdAt = createdAt;
        o.id = createdAt;
        return o;
    }

    private static void check(String name, Object actual, Object expected) {
        report(name, String.valueOf(actual).equals(String.valueOf(expected)), expected, actual);
    }

    private static void check(String name, int actual, int expected) {
        report(name, actual == expected, expected, actual);
    }

    private static void check(String name, double actual, double expected) {
        report(name, Math.abs(actual - expected) < 0.005, expected, actual);
    }

    private static void report(String name, boolean ok, Object expected, Object actual) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " expected " + expected + " got " + actual);
        }
    }
}
