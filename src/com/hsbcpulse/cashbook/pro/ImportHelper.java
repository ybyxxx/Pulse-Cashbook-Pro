package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据恢复（Android 侧）。
 *
 * 文件读取走系统文件选择器（ACTION_OPEN_DOCUMENT），不需要存储权限；
 * 解析全部交给 {@link ImportParser}（纯 JVM 可测），这里只负责读流、落库、拼提示文案。
 *
 * 三种恢复方式：
 * - MERGE   合并到当前卡片：按「日期+金额+创建时间」去重，重复的跳过；
 * - NEW_CARD 恢复为一张新卡片：不动现有数据，适合从别的设备/备份导回；
 * - REPLACE 覆盖当前卡片：先清空当前卡片账单（含截图），再导入。
 */
public final class ImportHelper {

    public static final int REQ_PICK_JSON = 8201;

    public static final int MODE_MERGE = 0;
    public static final int MODE_NEW_CARD = 1;
    public static final int MODE_REPLACE = 2;

    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private ImportHelper() {}

    // ---------- 选择文件 ----------

    public static Intent createIntent() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream"
        });
        return it;
    }

    /** 读取并解析选中的文件；任何失败都通过 result.error 返回，不抛异常。 */
    public static ImportParser.Result read(Context ctx, Uri uri) {
        ImportParser.Result fail = new ImportParser.Result();
        if (uri == null) {
            fail.error = "未选择文件";
            return fail;
        }
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                fail.error = "无法打开文件";
                return fail;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) {
                    fail.error = "文件过大（超过 8MB）";
                    return fail;
                }
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return ImportParser.parse(text);
        } catch (Throwable t) {
            fail.error = "读取失败：" + t.getClass().getSimpleName();
            return fail;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ---------- 落库 ----------

    /** 按指定模式恢复，返回给用户看的提示文案。 */
    public static String apply(Context ctx, ImportParser.Result r, long cardId, int mode) {
        if (r == null) return ctx.getString(R.string.restore_failed, "解析结果为空");
        if (!r.ok) return ctx.getString(R.string.restore_failed, r.error);
        if (r.orders.isEmpty()) return ctx.getString(R.string.restore_empty);

        CashbookDb db = new CashbookDb(ctx);
        try {
            if (mode == MODE_NEW_CARD) {
                String base = r.cardName == null || r.cardName.trim().isEmpty()
                        ? "恢复的卡片" : r.cardName.trim();
                String name = uniqueName(db, base);
                long newCard = db.insertCard(name, r.cardIssueDate);
                for (Order o : r.orders) o.cardId = newCard;
                int n = db.insertOrders(r.orders);
                Prefs.setCurrentCardId(ctx, newCard);
                return ctx.getString(R.string.restore_done_new, n, name);
            }

            if (mode == MODE_REPLACE) {
                List<String> files = db.clearOrders(cardId);
                for (String p : files) ImageUtil.deleteQuietly(p);
                for (Order o : r.orders) o.cardId = cardId;
                int n = db.insertOrders(r.orders);
                return ctx.getString(R.string.restore_done, n);
            }

            // MERGE：已存在的（日期+金额+创建时间相同）跳过
            Set<String> existing = new HashSet<>();
            for (Order o : db.getAllOrdersLight(cardId)) existing.add(ImportParser.key(o));
            List<Order> fresh = new ArrayList<>();
            int duplicate = 0;
            for (Order o : r.orders) {
                o.cardId = cardId;
                if (existing.add(ImportParser.key(o))) fresh.add(o);
                else duplicate++;
            }
            int n = db.insertOrders(fresh);
            if (duplicate > 0) return ctx.getString(R.string.restore_done_skip, n, duplicate);
            return ctx.getString(R.string.restore_done, n);
        } finally {
            db.close();
        }
    }

    /** 当前卡片已有账单数（覆盖恢复前的确认文案用）。 */
    public static int countOrders(Context ctx, long cardId) {
        CashbookDb db = new CashbookDb(ctx);
        try {
            return db.countOrders(cardId);
        } finally {
            db.close();
        }
    }

    private static String uniqueName(CashbookDb db, String base) {
        Set<String> names = new HashSet<>();
        for (Card c : db.getCards()) names.add(c.displayName());
        if (!names.contains(base)) return base;
        for (int i = 2; i < 100; i++) {
            String candidate = base + " (" + i + ")";
            if (!names.contains(candidate)) return candidate;
        }
        return base + " (恢复)";
    }
}
