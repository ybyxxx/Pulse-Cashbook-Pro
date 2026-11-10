package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 导出功能（Android 侧）。
 *
 * 写入走系统文件选择器（ACTION_CREATE_DOCUMENT），由用户自选保存位置，
 * 因此不需要任何存储权限，也不违反「纯本地、零权限」原则。
 * 内容生成全在 {@link CsvExport}（纯 JVM 可测）。
 */
public final class ExportHelper {

    public static final int REQ_CSV = 8101;
    public static final int REQ_JSON = 8102;

    private ExportHelper() {}

    // ---------- 内容生成 ----------

    /** 汇总某张卡全部订单的返现结果（按年分批 computeYear，保证年度额度口径正确）。 */
    public static Map<Long, Cashback.OrderResult> computeAll(Context ctx, List<Order> all) {
        Map<Integer, List<Order>> byYear = new TreeMap<>();
        for (Order o : all) {
            int y = yearOf(o.date);
            List<Order> list = byYear.get(y);
            if (list == null) {
                list = new ArrayList<>();
                byYear.put(y, list);
            }
            list.add(o);
        }
        Map<Long, Cashback.OrderResult> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Order>> e : byYear.entrySet()) {
            boolean sw = Prefs.isShopWorld(ctx, e.getKey());
            out.putAll(Cashback.computeYear(e.getValue(), sw));
        }
        return out;
    }

    public static String buildCsv(Context ctx) {
        long cardId = Prefs.getCurrentCardId(ctx);
        CashbookDb db = new CashbookDb(ctx);
        try {
            List<Order> all = db.getAllOrdersLight(cardId);
            return CsvExport.toCsv(all, computeAll(ctx, all));
        } finally {
            db.close();
        }
    }

    /** 账单笔数（导出结果提示用）。 */
    public static int countOrders(Context ctx) {
        long cardId = Prefs.getCurrentCardId(ctx);
        CashbookDb db = new CashbookDb(ctx);
        try {
            return db.getAllOrdersLight(cardId).size();
        } finally {
            db.close();
        }
    }

    public static String buildJson(Context ctx) {
        long cardId = Prefs.getCurrentCardId(ctx);
        CashbookDb db = new CashbookDb(ctx);
        try {
            List<Order> all = db.getAllOrdersLight(cardId);
            Card card = db.getCard(cardId);
            Map<Integer, Boolean> shopWorld = new TreeMap<>();
            for (Order o : all) {
                int y = yearOf(o.date);
                if (!shopWorld.containsKey(y)) shopWorld.put(y, Prefs.isShopWorld(ctx, y));
            }
            String issue = card == null ? "" : card.issueDate;
            String name = card == null ? "" : card.displayName();
            return CsvExport.toJson(all, computeAll(ctx, all), shopWorld, issue, name,
                    System.currentTimeMillis());
        } finally {
            db.close();
        }
    }

    // ---------- 系统文件选择器 ----------

    public static Intent createCsvIntent() {
        return createIntent("text/csv", CsvExport.fileName("csv", System.currentTimeMillis()));
    }

    public static Intent createJsonIntent() {
        return createIntent("application/json", CsvExport.fileName("json", System.currentTimeMillis()));
    }

    private static Intent createIntent(String mime, String name) {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(mime);
        it.putExtra(Intent.EXTRA_TITLE, name);
        return it;
    }

    /** 写入内容，返回 null 表示成功，否则返回错误信息。 */
    public static String write(Context ctx, Uri uri, String content) {
        if (uri == null) return "未选择保存位置";
        OutputStream os = null;
        try {
            os = ctx.getContentResolver().openOutputStream(uri, "wt");
            if (os == null) return "无法打开目标文件";
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.flush();
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (os != null) {
                try { os.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static int yearOf(String date) {
        try { return Integer.parseInt(date.substring(0, 4)); }
        catch (Exception e) { return java.util.Calendar.getInstance().get(java.util.Calendar.YEAR); }
    }
}
