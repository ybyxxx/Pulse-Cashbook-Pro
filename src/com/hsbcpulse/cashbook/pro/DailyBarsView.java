package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** 每日返现柱状图（当月 1~31 日）。纯 Canvas 绘制。 */
public class DailyBarsView extends View {

    private double[] dayValues = new double[31];
    private int barColor = 0xFFDB0011;
    private int daysInMonth = 30;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DailyBarsView(Context context) { super(context); init(); }
    public DailyBarsView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        basePaint.setColor(0xFFDDDDDD);
        basePaint.setStrokeWidth(1f);
        labelPaint.setColor(0xFF999999);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(getResources().getDisplayMetrics().density * 9);
    }

    /** 设置每日返现值（下标 0 = 1 日）与当月天数。 */
    public void setData(double[] dayValues, int daysInMonth, int barColor) {
        this.dayValues = dayValues != null ? dayValues : new double[31];
        this.daysInMonth = Math.max(28, Math.min(daysInMonth, 31));
        this.barColor = barColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float baseY = getPaddingTop() + h * 0.82f;   // 基线留出底部标签
        float topY = getPaddingTop() + h * 0.06f;

        double max = 0;
        for (int i = 0; i < daysInMonth && i < dayValues.length; i++) {
            if (dayValues[i] > max) max = dayValues[i];
        }

        // 基线
        canvas.drawLine(getPaddingLeft(), baseY, getPaddingLeft() + w, baseY, basePaint);

        float slot = w / daysInMonth;
        float barW = Math.max(slot * 0.55f, 2f);
        barPaint.setColor(barColor);
        for (int i = 0; i < daysInMonth; i++) {
            double v = i < dayValues.length ? dayValues[i] : 0;
            if (v <= 0) continue;
            float bh = max > 0 ? (float) ((v / max) * (baseY - topY)) : 0;
            float cx = getPaddingLeft() + slot * (i + 0.5f);
            canvas.drawRect(cx - barW / 2f, baseY - Math.max(bh, 2f), cx + barW / 2f, baseY, barPaint);
        }

        // 日期刻度：1 / 10 / 20 / 月末
        int[] ticks = {1, 10, 20, daysInMonth};
        for (int t : ticks) {
            float cx = getPaddingLeft() + slot * (t - 0.5f);
            canvas.drawText(String.valueOf(t), cx, getPaddingTop() + h, labelPaint);
        }
    }
}
