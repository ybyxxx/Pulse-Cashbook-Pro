package com.hsbcpulse.cashbook.pro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 导出内容生成（纯 Java，无 Android 依赖，可在 JVM 单测中直接跑）。
 *
 * 两种格式：
 * - CSV：给 Excel / 表格软件读，列固定，含四项返现分项；
 * - JSON：完整备份，含订单原始字段 + 返现分项 + 汇总。
 *
 * 金额一律用 Locale.US 的 "%.2f"（小数点，无千分位），避免 Excel 误判。
 */
public final class CsvExport {

    public static final String APP_NAME = "Pulse 账本 Pro";
    public static final String APP_VERSION = "2.1.1";
    /** Excel 识别 UTF-8 中文所需的 BOM。 */
    public static final String BOM = "\uFEFF";

    /** CSV 表头，顺序与 {@link #csvRow} 严格一致。 */
    public static final String[] HEADER = {
            "日期", "币种", "金额", "退款", "净额", "支付方式", "自行确认", "餐饮",
            "备注", "基础返现", "支付加成", "赏世界", "餐饮加成", "返现合计"
    };

    private CsvExport() {}

    // ---------- CSV ----------

    /** 生成 CSV 文本（含 BOM，按日期升序）。 */
    public static String toCsv(List<Order> orders, Map<Long, Cashback.OrderResult> results) {
        List<Order> sorted = sortedByDate(orders);
        StringBuilder sb = new StringBuilder(BOM);
        sb.append(String.join(",", HEADER)).append("\r\n");
        for (Order o : sorted) {
            sb.append(csvRow(o, results == null ? null : results.get(o.id))).append("\r\n");
        }
        return sb.toString();
    }

    /** 单行 CSV（不含换行）。 */
    static String csvRow(Order o, Cashback.OrderResult r) {
        double[] c = r == null ? new double[4] : r.components;
        double total = r == null ? 0 : r.cashback;
        return String.join(",",
                cell(o.date),
                cell(o.currency),
                num(o.amount),
                num(o.refundAmount),
                num(o.netAmount()),
                cell(o.methodLabel()),
                cell(o.payConfirmed ? "是" : "否"),
                cell(o.dining ? "是" : "否"),
                cell(o.note),
                num(c[0]), num(c[1]), num(c[2]), num(c[3]),
                num(total));
    }

    // ---------- JSON ----------

    /**
     * 生成 JSON 备份文本。
     *
     * @param shopWorld 年度赏世界报名状态（key=年份），可为 null
     * @param issueDate 下卡日期 yyyy-MM-dd，可为空串
     */
    public static String toJson(List<Order> orders, Map<Long, Cashback.OrderResult> results,
                                Map<Integer, Boolean> shopWorld, String issueDate,
                                String cardName, long exportedAt) {
        List<Order> sorted = sortedByDate(orders);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"app\": ").append(json(APP_NAME)).append(",\n");
        sb.append("  \"version\": ").append(json(APP_VERSION)).append(",\n");
        sb.append("  \"exportedAt\": ").append(json(isoTime(exportedAt))).append(",\n");
        sb.append("  \"cardName\": ").append(json(cardName == null ? "" : cardName)).append(",\n");
        sb.append("  \"cardIssueDate\": ").append(json(issueDate == null ? "" : issueDate)).append(",\n");

        sb.append("  \"shopWorld\": {");
        if (shopWorld != null && !shopWorld.isEmpty()) {
            sb.append("\n");
            int i = 0;
            for (Map.Entry<Integer, Boolean> e : shopWorld.entrySet()) {
                sb.append("    ").append(json(String.valueOf(e.getKey()))).append(": ")
                        .append(e.getValue() ? "true" : "false");
                if (++i < shopWorld.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ");
        }
        sb.append("},\n");

        double netSum = 0, cbSum = 0;
        sb.append("  \"orders\": [");
        for (int i = 0; i < sorted.size(); i++) {
            Order o = sorted.get(i);
            Cashback.OrderResult r = results == null ? null : results.get(o.id);
            double[] c = r == null ? new double[4] : r.components;
            double total = r == null ? 0 : r.cashback;
            netSum += o.netAmount();
            cbSum += total;

            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\n");
            sb.append("      \"id\": ").append(o.id).append(",\n");
            sb.append("      \"date\": ").append(json(o.date)).append(",\n");
            sb.append("      \"currency\": ").append(json(o.currency)).append(",\n");
            sb.append("      \"amount\": ").append(num(o.amount)).append(",\n");
            sb.append("      \"refund\": ").append(num(o.refundAmount)).append(",\n");
            sb.append("      \"net\": ").append(num(o.netAmount())).append(",\n");
            sb.append("      \"payMethod\": ").append(json(o.payMethod)).append(",\n");
            sb.append("      \"payMethodLabel\": ").append(json(o.methodLabel())).append(",\n");
            sb.append("      \"payConfirmed\": ").append(o.payConfirmed).append(",\n");
            sb.append("      \"dining\": ").append(o.dining).append(",\n");
            sb.append("      \"note\": ").append(json(o.note)).append(",\n");
            sb.append("      \"createdAt\": ").append(o.createdAt).append(",\n");
            sb.append("      \"cashback\": ").append(num(total)).append(",\n");
            sb.append("      \"components\": {")
                    .append("\"base\": ").append(num(c[0]))
                    .append(", \"pay\": ").append(num(c[1]))
                    .append(", \"world\": ").append(num(c[2]))
                    .append(", \"dining\": ").append(num(c[3]))
                    .append("}\n");
            sb.append("    }");
        }
        sb.append(sorted.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"summary\": {")
                .append("\"orders\": ").append(sorted.size())
                .append(", \"net\": ").append(num(Cashback.round2(netSum)))
                .append(", \"cashback\": ").append(num(Cashback.round2(cbSum)))
                .append("}\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ---------- 工具 ----------

    /** 按日期升序、同日期按创建时间升序（与 computeYear 口径一致）。 */
    static List<Order> sortedByDate(List<Order> orders) {
        List<Order> sorted = new ArrayList<>(orders);
        sorted.sort((a, b) -> {
            int cmp = String.valueOf(a.date).compareTo(String.valueOf(b.date));
            if (cmp != 0) return cmp;
            cmp = Long.compare(a.createdAt, b.createdAt);
            if (cmp != 0) return cmp;
            return Long.compare(a.id, b.id);
        });
        return sorted;
    }

    /** CSV 单元格转义：含逗号 / 引号 / 换行时加引号，内部引号翻倍。 */
    static String cell(String s) {
        if (s == null) return "";
        if (s.isEmpty()) return "";
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || s.startsWith(" ") || s.endsWith(" ");
        if (!quote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** JSON 字符串转义（含控制字符）。 */
    static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (ch < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 两位小数，无千分位（Excel 友好）。 */
    static String num(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** ISO 本地时间 yyyy-MM-dd'T'HH:mm:ss。 */
    static String isoTime(long millis) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new java.util.Date(millis));
    }

    /** 建议文件名：pulse-orders-20261110-1330.csv */
    public static String fileName(String ext, long millis) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return "pulse-orders-" + f.format(new java.util.Date(millis)) + "." + ext;
    }
}
