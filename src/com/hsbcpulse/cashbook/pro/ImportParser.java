package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON 备份解析（纯 JVM，无 Android 依赖）。
 *
 * 输入是 {@link CsvExport#toJson} 生成的备份文件。解析时逐条校验：
 * 日期格式、金额、退款不超原额、币种、支付方式归一。
 * 单条有问题只跳过该条并计数，整体结构不对才报错。
 *
 * 注意：备份不含截图附件（路径是设备本地的），恢复后附件需重新添加。
 */
public final class ImportParser {

    /** 单次导入上限，防止异常文件把内存撑爆。 */
    public static final int MAX_ORDERS = 20000;

    private ImportParser() {}

    public static class Result {
        public boolean ok = false;
        public String error = "";
        /** 备份里的应用版本与导出时间，仅用于展示。 */
        public String appVersion = "";
        public String exportedAt = "";
        /** 备份对应的卡名与下卡日期。 */
        public String cardName = "";
        public String cardIssueDate = "";
        public final List<Order> orders = new ArrayList<>();
        /** 跳过的异常条目数。 */
        public int skipped = 0;

        public int count() { return orders.size(); }
    }

    /** 解析备份文本；从不抛异常，失败时 result.ok=false 且 error 有值。 */
    public static Result parse(String json) {
        Result r = new Result();
        if (json == null || json.trim().isEmpty()) {
            r.error = "文件内容为空";
            return r;
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Exception e) {
            r.error = "不是合法的 JSON：" + e.getMessage();
            return r;
        }
        Map<String, Object> m = MiniJson.asMap(root);
        if (m == null) {
            r.error = "备份格式不正确（根节点不是对象）";
            return r;
        }
        List<Object> arr = MiniJson.asList(m.get("orders"));
        if (arr == null) {
            r.error = "备份里没有 orders 字段，可能不是本应用导出的文件";
            return r;
        }
        if (arr.size() > MAX_ORDERS) {
            r.error = "备份条数过多（" + arr.size() + " 条），超过上限 " + MAX_ORDERS;
            return r;
        }

        r.appVersion = MiniJson.str(m, "version", "");
        r.exportedAt = MiniJson.str(m, "exportedAt", "");
        r.cardName = MiniJson.str(m, "cardName", "");
        r.cardIssueDate = MiniJson.str(m, "cardIssueDate", "");

        for (Object o : arr) {
            Map<String, Object> om = MiniJson.asMap(o);
            if (om == null) { r.skipped++; continue; }

            String date = MiniJson.str(om, "date", "");
            double amount = MiniJson.num(om, "amount", 0);
            if (!validDate(date) || amount <= 0) { r.skipped++; continue; }

            Order order = new Order();
            order.date = date;
            order.amount = Cashback.round2(amount);
            double refund = MiniJson.num(om, "refund", 0);
            order.refundAmount = Math.max(0, Math.min(refund, order.amount));
            String currency = MiniJson.str(om, "currency", "CNY");
            order.currency = "HKD".equals(currency) ? "HKD" : "CNY";
            order.note = MiniJson.str(om, "note", "");
            order.payMethod = Order.normalizePayMethod(MiniJson.str(om, "payMethod", Order.PAY_NONE));
            order.payConfirmed = MiniJson.bool(om, "payConfirmed", false);
            order.dining = MiniJson.bool(om, "dining", false);
            order.createdAt = (long) MiniJson.num(om, "createdAt", 0);
            order.id = -1;
            r.orders.add(order);
        }

        r.ok = true;
        return r;
    }

    private static boolean validDate(String d) {
        if (d == null || d.length() != 10 || d.charAt(4) != '-' || d.charAt(7) != '-') return false;
        try {
            int y = Integer.parseInt(d.substring(0, 4));
            int mo = Integer.parseInt(d.substring(5, 7));
            int day = Integer.parseInt(d.substring(8, 10));
            return y >= 1900 && y <= 2999 && mo >= 1 && mo <= 12 && day >= 1 && day <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 去重键：同日期、同金额、同创建时间视为同一笔（{@link CsvExport} 导出的 createdAt 保留毫秒）。 */
    public static String key(Order o) {
        return o.date + "|" + String.format(Locale.US, "%.2f", o.amount) + "|" + o.createdAt;
    }
}
