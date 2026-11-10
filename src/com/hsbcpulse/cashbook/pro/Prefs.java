package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局设置：赏世界活动按年度报名。 */
public final class Prefs {

    private static final String NAME = "cashbook_settings";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 该年度是否已报名赏世界活动。 */
    public static boolean isShopWorld(Context ctx, int year) {
        return sp(ctx).getBoolean("shopworld_" + year, false);
    }

    public static void setShopWorld(Context ctx, int year, boolean enabled) {
        sp(ctx).edit().putBoolean("shopworld_" + year, enabled).apply();
    }

    /** 主题颜色索引（Themer.COLORS）。 */
    public static int getThemeIndex(Context ctx) {
        return sp(ctx).getInt("theme_index", 0);
    }

    public static void setThemeIndex(Context ctx, int index) {
        sp(ctx).edit().putInt("theme_index", index).apply();
    }

    /** 当前选中的卡片 id（多卡支持）。 */
    public static long getCurrentCardId(Context ctx) {
        return sp(ctx).getLong("current_card_id", 1);
    }

    public static void setCurrentCardId(Context ctx, long id) {
        sp(ctx).edit().putLong("current_card_id", id).apply();
    }

    /** 下卡日期 yyyy-MM-dd（旧版全局设置，仅供 v4 迁移时读取）。 */
    public static String getCardIssueDate(Context ctx) {
        return sp(ctx).getString("card_issue_date", "");
    }

    public static void setCardIssueDate(Context ctx, String date) {
        sp(ctx).edit().putString("card_issue_date", date == null ? "" : date).apply();
    }

    /** 迎新活动模式开关。 */
    public static boolean isWelcomeEnabled(Context ctx) {
        return sp(ctx).getBoolean("welcome_enabled", false);
    }

    public static void setWelcomeEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean("welcome_enabled", enabled).apply();
    }

    /** 自定义快捷按钮（JSON 数组字符串）。 */
    public static String getQuickButtons(Context ctx) {
        return sp(ctx).getString("quick_buttons", "[]");
    }

    public static void setQuickButtons(Context ctx, String json) {
        sp(ctx).edit().putString("quick_buttons", json == null ? "[]" : json).apply();
    }
}
