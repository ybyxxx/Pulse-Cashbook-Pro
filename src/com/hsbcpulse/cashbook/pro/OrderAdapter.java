package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * 订单列表适配器（按日归类）：行类型为 DayRow（日期头）或 Order。
 */
public class OrderAdapter extends BaseAdapter {

    /** 行内删除按钮回调（滑动揭示后点击触发）。 */
    public interface OnRowDeleteListener {
        void onDeleteClick(Order o);
    }

    /** 日汇总行。 */
    public static class DayRow {
        public String label;      // "11月9日 · 周日 · 2 笔"
        public double total;
        public double cashback;

        public DayRow(String label, double total, double cashback) {
            this.label = label;
            this.total = total;
            this.cashback = cashback;
        }
    }

    private static final int TYPE_DAY = 0;
    private static final int TYPE_ORDER = 1;

    private final Context ctx;
    private final List<Object> rows;
    private final java.util.Map<Long, Double> cashbackMap; // orderId → 预计算返现
    private final OnRowDeleteListener deleteListener;

    /** rows 为 DayRow 与 Order 交错的列表。cashbackMap 为年度额度感知的预计算返现。 */
    public OrderAdapter(Context ctx, List<Object> rows, java.util.Map<Long, Double> cashbackMap,
                        OnRowDeleteListener deleteListener) {
        this.ctx = ctx;
        this.rows = rows;
        this.cashbackMap = cashbackMap;
        this.deleteListener = deleteListener;
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        Object o = rows.get(position);
        return o instanceof Order ? ((Order) o).id : position;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof DayRow ? TYPE_DAY : TYPE_ORDER;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItemViewType(position) == TYPE_ORDER;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        int type = getItemViewType(position);
        if (type == TYPE_DAY) {
            DayHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(ctx).inflate(R.layout.item_day_header, parent, false);
                h = new DayHolder();
                h.tvLabel = convertView.findViewById(R.id.tv_day_label);
                h.tvTotal = convertView.findViewById(R.id.tv_day_total);
                h.tvCashback = convertView.findViewById(R.id.tv_day_cashback);
                convertView.setTag(h);
            } else {
                h = (DayHolder) convertView.getTag();
            }
            DayRow d = (DayRow) rows.get(position);
            h.tvLabel.setText(d.label);
            h.tvTotal.setText("¥ " + Cashback.format(d.total));
            h.tvCashback.setText("返现 " + Cashback.format(d.cashback));
            Themer.tintTextDark(h.tvLabel, ctx);
            return convertView;
        }

        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx).inflate(R.layout.item_order, parent, false);
            h = new Holder();
            h.tvNote = convertView.findViewById(R.id.tv_note);
            h.tvAmount = convertView.findViewById(R.id.tv_amount);
            h.tvCashback = convertView.findViewById(R.id.tv_cashback);
            h.chipPay = convertView.findViewById(R.id.chip_pay);
            h.chipRefund = convertView.findViewById(R.id.chip_refund);
            h.chipDining = convertView.findViewById(R.id.chip_dining);
            h.chipAttach = convertView.findViewById(R.id.chip_attach);
            h.btnDelete = convertView.findViewById(R.id.btn_row_delete);
            h.foreground = convertView.findViewById(R.id.layout_foreground);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        final Order o = (Order) rows.get(position);
        final View rowView = convertView;

        // 复用时复位滑动状态
        h.foreground.setTranslationX(0f);
        // 删除按钮与卡片同为 12dp 圆角，避免从卡片圆角处漏出红角
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(Themer.primary(ctx));
        btnBg.setCornerRadius(ctx.getResources().getDisplayMetrics().density * 12);
        h.btnDelete.setBackground(btnBg);
        h.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 双保险：仅当该行处于滑开状态才执行删除
                if (!SwipeRevealTouchListener.isRevealed(rowView)) return;
                if (deleteListener != null) deleteListener.onDeleteClick(o);
            }
        });
        // 收起状态下按钮不接收触摸，避免点击穿透到按钮导致误删
        h.btnDelete.setClickable(false);
        h.tvNote.setText(o.note == null || o.note.isEmpty() ? "（无备注）" : o.note);
        h.tvAmount.setText(o.formatAmount());

        double cb = cashbackMap != null && cashbackMap.containsKey(o.id)
                ? cashbackMap.get(o.id) : 0;
        h.tvCashback.setText("返现 " + o.currencySymbol() + Cashback.format(cb));
        h.tvCashback.setTextColor(Themer.primary(ctx));

        String shortLabel = o.methodShortLabel();
        if (shortLabel.isEmpty()) {
            h.chipPay.setVisibility(View.GONE);
        } else {
            h.chipPay.setText(shortLabel);
            h.chipPay.setVisibility(View.VISIBLE);
        }
        if (o.hasRefund()) {
            h.chipRefund.setText("已退 " + o.currencySymbol() + Cashback.format(o.refundAmount));
            h.chipRefund.setVisibility(View.VISIBLE);
        } else {
            h.chipRefund.setVisibility(View.GONE);
        }
        h.chipDining.setVisibility(o.dining ? View.VISIBLE : View.GONE);
        h.chipAttach.setVisibility(o.attachments != null && !o.attachments.isEmpty() ? View.VISIBLE : View.GONE);

        return convertView;
    }

    static class Holder {
        TextView tvNote, tvAmount, tvCashback;
        TextView chipPay, chipRefund, chipDining, chipAttach;
        TextView btnDelete;
        View foreground;
    }

    static class DayHolder {
        TextView tvLabel, tvTotal, tvCashback;
    }
}
