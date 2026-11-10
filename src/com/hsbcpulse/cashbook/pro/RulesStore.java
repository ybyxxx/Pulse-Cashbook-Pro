package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 返现规则仓库：内置规则兜底 → 本地缓存 → 服务端下发。
 *
 * 加载顺序（{@link #load}）：
 *   本地缓存（上次成功下载并校验通过的规则）→ 内置规则。
 *
 * 刷新（{@link #refresh}，需在后台线程调用）：
 *   GET 规则地址，带 If-None-Match 做条件请求；
 *   200 → 解析 + 严格校验 → 通过才落盘并注入，脏数据一律丢弃、继续用旧规则；
 *   304 → 视为已是最新；网络/解析失败 → 保留旧规则，返回失败原因。
 *
 * 只下载规则文本，不上传任何本地数据。
 */
public final class RulesStore {

    /** 规则地址（可在设置里修改）。默认指向项目仓库的 rules.json。 */
    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/ybyxxx/Pulse-Cashbook-Pro/main/rules.json";

    private static final String CACHE_FILE = "rules_cache.json";
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 256 * 1024;

    private RulesStore() {}

    // ---------- 加载 ----------

    /** 载入当前生效规则并注入 Cashback，返回实际使用的规则集。 */
    public static RuleSet load(Context ctx) {
        RuleSet cached = readCache(ctx);
        RuleSet rs = cached != null ? cached : RuleSet.builtin();
        Cashback.setRules(rs);
        return rs;
    }

    private static RuleSet readCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return null;
        try {
            String json = new String(readAll(new FileInputStream(f)), StandardCharsets.UTF_8);
            return RuleSet.parse(json);
        } catch (Exception e) {
            f.delete(); // 缓存损坏直接丢弃，下次重新拉取
            return null;
        }
    }

    // ---------- 刷新 ----------

    public interface Callback {
        /** ok=true 时 message 为结果说明；ruleSet 为当前生效规则。 */
        void onResult(boolean ok, String message, RuleSet ruleSet);
    }

    /** 在后台线程执行网络请求；回调也在后台线程，调用方自行切回 UI。 */
    public static void refresh(Context ctx, Callback cb) {
        String url = url(ctx);
        if (url.isEmpty()) {
            cb.onResult(false, "未配置规则地址", Cashback.rules());
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "PulseCashbookPro/" + CsvExport.APP_VERSION);
            String etag = prefs(ctx).getString("rules_etag", "");
            if (!etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                cb.onResult(true, "已是最新规则", Cashback.rules());
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                cb.onResult(false, "服务器返回 HTTP " + code, Cashback.rules());
                return;
            }

            String body = new String(readLimited(conn.getInputStream()), StandardCharsets.UTF_8);
            RuleSet rs;
            try {
                rs = RuleSet.parse(body);
            } catch (Exception e) {
                cb.onResult(false, "规则格式不合法：" + e.getMessage(), Cashback.rules());
                return;
            }
            if (writeCache(ctx, body)) {
                SharedPreferences.Editor ed = prefs(ctx).edit();
                String newEtag = conn.getHeaderField("ETag");
                if (newEtag != null) ed.putString("rules_etag", newEtag);
                ed.putString("rules_version", rs.version);
                ed.putString("rules_source", url);
                ed.putLong("rules_fetched_at", System.currentTimeMillis());
                ed.apply();
            }
            Cashback.setRules(rs);
            cb.onResult(true, "已更新到规则 " + rs.version, rs);
        } catch (Throwable t) {
            cb.onResult(false, "网络异常：" + t.getClass().getSimpleName(), Cashback.rules());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- 元信息 ----------

    public static String url(Context ctx) {
        String v = prefs(ctx).getString("rules_url", DEFAULT_URL);
        return v == null ? "" : v.trim();
    }

    public static void setUrl(Context ctx, String url) {
        prefs(ctx).edit().putString("rules_url", url == null ? "" : url.trim()).apply();
    }

    /** 设置页展示用：规则来源与版本。 */
    public static String describe(Context ctx, RuleSet rs) {
        SharedPreferences sp = prefs(ctx);
        String fetched = sp.getString("rules_source", "");
        long at = sp.getLong("rules_fetched_at", 0);
        boolean fromServer = !fetched.isEmpty() && at > 0;
        StringBuilder sb = new StringBuilder();
        sb.append("当前规则：").append(rs.version.isEmpty() ? "内置" : rs.version);
        sb.append("\n").append(rs.summary());
        if (fromServer) {
            sb.append("\n来源：服务端\n更新时间：").append(timeText(at));
        } else {
            sb.append("\n来源：内置默认规则（未同步）");
        }
        String u = url(ctx);
        sb.append("\n地址：").append(u.isEmpty() ? "未配置" : u);
        return sb.toString();
    }

    private static String timeText(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(millis));
    }

    // ---------- 文件 ----------

    private static boolean writeCache(Context ctx, String json) {
        File tmp = new File(ctx.getFilesDir(), CACHE_FILE + ".tmp");
        File dst = new File(ctx.getFilesDir(), CACHE_FILE);
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            os = null;
            return tmp.renameTo(dst) || tmp.renameTo(dst);
        } catch (Exception e) {
            return false;
        } finally {
            if (os != null) {
                try { os.close(); } catch (Exception ignored) {}
            }
            if (tmp.exists() && !dst.exists()) tmp.delete();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    /** 限长读取，防止异常大的响应撑爆内存。 */
    private static byte[] readLimited(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IllegalStateException("规则文件过大");
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("rules_store", Context.MODE_PRIVATE);
    }
}
