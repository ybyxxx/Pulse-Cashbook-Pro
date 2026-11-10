import com.hsbcpulse.cashbook.pro.Order;
import com.hsbcpulse.cashbook.pro.Cashback;
import com.hsbcpulse.cashbook.pro.Welcome;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 迎新活动计算逻辑测试。 */
public class WelcomeTest {
    static int pass = 0, fail = 0;

    static void check(String name, Object actual, Object expected) {
        boolean ok = (actual instanceof Double && expected instanceof Double)
                ? Math.abs((Double) actual - (Double) expected) < 0.005
                : String.valueOf(actual).equals(String.valueOf(expected));
        if (ok) {
            pass++;
            System.out.println("PASS " + name + " = " + actual);
        } else {
            fail++;
            System.out.println("FAIL " + name + " expected " + expected + " got " + actual);
        }
    }

    static Order o(String date, double amount, String pay, boolean confirmed) {
        Order x = new Order();
        x.date = date;
        x.amount = amount;
        x.payMethod = pay;
        x.payConfirmed = confirmed;
        return x;
    }

    public static void main(String[] args) {
        String issue = "2026-11-01"; // 窗口 11-01 ~ 12-30（60 日含当日）
        LocalDate inside = LocalDate.parse("2026-11-20");
        LocalDate lastDay = LocalDate.parse("2026-12-30"); // 第 60 日
        LocalDate after = LocalDate.parse("2026-12-31");
        LocalDate before = LocalDate.parse("2026-10-31");

        List<Order> none = new ArrayList<>();

        // 无下卡日期
        Welcome.Result r0 = Welcome.compute(none, "", inside);
        check("no issue date", r0.hasIssueDate, false);

        // 窗口边界：含当日、第 60 日在窗口内、第 61 日过期
        Welcome.Result r1 = Welcome.compute(none, issue, lastDay);
        check("day-60 in window", r1.inWindow, true);
        check("day-60 daysLeft", r1.daysLeft, 1L);
        Welcome.Result r2 = Welcome.compute(none, issue, after);
        check("day-61 expired", r2.expired, true);
        Welcome.Result r3 = Welcome.compute(none, issue, before);
        check("before issue not in window", r3.inWindow, false);

        // 窗口外订单不计
        List<Order> mixed = new ArrayList<>();
        mixed.add(o("2026-10-15", 5000, Order.PAY_NONE, false)); // 窗口前
        mixed.add(o("2026-11-05", 3000, Order.PAY_NONE, false)); // 窗口内
        mixed.add(o("2027-01-10", 9999, Order.PAY_NONE, false)); // 窗口后
        Welcome.Result r4 = Welcome.compute(mixed, issue, inside);
        check("spend only in window", r4.spend, 3000.0);
        check("spend not met", r4.spendMet, false);
        check("reward zero when spend not met", r4.reward, 0.0);

        // 条件 c+d：满 8000 且 QR 笔数计奖
        List<Order> qr = new ArrayList<>();
        qr.add(o("2026-11-02", 4000, Order.PAY_NONE, false));
        qr.add(o("2026-11-03", 4000, Order.PAY_NONE, false)); // 满 8000
        qr.add(o("2026-11-04", 100, Order.PAY_UNION_QR, false));   // 计 1 笔
        qr.add(o("2026-11-05", 60, Order.PAY_UNION_QR, false));    // 计 1 笔
        qr.add(o("2026-11-06", 30, Order.PAY_UNION_QR, false));    // <50 不计
        qr.add(o("2026-11-07", 200, Order.PAY_APPLE, false));      // ApplePay 非 QR 不计
        qr.add(o("2026-11-08", 200, Order.PAY_ALIPAY_WECHAT, false)); // 支付宝微信 不计
        qr.add(o("2026-11-09", 200, Order.PAY_JUMP_UNION, false));    // 跳转未确认 不计
        qr.add(o("2026-11-10", 200, Order.PAY_JUMP_UNION, true));     // 跳转已确认 计
        qr.add(o("2026-11-11", 200, Order.PAY_JUMP_UNION, true));     // 计
        Welcome.Result r5 = Welcome.compute(qr, issue, inside);
        check("spend met", r5.spendMet, true);
        check("qr count", r5.qrCount, 4);
        check("reward = 800 base + 4x10", r5.reward, 840.0);

        // 支付宝/微信交易不计入迎新签账：8000 中有 3000 是支付宝 → 只计 5000，未达标
        List<Order> ali = new ArrayList<>();
        ali.add(o("2026-11-02", 3000, Order.PAY_ALIPAY_WECHAT, false));
        ali.add(o("2026-11-03", 5000, Order.PAY_NONE, false));
        Welcome.Result r5c = Welcome.compute(ali, issue, inside);
        check("alipay excluded from spend", r5c.spend, 5000.0);
        check("alipay excluded spend not met", r5c.spendMet, false);

        // 退款按净额扣减：8000 订单退 50 → 净 7950 未达标
        List<Order> refunded = new ArrayList<>();
        Order ro = o("2026-11-02", 8000, Order.PAY_NONE, false);
        ro.refundAmount = 50;
        refunded.add(ro);
        Welcome.Result r5d = Welcome.compute(refunded, issue, inside);
        check("refund reduces spend", r5d.spend, 7950.0);
        check("refund blocks spend target", r5d.spendMet, false);

        // 仅基础：满 8000 无 QR → $800
        List<Order> baseOnly = new ArrayList<>();
        baseOnly.add(o("2026-11-02", 8000, Order.PAY_NONE, false));
        Welcome.Result r5b = Welcome.compute(baseOnly, issue, inside);
        check("base reward 800", r5b.reward, 800.0);

        // 28 次封顶
        List<Order> many = new ArrayList<>();
        many.add(o("2026-11-02", 8000, Order.PAY_NONE, false));
        for (int i = 0; i < 35; i++) {
            many.add(o("2026-11-05", 50, Order.PAY_UNION_QR, false));
        }
        Welcome.Result r6 = Welcome.compute(many, issue, inside);
        check("qr capped at 28", r6.qrCounted, 28);
        check("reward capped at 1080", r6.reward, 1080.0);

        // 港币人民币 1:1 合并累计
        List<Order> mix2 = new ArrayList<>();
        Order cny = o("2026-11-02", 4000, Order.PAY_NONE, false);
        cny.currency = "CNY";
        mix2.add(cny);
        mix2.add(o("2026-11-03", 4000, Order.PAY_UNION_QR, false)); // HKD 4000
        Welcome.Result r7 = Welcome.compute(mix2, issue, inside);
        check("cny+hkd 1:1 spend", r7.spend, 8000.0);
        check("mixed spend met", r7.spendMet, true);

        // QR 资格判定单测
        check("unionqr qualified", Welcome.isQrQualified(o("x", 1, Order.PAY_UNION_QR, false)), true);
        check("jump_union needs confirm", Welcome.isQrQualified(o("x", 1, Order.PAY_JUMP_UNION, false)), false);
        check("jump_union confirmed", Welcome.isQrQualified(o("x", 1, Order.PAY_JUMP_UNION, true)), true);
        check("applepay not qr", Welcome.isQrQualified(o("x", 1, Order.PAY_APPLE, false)), false);

        System.out.println("---- " + pass + " passed, " + fail + " failed ----");
        if (fail > 0) System.exit(1);
    }
}
