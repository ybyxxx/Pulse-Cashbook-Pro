import com.hsbcpulse.cashbook.pro.Cashback;
import com.hsbcpulse.cashbook.pro.Order;
import com.hsbcpulse.cashbook.pro.RewardActivity;
import com.hsbcpulse.cashbook.pro.RuleEngine;
import com.hsbcpulse.cashbook.pro.RuleSet;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 校验仓库根目录的 rules.json（真正下发给客户端的那份）。
 *
 * 目的：规则文件是「数据」，不走编译期检查，改了 JSON 很容易出错。
 * 这个测试在提交前跑一遍，保证文件能解析、字段合法、口径与内置规则一致。
 * 文件不存在时跳过（纯 JVM 环境不一定从项目根运行）。
 */
public class RulesFileTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File f = new File("rules.json");
        if (!f.isFile()) {
            System.out.println("SKIP rules.json 不在当前目录，跳过");
            return;
        }
        String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        RuleSet rs = RuleSet.parse(json);
        RuleSet builtin = RuleSet.builtin();

        check("可解析且版本非空", !rs.version.isEmpty(), true);
        check("schemaVersion = 1", rs.schemaVersion, RuleSet.SCHEMA_VERSION);
        check("基础费率与内置一致", rs.baseRate, builtin.baseRate);
        check("餐饮门槛与内置一致", rs.diningThreshold, builtin.diningThreshold);
        check("活动数量与内置一致", rs.activities.size(), builtin.activities.size());

        for (RewardActivity b : builtin.activities) {
            RewardActivity a = rs.findById(b.id);
            if (a == null) {
                check("缺少活动 " + b.id, false, true);
                continue;
            }
            check("活动 " + b.id + " 类型一致", a.type, b.type);
            check("活动 " + b.id + " 费率一致", a.rate, b.rate);
            check("活动 " + b.id + " 额度一致", a.capAmount, b.capAmount);
            check("活动 " + b.id + " 额度作用域一致", a.capScope, b.capScope);
            check("活动 " + b.id + " 额度类型一致", a.capLimitType, b.capLimitType);
            check("活动 " + b.id + " 报名要求一致", a.requiresOptIn, b.requiresOptIn);
            check("活动 " + b.id + " 餐饮限定一致", a.diningOnly, b.diningOnly);
            check("活动 " + b.id + " 月门槛一致", a.minMonthlySpend, b.minMonthlySpend);
            check("活动 " + b.id + " 支付方式一致", String.join(",", a.payMethods), String.join(",", b.payMethods));
            check("活动 " + b.id + " 确认方式一致", String.join(",", a.confirmMethods), String.join(",", b.confirmMethods));
            check("活动 " + b.id + " 排除方式一致", String.join(",", b.excludeMethods), String.join(",", b.excludeMethods));
        }

        // 抽样对比：同一笔订单在两套规则下结果必须相同（确保下发不改变现行口径）
        Order o = new Order();
        o.amount = 100;
        o.date = "2026-11-01";
        o.payMethod = Order.PAY_APPLE;
        o.dining = true;
        check("抽样：ApplePay+餐饮(满额)",
                RuleEngine.cashbackOf(o, rs, true, true, 80000, 100000, 80),
                RuleEngine.cashbackOf(o, builtin, true, true, 80000, 100000, 80));

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        report(name, ok, expected, actual);
    }

    private static void check(String name, double actual, double expected) {
        report(name, Math.abs(actual - expected) < 0.005, expected, actual);
    }

    private static void check(String name, int actual, int expected) {
        report(name, actual == expected, expected, actual);
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
