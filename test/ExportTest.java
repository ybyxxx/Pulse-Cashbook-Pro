import com.hsbcpulse.cashbook.pro.Cashback;
import com.hsbcpulse.cashbook.pro.CsvExport;
import com.hsbcpulse.cashbook.pro.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 导出格式测试：CSV 转义/排序/数值口径，JSON 结构/转义。纯 JVM，不依赖 Android。 */
public class ExportTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        // ---------- CSV ----------
        Order a = order(1, "2026-11-01", 100, 0, "Apple Pay 午餐, 含小费", Order.PAY_APPLE, true);
        Order b = order(2, "2026-10-05", 200, 50, "带\"引号\"\n和换行", Order.PAY_UNION_QR, false);
        Order c = order(3, "2026-11-20", 1500.5, 0, "", Order.PAY_ONLINE_CARD, false);

        List<Order> orders = new ArrayList<>();
        orders.add(a);
        orders.add(b);
        orders.add(c);
        Map<Long, Cashback.OrderResult> res = Cashback.computeYear(orders, false);

        String csv = CsvExport.toCsv(orders, res);

        check("BOM 开头", csv.startsWith("\uFEFF"));
        check("表头完整", csv.contains("日期,币种,金额,退款,净额,支付方式,自行确认,餐饮,备注,基础返现,支付加成,赏世界,餐饮加成,返现合计"));
        check("行数 = 表头 + 3", csv.split("\r\n").length == 4);

        String[] lines = csv.split("\r\n");
        check("逐日升序（第一条为 10-05）", lines[1].startsWith("2026-10-05"));
        check("同日/末条为 11-20", lines[3].startsWith("2026-11-20"));
        check("中间行为 11-01", lines[2].startsWith("2026-11-01"));

        // 11-01 那条备注含逗号
        String rowNov1 = findRow(lines, "2026-11-01");
        check("逗号备注被引号包裹", rowNov1.contains("\"Apple Pay 午餐, 含小费\""));
        check("Apple Pay 支付方式标签", rowNov1.contains("Apple Pay"));

        // 引号翻倍 + 换行保留在引号内
        String rowOct = lines[1];
        check("引号翻倍转义", rowOct.contains("\"\"引号\"\""));
        check("换行被引号包裹", rowOct.contains("\n"));

        // 退款净额
        check("退款净额 = 150.00", rowOct.contains(",150.00,"));
        check("退款列 = 50.00", rowOct.contains(",50.00,"));

        // 数值格式：两位小数、无千分位
        check("金额两位小数", rowNov1.contains(",100.00,"));
        check("无千分位分隔符", !csv.contains("1,500.50"));

        // 分项合计 = 合计列
        for (Order o : orders) {
            Cashback.OrderResult r = res.get(o.id);
            double sum = Cashback.round2(r.components[0] + r.components[1] + r.components[2] + r.components[3]);
            check("分项合计一致 id=" + o.id, Math.abs(sum - r.cashback) < 0.005);
        }

        // ---------- JSON ----------
        Map<Integer, Boolean> sw = new LinkedHashMap<>();
        sw.put(2026, true);
        String json = CsvExport.toJson(orders, res, sw, "2026-10-01", "Pulse 主卡", 1763000000000L);

        check("JSON 含 app 字段", json.contains("\"app\": \"Pulse 账本 Pro\""));
        check("JSON 含 version", json.contains("\"version\": \"" + CsvExport.APP_VERSION + "\""));
        check("JSON 转义引号", json.contains("带\\\"引号\\\""));
        check("JSON 转义换行", json.contains("\\n和换行"));
        check("JSON 下卡日期", json.contains("\"cardIssueDate\": \"2026-10-01\""));
        check("JSON 赏世界标记", json.contains("\"2026\": true"));
        check("JSON 订单数 summary", json.contains("\"orders\": 3"));
        check("JSON 无裸换行（字符串内）", !json.contains("\n和换行"));
        check("JSON 括号配平", count(json, '{') == count(json, '}') && count(json, '[') == count(json, ']'));

        String emptyJson = CsvExport.toJson(new ArrayList<Order>(), res,
                new LinkedHashMap<Integer, Boolean>(), "", "", 0L);
        check("空数据 JSON 合法", emptyJson.contains("\"orders\": []") && emptyJson.contains("\"orders\": 0"));

        // ---------- 文件名 ----------
        String name = CsvExport.fileName("csv", 1763000000000L);
        check("文件名格式", name.startsWith("pulse-orders-") && name.endsWith(".csv") && name.length() > 20);

        System.out.println("---- " + passed + " passed, " + failed + " failed ----");
        if (failed > 0) System.exit(1);
    }

    private static Order order(long id, String date, double amount, double refund,
                              String note, String pay, boolean dining) {
        Order o = new Order();
        o.id = id;
        o.date = date;
        o.amount = amount;
        o.refundAmount = refund;
        o.currency = "CNY";
        o.note = note;
        o.payMethod = pay;
        o.dining = dining;
        o.createdAt = 1000L + id;
        return o;
    }

    private static String findRow(String[] lines, String datePrefix) {
        for (String l : lines) if (l.startsWith(datePrefix)) return l;
        return "";
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS " + name); }
        else { failed++; System.out.println("FAIL " + name); }
    }
}
