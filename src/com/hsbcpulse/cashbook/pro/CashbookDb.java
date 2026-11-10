package com.hsbcpulse.cashbook.pro;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地 SQLite 存储：订单 + 附件。
 *
 * v2：支付方式合并为 pay_method 单选字段（+ pay_confirmed 自行确认标记），
 * 赏世界活动改为按年度全局开关（SharedPreferences），不再逐单存储。
 * v3：新增 refund_amount 部分退款列；支付方式归并（跳转云闪付、在线/实体卡）。
 */
public class CashbookDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "pulse_cashbook_pro.db";
    private static final int DB_VERSION = 4;

    /**
     * 全局数据版本号：任何 CashbookDb 实例执行写操作都会 +1。
     * 用于跨实例失效返现缓存（主页实例 vs 编辑页实例）。
     */
    private static final java.util.concurrent.atomic.AtomicLong REVISION =
            new java.util.concurrent.atomic.AtomicLong();

    /** 年度返现结果缓存（key = year|shopWorld），数据一变即整体失效。 */
    private final java.util.Map<String, java.util.Map<Long, Cashback.OrderResult>> resultCache =
            new java.util.HashMap<>();
    private long cacheRevision = -1;
    /** 上次缓存对应的规则代次，规则变更时整体失效。 */
    private long rulesGenCache = -1;

    private final Context context;

    public CashbookDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cards (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "issue_date TEXT DEFAULT ''," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE orders (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "date TEXT NOT NULL," +
                "amount REAL NOT NULL," +
                "currency TEXT NOT NULL," +
                "note TEXT DEFAULT ''," +
                "pay_method TEXT DEFAULT ''," +
                "pay_confirmed INTEGER DEFAULT 0," +
                "dining INTEGER DEFAULT 0," +
                "refund_amount REAL NOT NULL DEFAULT 0," +
                "card_id INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE attachments (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "order_id INTEGER NOT NULL," +
                "path TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_orders_date ON orders(date)");
        db.execSQL("CREATE INDEX idx_orders_card ON orders(card_id)");
        // 默认卡片
        ContentValues cv = new ContentValues();
        cv.put("name", "Pulse 主卡");
        cv.put("issue_date", Prefs.getCardIssueDate(context));
        cv.put("created_at", System.currentTimeMillis());
        db.insert("cards", null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1 -> v2：新增支付方式字段
            db.execSQL("ALTER TABLE orders ADD COLUMN pay_method TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE orders ADD COLUMN pay_confirmed INTEGER DEFAULT 0");
            // 迁移旧的 applepay / unionqr 勾选为单选支付方式
            db.execSQL("UPDATE orders SET pay_method='applepay' WHERE applepay=1");
            db.execSQL("UPDATE orders SET pay_method='unionqr' WHERE unionqr=1 AND (pay_method='' OR pay_method IS NULL)");
            // 迁移旧的 shopworld 逐单勾选为年度全局报名
            Set<Integer> years = new HashSet<>();
            Cursor c = db.rawQuery("SELECT DISTINCT substr(date,1,4) FROM orders WHERE shopworld=1", null);
            while (c.moveToNext()) {
                try { years.add(Integer.parseInt(c.getString(0))); } catch (Exception ignored) {}
            }
            c.close();
            for (Integer y : years) Prefs.setShopWorld(context, y, true);
        }
        if (oldVersion < 3) {
            // v2 -> v3：部分退款列 + 支付方式归并
            db.execSQL("ALTER TABLE orders ADD COLUMN refund_amount REAL NOT NULL DEFAULT 0");
            db.execSQL("UPDATE orders SET pay_method='jumpunion' WHERE pay_method IN ('wechat_union','app_union')");
            db.execSQL("UPDATE orders SET pay_method='onlinecard' WHERE pay_method IN ('online','physical')");
        }
        if (oldVersion < 4) {
            // v3 -> v4：多卡片支持
            db.execSQL("CREATE TABLE IF NOT EXISTS cards (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "issue_date TEXT DEFAULT ''," +
                    "created_at INTEGER NOT NULL)");
            db.execSQL("ALTER TABLE orders ADD COLUMN card_id INTEGER NOT NULL DEFAULT 1");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_card ON orders(card_id)");
            // 老数据全部归入默认卡；下卡日期沿用原全局设置
            ContentValues cv = new ContentValues();
            cv.put("name", "Pulse 主卡");
            cv.put("issue_date", Prefs.getCardIssueDate(context));
            cv.put("created_at", System.currentTimeMillis());
            db.insert("cards", null, cv);
        }
    }

    // ---------- 增 ----------

    public long insertOrder(Order o) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("date", o.date);
        cv.put("amount", o.amount);
        cv.put("refund_amount", o.refundAmount);
        cv.put("currency", o.currency);
        cv.put("note", o.note);
        cv.put("pay_method", o.payMethod == null ? "" : o.payMethod);
        cv.put("pay_confirmed", o.payConfirmed ? 1 : 0);
        cv.put("dining", o.dining ? 1 : 0);
        cv.put("card_id", o.cardId > 0 ? o.cardId : 1);
        cv.put("created_at", o.createdAt > 0 ? o.createdAt : System.currentTimeMillis());
        long id = db.insert("orders", null, cv);
        saveAttachments(id, o.attachments);
        REVISION.incrementAndGet();
        return id;
    }

    /** 恢复已删除订单（撤销用），保留原 _id 与附件。 */
    public void restoreOrder(Order o) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("_id", o.id);
        cv.put("date", o.date);
        cv.put("amount", o.amount);
        cv.put("refund_amount", o.refundAmount);
        cv.put("currency", o.currency);
        cv.put("note", o.note);
        cv.put("pay_method", o.payMethod == null ? "" : o.payMethod);
        cv.put("pay_confirmed", o.payConfirmed ? 1 : 0);
        cv.put("dining", o.dining ? 1 : 0);
        cv.put("card_id", o.cardId > 0 ? o.cardId : 1);
        cv.put("created_at", o.createdAt);
        db.insertWithOnConflict("orders", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        saveAttachments(o.id, o.attachments);
        REVISION.incrementAndGet();
    }

    /**
     * 批量插入（数据恢复用），单事务提交：要么全进，要么全不进。
     *
     * @return 实际插入条数
     */
    public int insertOrders(List<Order> orders) {
        if (orders == null || orders.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            for (Order o : orders) {
                ContentValues cv = orderValues(o);
                cv.put("created_at", o.createdAt > 0 ? o.createdAt : System.currentTimeMillis());
                if (db.insert("orders", null, cv) > 0) n++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (n > 0) REVISION.incrementAndGet();
        return n;
    }

    /** 清空某张卡的全部账单（保留卡片本身），返回需清理的附件文件路径。 */
    public List<String> clearOrders(long cardId) {
        SQLiteDatabase db = getWritableDatabase();
        List<String> paths = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT a.path FROM attachments a JOIN orders o ON o._id=a.order_id " +
                "WHERE o.card_id=?", new String[]{String.valueOf(cardId)});
        try {
            while (c.moveToNext()) paths.add(c.getString(0));
        } finally {
            c.close();
        }
        db.delete("attachments", "order_id IN (SELECT _id FROM orders WHERE card_id=?)",
                new String[]{String.valueOf(cardId)});
        db.delete("orders", "card_id=?", new String[]{String.valueOf(cardId)});
        REVISION.incrementAndGet();
        return paths;
    }

    /** 订单字段 → ContentValues（不含 _id / created_at，由调用方决定）。 */
    private ContentValues orderValues(Order o) {
        ContentValues cv = new ContentValues();
        cv.put("date", o.date);
        cv.put("amount", o.amount);
        cv.put("refund_amount", o.refundAmount);
        cv.put("currency", o.currency);
        cv.put("note", o.note);
        cv.put("pay_method", o.payMethod == null ? "" : o.payMethod);
        cv.put("pay_confirmed", o.payConfirmed ? 1 : 0);
        cv.put("dining", o.dining ? 1 : 0);
        cv.put("card_id", o.cardId > 0 ? o.cardId : 1);
        return cv;
    }

    // ---------- 改 ----------

    public int updateOrder(Order o) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("date", o.date);
        cv.put("amount", o.amount);
        cv.put("refund_amount", o.refundAmount);
        cv.put("currency", o.currency);
        cv.put("note", o.note);
        cv.put("pay_method", o.payMethod == null ? "" : o.payMethod);
        cv.put("pay_confirmed", o.payConfirmed ? 1 : 0);
        cv.put("dining", o.dining ? 1 : 0);
        int rows = db.update("orders", cv, "_id=?", new String[]{String.valueOf(o.id)});
        db.delete("attachments", "order_id=?", new String[]{String.valueOf(o.id)});
        saveAttachments(o.id, o.attachments);
        REVISION.incrementAndGet();
        return rows;
    }

    private void saveAttachments(long orderId, List<String> paths) {
        if (paths == null) return;
        SQLiteDatabase db = getWritableDatabase();
        for (String p : paths) {
            ContentValues cv = new ContentValues();
            cv.put("order_id", orderId);
            cv.put("path", p);
            db.insert("attachments", null, cv);
        }
    }

    // ---------- 删 ----------

    /** 删除订单并返回附件路径列表，方便调用方清理文件。 */
    public List<String> deleteOrder(long id) {
        SQLiteDatabase db = getWritableDatabase();
        List<String> paths = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT path FROM attachments WHERE order_id=?", new String[]{String.valueOf(id)});
        while (c.moveToNext()) paths.add(c.getString(0));
        c.close();
        db.delete("attachments", "order_id=?", new String[]{String.valueOf(id)});
        db.delete("orders", "_id=?", new String[]{String.valueOf(id)});
        REVISION.incrementAndGet();
        return paths;
    }

    // ---------- 查 ----------

    public Order getOrder(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM orders WHERE _id=?", new String[]{String.valueOf(id)});
        Order o = null;
        if (c.moveToFirst()) o = fromCursor(c);
        c.close();
        if (o != null) o.attachments = getAttachments(id);
        return o;
    }

    /**
     * 统一查询入口。
     * withAttachments=false 时跳过逐单附件查询（N+1），统计/批量计算场景专用。
     */
    private List<Order> query(String selection, String[] args, String orderBy, boolean withAttachments) {
        SQLiteDatabase db = getReadableDatabase();
        List<Order> list = new ArrayList<>();
        String sql = "SELECT * FROM orders"
                + (selection == null ? "" : " WHERE " + selection)
                + " ORDER BY " + orderBy;
        Cursor c = db.rawQuery(sql, args);
        try {
            while (c.moveToNext()) list.add(fromCursor(c));
        } finally {
            c.close();
        }
        if (withAttachments) {
            for (Order o : list) o.attachments = getAttachments(o.id);
        }
        return list;
    }

    /** 查询某个月的订单（按卡），按日期倒序、创建时间倒序。month 格式 yyyy-MM。 */
    public List<Order> getOrdersByMonth(String month, long cardId) {
        return query("date LIKE ? AND card_id=?",
                new String[]{month + "%", String.valueOf(cardId)},
                "date DESC, created_at DESC", true);
    }

    /** 月度订单（不含附件，统计用）。 */
    public List<Order> getOrdersByMonthLight(String month, long cardId) {
        return query("date LIKE ? AND card_id=?",
                new String[]{month + "%", String.valueOf(cardId)},
                "date DESC, created_at DESC", false);
    }

    /** 某张卡的全部订单（迎新窗口统计用），按日期倒序。 */
    public List<Order> getAllOrders(long cardId) {
        return query("card_id=?", new String[]{String.valueOf(cardId)},
                "date DESC, created_at DESC", true);
    }

    /** 某张卡的全部订单（不含附件，迎新/导出统计用）。 */
    public List<Order> getAllOrdersLight(long cardId) {
        return query("card_id=?", new String[]{String.valueOf(cardId)},
                "date DESC, created_at DESC", false);
    }

    /** 某年某卡全部订单（年度额度累计用），按日期升序、创建时间升序。 */
    public List<Order> getOrdersByYear(int year, long cardId) {
        return query("date LIKE ? AND card_id=?",
                new String[]{year + "%", String.valueOf(cardId)},
                "date ASC, created_at ASC", true);
    }

    /** 某年某卡全部订单（不含附件，返现批量计算用）。 */
    public List<Order> getOrdersByYearLight(int year, long cardId) {
        return query("date LIKE ? AND card_id=?",
                new String[]{year + "%", String.valueOf(cardId)},
                "date ASC, created_at ASC", false);
    }

    private List<String> getAttachments(long orderId) {
        List<String> paths = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT path FROM attachments WHERE order_id=? ORDER BY _id",
                new String[]{String.valueOf(orderId)});
        while (c.moveToNext()) paths.add(c.getString(0));
        c.close();
        return paths;
    }

    private Order fromCursor(Cursor c) {
        Order o = new Order();
        o.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        o.date = c.getString(c.getColumnIndexOrThrow("date"));
        o.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
        o.currency = c.getString(c.getColumnIndexOrThrow("currency"));
        o.note = c.getString(c.getColumnIndexOrThrow("note"));
        String pm = c.getString(c.getColumnIndexOrThrow("pay_method"));
        o.payMethod = Order.normalizePayMethod(pm);
        o.payConfirmed = c.getInt(c.getColumnIndexOrThrow("pay_confirmed")) == 1;
        o.dining = c.getInt(c.getColumnIndexOrThrow("dining")) == 1;
        try {
            o.cardId = c.getLong(c.getColumnIndexOrThrow("card_id"));
        } catch (Exception e) {
            o.cardId = 1;
        }
        if (o.cardId <= 0) o.cardId = 1;
        try {
            o.refundAmount = c.getDouble(c.getColumnIndexOrThrow("refund_amount"));
        } catch (Exception e) {
            o.refundAmount = 0;
        }
        o.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return o;
    }

    // ---------- 卡片 ----------

    /** 全部卡片，按创建顺序。 */
    public List<Card> getCards() {
        List<Card> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, name, issue_date, created_at FROM cards ORDER BY created_at ASC, _id ASC", null);
        try {
            while (c.moveToNext()) {
                Card card = new Card(c.getLong(0), c.getString(1), c.getString(2));
                card.createdAt = c.getLong(3);
                list.add(card);
            }
        } finally {
            c.close();
        }
        return list;
    }

    public Card getCard(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT _id, name, issue_date, created_at FROM cards WHERE _id=?",
                new String[]{String.valueOf(id)});
        try {
            if (c.moveToFirst()) {
                Card card = new Card(c.getLong(0), c.getString(1), c.getString(2));
                card.createdAt = c.getLong(3);
                return card;
            }
        } finally {
            c.close();
        }
        return null;
    }

    public long insertCard(String name, String issueDate) {
        ContentValues cv = new ContentValues();
        cv.put("name", name == null || name.trim().isEmpty() ? "未命名卡片" : name.trim());
        cv.put("issue_date", issueDate == null ? "" : issueDate);
        cv.put("created_at", System.currentTimeMillis());
        long id = getWritableDatabase().insert("cards", null, cv);
        REVISION.incrementAndGet();
        return id;
    }

    public int updateCard(Card card) {
        ContentValues cv = new ContentValues();
        cv.put("name", card.displayName());
        cv.put("issue_date", card.issueDate == null ? "" : card.issueDate);
        int rows = getWritableDatabase().update("cards", cv, "_id=?",
                new String[]{String.valueOf(card.id)});
        REVISION.incrementAndGet();
        return rows;
    }

    public int updateCardIssueDate(long cardId, String issueDate) {
        ContentValues cv = new ContentValues();
        cv.put("issue_date", issueDate == null ? "" : issueDate);
        int rows = getWritableDatabase().update("cards", cv, "_id=?",
                new String[]{String.valueOf(cardId)});
        REVISION.incrementAndGet();
        return rows;
    }

    /** 某张卡的账单笔数。 */
    public int countOrders(long cardId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM orders WHERE card_id=?", new String[]{String.valueOf(cardId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 删除卡片及其全部订单（含附件记录），返回需清理的附件文件路径。
     * 调用方负责弹出确认，并保证不删最后一张卡。
     */    public List<String> deleteCard(long cardId) {
        SQLiteDatabase db = getWritableDatabase();
        List<String> paths = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT a.path FROM attachments a JOIN orders o ON o._id=a.order_id " +
                "WHERE o.card_id=?", new String[]{String.valueOf(cardId)});
        try {
            while (c.moveToNext()) paths.add(c.getString(0));
        } finally {
            c.close();
        }
        db.delete("attachments", "order_id IN (SELECT _id FROM orders WHERE card_id=?)",
                new String[]{String.valueOf(cardId)});
        db.delete("orders", "card_id=?", new String[]{String.valueOf(cardId)});
        db.delete("cards", "_id=?", new String[]{String.valueOf(cardId)});
        REVISION.incrementAndGet();
        return paths;
    }

    // ---------- 统计 ----------

    /**
     * 年度返现结果（带缓存）。
     *
     * 同一 (年份, 赏世界报名) 组合只批量计算一次；任何写操作会让全局版本号 +1，
     * 下次调用自动重算，因此跨 Activity 的实例也不会读到脏数据。
     *
     * @return Map&lt;orderId, OrderResult&gt;
     */
    public java.util.Map<Long, Cashback.OrderResult> yearResults(int year, long cardId, boolean shopWorldActive) {
        // 规则换代后旧结果全部作废（服务端规则更新需要立即生效）
        long gen = Cashback.rulesGeneration();
        if (gen != rulesGenCache) {
            resultCache.clear();
            rulesGenCache = gen;
            cacheRevision = -1;
        }
        long rev = REVISION.get();
        if (rev != cacheRevision) {
            resultCache.clear();
            cacheRevision = rev;
        }
        String key = year + "|" + cardId + "|" + shopWorldActive;
        java.util.Map<Long, Cashback.OrderResult> cached = resultCache.get(key);
        if (cached != null) return cached;

        java.util.Map<Long, Cashback.OrderResult> computed =
                Cashback.computeYear(getOrdersByYearLight(year, cardId), shopWorldActive);
        resultCache.put(key, computed);
        return computed;
    }

    /** 主动清空返现缓存（一般不需要，写操作已自动失效）。 */
    public void invalidateCache() {
        resultCache.clear();
        cacheRevision = -1;
    }

    /**
     * 月度汇总：[净签账总额, 预计返现总额]（1:1 计）。
     * shopWorldActive 为该月所属年度的赏世界报名状态。
     * 返现考虑年度额度累计封顶（支付加成 80,000 / 赏世界 100,000）。
     */
    public double[] monthStats(String month, long cardId, boolean shopWorldActive) {
        int year;
        try { year = Integer.parseInt(month.substring(0, 4)); }
        catch (Exception e) { year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR); }

        // 年度结果走缓存，月度订单不再加载附件
        java.util.Map<Long, Cashback.OrderResult> yearResults = yearResults(year, cardId, shopWorldActive);

        List<Order> monthOrders = getOrdersByMonthLight(month, cardId);
        double total = 0, cashback = 0;
        for (Order o : monthOrders) {
            total += o.netAmount();
            Cashback.OrderResult r = yearResults.get(o.id);
            if (r != null) cashback += r.cashback;
        }
        return new double[]{Cashback.round2(total), Cashback.round2(cashback)};
    }
}
