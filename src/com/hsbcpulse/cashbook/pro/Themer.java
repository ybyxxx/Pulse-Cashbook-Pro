package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/** 主题颜色：6 套预设色，运行时即时生效。 */
public final class Themer {

    public static final String[] NAMES = {"经典红", "深海蓝", "墨绿", "曜石黑", "琥珀橙", "紫罗兰"};
    public static final int[] COLORS = {
            0xFFDB0011, // 经典红
            0xFF1D5FD0, // 深海蓝
            0xFF0E7A3C, // 墨绿
            0xFF1F2937, // 曜石黑
            0xFFC26100, // 琥珀橙
            0xFF6D28D9, // 紫罗兰
    };

    private Themer() {}

    public static int index(Context ctx) {
        int i = Prefs.getThemeIndex(ctx);
        return (i >= 0 && i < COLORS.length) ? i : 0;
    }

    public static int primary(Context ctx) {
        return COLORS[index(ctx)];
    }

    /** 深色主色（状态栏、深色文字）。 */
    public static int primaryDark(Context ctx) {
        return blend(primary(ctx), 0xFF000000, 0.25f);
    }

    /** 浅色主色（卡片底、日期块）。 */
    public static int primaryLight(Context ctx) {
        return blend(primary(ctx), 0xFFFFFFFF, 0.88f);
    }

    public static int blend(int from, int to, float ratio) {
        int a = (int) (((from >>> 24) & 0xFF) * (1 - ratio) + ((to >>> 24) & 0xFF) * ratio);
        int r = (int) (((from >>> 16) & 0xFF) * (1 - ratio) + ((to >>> 16) & 0xFF) * ratio);
        int g = (int) (((from >>> 8) & 0xFF) * (1 - ratio) + ((to >>> 8) & 0xFF) * ratio);
        int b = (int) ((from & 0xFF) * (1 - ratio) + (to & 0xFF) * ratio);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void applyStatusBar(Activity a) {
        a.getWindow().setStatusBarColor(primaryDark(a));
    }

    /** 给圆角矩形按钮上主色。 */
    public static void tintButton(View v, Context ctx) {
        Drawable d = v.getBackground();
        if (d != null) {
            Drawable mut = d.mutate();
            if (mut instanceof GradientDrawable) {
                ((GradientDrawable) mut).setColor(primary(ctx));
            }
        }
    }

    /** 给圆角矩形卡片上浅主色。 */
    public static void tintCardLight(View v, Context ctx) {
        Drawable d = v.getBackground();
        if (d != null) {
            Drawable mut = d.mutate();
            if (mut instanceof GradientDrawable) {
                ((GradientDrawable) mut).setColor(primaryLight(ctx));
            }
        }
    }

    public static void tintText(TextView tv, Context ctx) {
        tv.setTextColor(primary(ctx));
    }

    public static void tintTextDark(TextView tv, Context ctx) {
        tv.setTextColor(primaryDark(ctx));
    }

    /** 主界面：顶栏与主按钮着色。 */
    public static void applyMain(Activity a) {
        View header = a.findViewById(R.id.header);
        if (header != null) header.setBackgroundColor(primary(a));
        View btnAdd = a.findViewById(R.id.btn_add);
        if (btnAdd != null) tintButton(btnAdd, a);
    }

    /** 订单编辑页着色。 */
    public static void applyEdit(Activity a) {
        TextView back = a.findViewById(R.id.tv_back);
        if (back != null) tintText(back, a);
        View card = a.findViewById(R.id.card_cashback);
        if (card != null) tintCardLight(card, a);
        TextView total = a.findViewById(R.id.tv_cashback_total);
        if (total != null) tintText(total, a);
        TextView detail = a.findViewById(R.id.tv_cashback_detail);
        if (detail != null) tintTextDark(detail, a);
        TextView th = a.findViewById(R.id.tv_threshold_status);
        if (th != null) tintTextDark(th, a);
        TextView sw = a.findViewById(R.id.tv_shopworld_status);
        if (sw != null) tintTextDark(sw, a);
        View btn = a.findViewById(R.id.btn_save);
        if (btn != null) tintButton(btn, a);
    }
}
