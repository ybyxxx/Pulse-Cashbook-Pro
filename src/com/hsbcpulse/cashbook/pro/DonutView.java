package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 返现构成环形图。纯 Canvas 绘制。
 * 使用高对比四色区分各段（颜色由调用方传入），大段弧上直接标注百分比。
 */
public class DonutView extends View {

    private double[] values = new double[]{0, 0, 0, 0};
    private int[] colors = new int[]{0xFFDB0011, 0xFF0891B2, 0xFFF59E0B, 0xFF7C3AED};
    private String centerValue = "";
    private String centerLabel = "";

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    public DonutView(Context context) {
        super(context);
        init();
    }

    private void init() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.BUTT);
        pctPaint.setColor(0xFFFFFFFF);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        pctPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF222222);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setColor(0xFF888888);
    }

    /** 设置数据：values 各项金额、colors 对应颜色、中心大字与小字。 */
    public void setData(double[] values, int[] colors, String centerValue, String centerLabel) {
        this.values = values != null ? values : new double[]{0, 0, 0, 0};
        if (colors != null) this.colors = colors;
        this.centerValue = centerValue == null ? "" : centerValue;
        this.centerLabel = centerLabel == null ? "" : centerLabel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float cx = getPaddingLeft() + w / 2f;
        float cy = getPaddingTop() + h / 2f;
        float radius = Math.min(w, h) / 2f;

        double total = 0;
        for (double v : values) total += v;

        float stroke = radius * 0.30f;
        arcPaint.setStrokeWidth(stroke);
        float ringRadius = radius - stroke / 2f - radius * 0.04f;
        oval.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        float textRadius = ringRadius; // 百分比标签位于环带中心

        if (total <= 0) {
            arcPaint.setColor(0xFFE8E8E8);
            canvas.drawArc(oval, 0, 360, false, arcPaint);
        } else {
            float start = -90f;
            float gap = 2f;
            pctPaint.setTextSize(radius * 0.17f);
            for (int i = 0; i < values.length; i++) {
                if (values[i] <= 0) continue;
                float sweep = (float) (values[i] / total * 360.0);
                arcPaint.setColor(colors[i % colors.length]);
                canvas.drawArc(oval, start + gap / 2f, Math.max(sweep - gap, 0.5f), false, arcPaint);
                // 大段弧上标注百分比
                double pct = values[i] / total * 100.0;
                if (pct >= 8.0) {
                    float mid = (float) Math.toRadians(start + sweep / 2f);
                    float tx = cx + (float) Math.cos(mid) * textRadius;
                    float ty = cy + (float) Math.sin(mid) * textRadius;
                    String label = String.format(java.util.Locale.US, "%.0f%%", pct);
                    float textH = pctPaint.descent() - pctPaint.ascent();
                    canvas.drawText(label, tx, ty + textH / 2f - pctPaint.descent(), pctPaint);
                }
                start += sweep;
            }
        }

        textPaint.setTextSize(radius * 0.32f);
        textPaint.setFakeBoldText(true);
        subTextPaint.setTextSize(radius * 0.15f);
        float vOffset = radius * 0.11f;
        canvas.drawText(centerValue, cx, cy + vOffset, textPaint);
        canvas.drawText(centerLabel, cx, cy + vOffset + radius * 0.25f, subTextPaint);
    }
}
