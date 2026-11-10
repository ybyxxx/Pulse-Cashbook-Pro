package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * 返现明细页：当月返现构成环形图（高对比四色 + 弧上百分比）+ 每日返现柱状图 + 分项明细。
 * 由主页「本月预计返现」点击进入。
 */
public class CashbackDetailActivity extends Activity {

    /** 四项构成固定高对比配色：红 / 青 / 琥珀 / 紫。 */
    private static final int COLOR_BASE = 0xFFDB0011;
    private static final int COLOR_PAY = 0xFF0891B2;
    private static final int COLOR_WORLD = 0xFFF59E0B;
    private static final int COLOR_DINING = 0xFF7C3AED;

    private CashbookDb db;
    private int year;
    private int month;

    private TextView tvTitle, tvTotal, tvSub, tvThreshold, tvShopWorld;
    private LinearLayout layoutRows;
    private FrameLayout layoutCompose, layoutDaily;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cashback_detail);

        db = new CashbookDb(this);
        year = getIntent().getIntExtra("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        month = getIntent().getIntExtra("month", java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1);

        Themer.applyStatusBar(this);

        tvTitle = findViewById(R.id.tv_title);
        tvTotal = findViewById(R.id.tv_total_cashback);
        tvSub = findViewById(R.id.tv_total_sub);
        tvThreshold = findViewById(R.id.tv_threshold_status);
        tvShopWorld = findViewById(R.id.tv_shopworld_status);
        layoutRows = findViewById(R.id.layout_compose_rows);
        layoutCompose = findViewById(R.id.layout_compose_chart);
        layoutDaily = findViewById(R.id.layout_daily_chart);

        // 主题着色
        TextView back = findViewById(R.id.tv_back);
        Themer.tintText(back, this);
        Themer.tintCardLight(findViewById(R.id.card_total), this);
        Themer.tintText(tvTotal, this);
        Themer.tintTextDark(tvSub, this);

        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        bind();
    }

    private void bind() {
        String monthKey = String.format(java.util.Locale.US, "%04d-%02d", year, month);
        boolean sw = Prefs.isShopWorld(this, year);
        long cardId = Prefs.getCurrentCardId(this);

        Card card = db.getCard(cardId);
        String cardName = card == null ? "" : card.displayName();
        tvTitle.setText("返现明细 · " + year + " 年 " + month + " 月"
                + (cardName.isEmpty() ? "" : " · " + cardName));

        List<Order> orders = db.getOrdersByMonth(monthKey, cardId);

        // 年度批量计算（额度感知，走 DB 缓存）
        java.util.Map<Long, Cashback.OrderResult> yearResults = db.yearResults(year, cardId, sw);

        // 汇总（净额口径，与主页一致）
        double refund = 0, net = 0, totalCb = 0;
        double[] compSum = new double[4]; // base / pay / world / dining
        double[] dayCb = new double[31];
        for (Order o : orders) {
            refund += o.refundAmount;
            net += o.netAmount();
        }
        boolean met = net >= Cashback.threshold();
        for (Order o : orders) {
            Cashback.OrderResult r = yearResults.get(o.id);
            if (r == null) continue;
            totalCb += r.cashback;
            for (int i = 0; i < 4; i++) compSum[i] += r.components[i];
            try {
                int day = Integer.parseInt(o.date.substring(8, 10));
                if (day >= 1 && day <= 31) dayCb[day - 1] += r.cashback;
            } catch (Exception ignored) {}
        }
        refund = Cashback.round2(refund);
        net = Cashback.round2(net);
        totalCb = Cashback.round2(totalCb);
        for (int i = 0; i < 4; i++) compSum[i] = Cashback.round2(compSum[i]);

        tvTotal.setText("¥" + Cashback.format(totalCb));
        StringBuilder sub = new StringBuilder();
        sub.append("净签账 ¥").append(Cashback.format(net));
        sub.append(" · 共 ").append(orders.size()).append(" 笔");
        if (refund > 0) sub.append(" · 退款 ¥").append(Cashback.format(refund));
        tvSub.setText(sub.toString());

        // 构成环形图（红/青/琥珀/紫 高对比四色，弧上标注百分比，代码动态创建）
        int[] colors = {COLOR_BASE, COLOR_PAY, COLOR_WORLD, COLOR_DINING};
        double compTotal = compSum[0] + compSum[1] + compSum[2] + compSum[3];
        layoutCompose.removeAllViews();
        DonutView donut = new DonutView(this);
        donut.setData(compSum, colors, "¥" + Cashback.format(totalCb), "预计返现");
        layoutCompose.addView(donut, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 分项图例行
        layoutRows.removeAllViews();
        String[] names = {
                getString(R.string.cb_detail_base),
                getString(R.string.cb_detail_pay),
                getString(R.string.cb_detail_world),
                getString(R.string.cb_detail_dining),
        };
        for (int i = 0; i < 4; i++) {
            layoutRows.addView(buildComposeRow(names[i], compSum[i], compTotal, colors[i]));
        }

        // 每日柱状图（代码动态创建，避免 XML 膨胀问题）
        layoutDaily.removeAllViews();
        int daysInMonth = daysOf(year, month);
        DailyBarsView bars = new DailyBarsView(this);
        bars.setData(dayCb, daysInMonth, Themer.primary(this));
        layoutDaily.addView(bars, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 状态行
        if (met) {
            tvThreshold.setText(R.string.threshold_met);
        } else {
            tvThreshold.setText("本月累计 " + Cashback.format(net) + " / " + Cashback.n0(Cashback.threshold()) + " · 还差 " + Cashback.format(Cashback.threshold() - net));
        }
        tvShopWorld.setText(sw
                ? "赏世界 2%：" + year + " 年度已报名，本月生效"
                : "赏世界 2%：" + year + " 年度未报名（主页可开关）");
    }

    private int daysOf(int y, int m) {
        boolean leap = (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0));
        switch (m) {
            case 1: case 3: case 5: case 7: case 8: case 10: case 12: return 31;
            case 4: case 6: case 9: case 11: return 30;
            case 2: return leap ? 29 : 28;
            default: return 30;
        }
    }

    /** 分项图例行：色点 + 名称 + 金额 + 占比。 */
    private View buildComposeRow(String name, double value, double total, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(13);
        dot.setTextColor(color);
        row.addView(dot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(13);
        tvName.setTextColor(0xFF333333);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        nameLp.setMarginStart(dp(8));
        row.addView(tvName, nameLp);

        TextView tvValue = new TextView(this);
        tvValue.setText("¥" + Cashback.format(value));
        tvValue.setTextSize(13);
        tvValue.setTextColor(0xFF333333);
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(tvValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvShare = new TextView(this);
        double share = total > 0 ? value / total * 100 : 0;
        tvShare.setText(String.format(java.util.Locale.US, "  %.0f%%", share));
        tvShare.setTextSize(12);
        tvShare.setTextColor(0xFF999999);
        row.addView(tvShare, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
