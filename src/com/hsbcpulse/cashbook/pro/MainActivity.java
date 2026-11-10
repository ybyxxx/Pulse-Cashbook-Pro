package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/** 主页：订单列表 + 当月消费/返现统计 + 赏世界年度报名开关。 */
public class MainActivity extends Activity {

    private CashbookDb db;
    private TextView tvMonth, tvSpend, tvCashback, tvThreshold, tvEmpty, tvShopWorldLabel, tvCard;
    private ProgressBar progress;
    private ListView listView;
    private Switch switchShopWorld;
    private OrderAdapter adapter;
    private SwipeRevealTouchListener swipeReveal;

    // 滑动删除撤销
    private LinearLayout layoutUndoBar;
    private TextView tvUndoMsg, tvUndoAction;
    private Order pendingDeleteOrder;
    private List<String> pendingDeleteFiles;
    private final Handler undoHandler = new Handler();
    private final Runnable undoTimeout = new Runnable() {
        @Override
        public void run() { finalizePendingDelete(); }
    };

    private int year;
    private int month; // 1-12
    /** 当前卡片 id（多卡支持）。 */
    private long cardId = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new CashbookDb(this);
        // 返现规则：本地缓存 → 内置兜底，随后在后台尝试同步服务端
        RulesStore.load(this);
        cardId = resolveCurrentCard();

        tvCard = findViewById(R.id.tv_card);
        tvCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showCardPicker(); }
        });

        tvMonth = findViewById(R.id.tv_month);
        tvSpend = findViewById(R.id.tv_month_spend);
        tvCashback = findViewById(R.id.tv_month_cashback);
        tvThreshold = findViewById(R.id.tv_threshold);
        tvEmpty = findViewById(R.id.tv_empty);
        progress = findViewById(R.id.progress_threshold);
        listView = findViewById(R.id.list_orders);
        tvShopWorldLabel = findViewById(R.id.tv_shopworld_label);
        switchShopWorld = findViewById(R.id.switch_shopworld);
        layoutUndoBar = findViewById(R.id.layout_undo_bar);
        tvUndoMsg = findViewById(R.id.tv_undo_msg);
        tvUndoAction = findViewById(R.id.tv_undo_action);

        Calendar cal = Calendar.getInstance();
        year = cal.get(Calendar.YEAR);
        month = cal.get(Calendar.MONTH) + 1;

        findViewById(R.id.btn_prev_month).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month--;
                if (month < 1) { month = 12; year--; }
                reload();
            }
        });
        findViewById(R.id.btn_next_month).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                month++;
                if (month > 12) { month = 1; year++; }
                reload();
            }
        });
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, EditOrderActivity.class));
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // 点击月份 → 手动选取年月
        tvMonth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMonthPicker();
            }
        });

        switchShopWorld.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setShopWorld(MainActivity.this, year, isChecked);
                Toast.makeText(MainActivity.this,
                        isChecked ? "赏世界 " + year + " 年度已报名" : "已取消赏世界 " + year + " 年度报名",
                        Toast.LENGTH_SHORT).show();
                reload();
            }
        });

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 行已滑开时：点击收起而不是进入编辑
                if (SwipeRevealTouchListener.isRevealed(view)) {
                    if (swipeReveal != null) swipeReveal.closeOpenRow();
                    return;
                }
                Intent it = new Intent(MainActivity.this, EditOrderActivity.class);
                it.putExtra("order_id", id);
                startActivity(it);
            }
        });

        // 自右向左滑动揭示删除按钮（日期头行不可滑动）；点按钮删除，不再弹窗确认
        swipeReveal = new SwipeRevealTouchListener(listView);
        listView.setOnTouchListener(swipeReveal);

        // 撤销条
        Themer.tintText(tvUndoAction, this);
        tvUndoAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { undoPendingDelete(); }
        });

        // 点击「本月预计返现」→ 返现明细页
        findViewById(R.id.layout_cashback_stat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, CashbackDetailActivity.class);
                it.putExtra("year", year);
                it.putExtra("month", month);
                startActivity(it);
            }
        });

        reload();
        syncRulesIfStale();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void deleteOrder(long id) {
        List<String> paths = db.deleteOrder(id);
        for (String p : paths) ImageUtil.deleteQuietly(p);
        Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
        reload();
    }

    // ---------- 滑动删除 + 撤销 ----------

    /** 行内删除按钮：备份订单与附件后删除数据行（文件延后清理），弹出撤销条。 */
    private void deleteOrderWithUndo(Order o) {
        if (o == null) return;
        finalizePendingDelete(); // 上一笔未撤销的直接落盘
        pendingDeleteOrder = db.getOrder(o.id); // 含附件路径
        pendingDeleteFiles = db.deleteOrder(o.id); // 删数据行，返回附件路径
        layoutUndoBar.setVisibility(View.VISIBLE);
        tvUndoMsg.setText(R.string.deleted_undo);
        reload();
        undoHandler.removeCallbacks(undoTimeout);
        undoHandler.postDelayed(undoTimeout, 4000);
    }

    private void undoPendingDelete() {
        undoHandler.removeCallbacks(undoTimeout);
        if (pendingDeleteOrder != null) {
            db.restoreOrder(pendingDeleteOrder);
            pendingDeleteOrder = null;
            pendingDeleteFiles = null;
        }
        layoutUndoBar.setVisibility(View.GONE);
        reload();
    }

    /** 撤销超时 / 被新删除顶替：真正清理截图文件。 */
    private void finalizePendingDelete() {
        undoHandler.removeCallbacks(undoTimeout);
        if (pendingDeleteFiles != null) {
            for (String p : pendingDeleteFiles) ImageUtil.deleteQuietly(p);
        }
        pendingDeleteOrder = null;
        pendingDeleteFiles = null;
        if (layoutUndoBar != null) layoutUndoBar.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        finalizePendingDelete();
        super.onDestroy();
    }

    // ---------- 规则同步 ----------

    /** 距上次成功同步超过 12 小时就在后台拉一次；失败静默，继续用旧规则。 */
    private void syncRulesIfStale() {
        if (RulesStore.url(this).isEmpty()) return;
        long last = getSharedPreferences("rules_store", MODE_PRIVATE)
                .getLong("rules_fetched_at", 0L);
        if (System.currentTimeMillis() - last < 12L * 60 * 60 * 1000) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                RulesStore.refresh(MainActivity.this, new RulesStore.Callback() {
                    @Override
                    public void onResult(boolean ok, String message, RuleSet ruleSet) {
                        if (!ok) return;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing()) reload();
                            }
                        });
                    }
                });
            }
        }).start();
    }

    // ---------- 多卡片 ----------

    /** 解析当前卡片：偏好值失效时回落到第一张，没有任何卡片则建一张默认卡。 */
    private long resolveCurrentCard() {
        long id = Prefs.getCurrentCardId(this);
        if (db.getCard(id) != null) return id;

        List<Card> cards = db.getCards();
        if (!cards.isEmpty()) {
            long first = cards.get(0).id;
            Prefs.setCurrentCardId(this, first);
            return first;
        }
        long created = db.insertCard("Pulse 主卡", Prefs.getCardIssueDate(this));
        Prefs.setCurrentCardId(this, created);
        return created;
    }

    private String currentIssueDate() {
        Card c = db.getCard(cardId);
        return c == null || c.issueDate == null ? "" : c.issueDate;
    }

    /** 卡片切换 / 管理入口。 */
    private void showCardPicker() {
        final List<Card> cards = db.getCards();
        final CharSequence[] items = new CharSequence[cards.size() + 1];
        for (int i = 0; i < cards.size(); i++) {
            Card c = cards.get(i);
            items[i] = (c.id == cardId ? "✓ " : "") + c.displayName();
        }
        items[cards.size()] = "⚙ 管理卡片…";

        new AlertDialog.Builder(this)
                .setTitle("选择卡片")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == cards.size()) {
                            startActivity(new Intent(MainActivity.this, CardsActivity.class));
                            return;
                        }
                        cardId = cards.get(which).id;
                        Prefs.setCurrentCardId(MainActivity.this, cardId);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void reload() {
        Themer.applyStatusBar(this);
        Themer.applyMain(this);

        String monthKey = String.format(java.util.Locale.US, "%04d-%02d", year, month);
        tvMonth.setText(year + " 年 " + month + " 月 ▾");

        // 当前卡片（可能在卡片管理页被删除）
        Card curCard = db.getCard(cardId);
        if (curCard == null) {
            cardId = resolveCurrentCard();
            curCard = db.getCard(cardId);
        }
        tvCard.setText((curCard == null ? "未命名卡片" : curCard.displayName()) + " ▾");

        boolean sw = Prefs.isShopWorld(this, year);
        // 先移除监听再设状态，避免误触发保存
        switchShopWorld.setOnCheckedChangeListener(null);
        switchShopWorld.setChecked(sw);
        switchShopWorld.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.setShopWorld(MainActivity.this, year, isChecked);
                Toast.makeText(MainActivity.this,
                        isChecked ? "赏世界 " + year + " 年度已报名" : "已取消赏世界 " + year + " 年度报名",
                        Toast.LENGTH_SHORT).show();
                reload();
            }
        });
        tvShopWorldLabel.setText("赏世界活动 · " + year + " 年度报名");

        List<Order> orders = db.getOrdersByMonth(monthKey, cardId);

        // 年度批量计算（额度感知，走 DB 缓存）→ 每笔返现 Map
        java.util.Map<Long, Cashback.OrderResult> yearResults = db.yearResults(year, cardId, sw);
        java.util.Map<Long, Double> cbMap = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, Cashback.OrderResult> e : yearResults.entrySet()) {
            cbMap.put(e.getKey(), e.getValue().cashback);
        }

        // 当月汇总直接从已有数据累加，避免重复查询
        double total = 0, cashback = 0;
        for (Order o : orders) {
            total += o.netAmount();
            Double cb = cbMap.get(o.id);
            if (cb != null) cashback += cb;
        }
        total = Cashback.round2(total);
        cashback = Cashback.round2(cashback);
        boolean met = total >= Cashback.threshold();

        tvSpend.setText(Cashback.format(total));
        tvCashback.setText(Cashback.format(cashback));

        if (met) {
            tvThreshold.setText(R.string.threshold_met);
        } else {
            tvThreshold.setText("满 " + Cashback.n0(Cashback.threshold()) + " 餐饮加码 · 还差 " + Cashback.format(Cashback.threshold() - total));
        }
        progress.setProgress((int) Math.min(total, Cashback.threshold()));

        // 按日归类构建列表行
        List<Object> rows = buildDayRows(orders, cbMap);
        adapter = new OrderAdapter(this, rows, cbMap, new OrderAdapter.OnRowDeleteListener() {
            @Override
            public void onDeleteClick(Order o) {
                deleteOrderWithUndo(o);
            }
        });
        listView.setAdapter(adapter);
        tvEmpty.setVisibility(orders.isEmpty() ? View.VISIBLE : View.GONE);

        updateWelcome();
    }

    /** 把订单按日归类：日期头（当日合计/返现）+ 订单行。cbMap 为预计算返现。 */
    private List<Object> buildDayRows(List<Order> orders, java.util.Map<Long, Double> cbMap) {
        List<Object> rows = new java.util.ArrayList<>();
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("M月d日 E", java.util.Locale.CHINA);
        String lastDate = null;
        OrderAdapter.DayRow current = null;
        for (Order o : orders) {
            if (!o.date.equals(lastDate)) {
                String label;
                try {
                    java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(o.date);
                    label = fmt.format(d);
                } catch (Exception e) {
                    label = o.date;
                }
                current = new OrderAdapter.DayRow(label, 0, 0);
                rows.add(current);
                lastDate = o.date;
            }
            current.total += o.netAmount();
            current.cashback += cbMap.containsKey(o.id) ? cbMap.get(o.id) : 0;
            rows.add(o);
        }
        if (current != null) {
            current.total = Cashback.round2(current.total);
            current.cashback = Cashback.round2(current.cashback);
        }
        return rows;
    }

    // ---------- 迎新活动面板 ----------

    private android.widget.LinearLayout layoutWelcome;
    private TextView tvWelcomeTitle, tvWelcomeReward, tvWelcomeDetail;

    private void updateWelcome() {
        if (layoutWelcome == null) {
            layoutWelcome = findViewById(R.id.layout_welcome);
            tvWelcomeTitle = findViewById(R.id.tv_welcome_title);
            tvWelcomeReward = findViewById(R.id.tv_welcome_reward);
            tvWelcomeDetail = findViewById(R.id.tv_welcome_detail);
            layoutWelcome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { showWelcomeDetail(); }
            });
        }

        if (!Prefs.isWelcomeEnabled(this)) {
            layoutWelcome.setVisibility(View.GONE);
            return;
        }
        layoutWelcome.setVisibility(View.VISIBLE);

        String issue = currentIssueDate();
        if (issue.isEmpty()) {
            tvWelcomeTitle.setText("迎新活动 · 未设置下卡日期");
            tvWelcomeReward.setText("");
            tvWelcomeDetail.setText("请到 卡片管理 中录入本卡下卡日期，开始 60 日迎新追踪");
            return;
        }

        Welcome.Result r = Welcome.compute(db.getAllOrdersLight(cardId), issue,
                java.time.LocalDate.now());

        if (r.expired) {
            tvWelcomeTitle.setText("迎新活动 · 已结束（60 日窗口已过）");
        } else if (r.inWindow) {
            tvWelcomeTitle.setText("迎新活动 · 剩余 " + r.daysLeft + " 天");
        } else {
            tvWelcomeTitle.setText("迎新活动 · " + r.windowStart + " 开始");
        }
        tvWelcomeReward.setText("预计奖赏 $" + Cashback.format(r.reward) + " / 1,080");
        tvWelcomeDetail.setText("签账 ¥" + Cashback.format(r.spend) + " / 8,000"
                + (r.spendMet ? " ✔" : "")
                + " · QR " + r.qrCounted + " / 28 次"
                + " · 窗口 " + r.windowStart + " ~ " + r.windowEnd);
    }

    private void showWelcomeDetail() {
        String issue = currentIssueDate();
        if (issue.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("迎新活动")
                    .setMessage("尚未录入本卡下卡日期。\n\n请到 卡片管理 中录入，迎新窗口自下卡日（含当日）起 60 个历日。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        Welcome.Result r = Welcome.compute(db.getAllOrdersLight(cardId), issue, java.time.LocalDate.now());
        View v = getLayoutInflater().inflate(R.layout.layout_welcome_detail, null);

        // 状态胶囊
        TextView tvStatus = v.findViewById(R.id.tv_wd_status);
        if (r.expired) {
            tvStatus.setText("已结束");
        } else if (r.inWindow) {
            tvStatus.setText("剩余 " + r.daysLeft + " 天");
        } else {
            tvStatus.setText("未开始");
        }
        TextView tvWindow = v.findViewById(R.id.tv_wd_window);
        tvWindow.setText(r.windowStart + " ~ " + r.windowEnd);

        // 大数字
        TextView tvReward = v.findViewById(R.id.tv_wd_reward);
        tvReward.setText("$" + Cashback.format(r.reward) + " / $1,080");
        tvReward.setTextColor(Themer.primary(this));

        // 基础迎新行
        TextView baseMark = v.findViewById(R.id.tv_wd_base_mark);
        TextView baseValue = v.findViewById(R.id.tv_wd_base_value);
        if (r.spendMet) {
            baseMark.setText("✓");
            baseMark.setTextColor(0xFF2E9E44);
            baseValue.setText("$800");
            baseValue.setTextColor(0xFF2E9E44);
        } else {
            baseMark.setText("○");
            baseMark.setTextColor(0xFFBBBBBB);
            baseValue.setText("待达标");
            baseValue.setTextColor(0xFFBBBBBB);
        }
        TextView tvBaseProgress = v.findViewById(R.id.tv_wd_base_progress);
        tvBaseProgress.setText(
                "签账 ¥" + Cashback.format(r.spend) + " / ¥8,000"
                        + (r.spendMet ? "" : " · 还差 ¥" + Cashback.format(Welcome.SPEND_TARGET - r.spend)));
        ProgressBar pbBase = v.findViewById(R.id.progress_wd_base);
        pbBase.setMax((int) Welcome.SPEND_TARGET);
        pbBase.setProgress((int) Math.min(r.spend, Welcome.SPEND_TARGET));
        pbBase.setProgressTintList(android.content.res.ColorStateList.valueOf(Themer.primary(this)));

        // 额外加码行
        TextView bonusMark = v.findViewById(R.id.tv_wd_bonus_mark);
        TextView bonusValue = v.findViewById(R.id.tv_wd_bonus_value);
        double bonus = r.spendMet ? r.qrCounted * Welcome.QR_REBATE : 0;
        if (r.spendMet && r.qrCounted >= Welcome.QR_MAX_TIMES) {
            bonusMark.setText("✓");
            bonusMark.setTextColor(0xFF2E9E44);
            bonusValue.setText("$280");
            bonusValue.setTextColor(0xFF2E9E44);
        } else if (bonus > 0) {
            bonusMark.setText("◐");
            bonusMark.setTextColor(Themer.primary(this));
            bonusValue.setText("$" + Cashback.format(bonus));
            bonusValue.setTextColor(Themer.primary(this));
        } else {
            bonusMark.setText("○");
            bonusMark.setTextColor(0xFFBBBBBB);
            bonusValue.setText("$0");
            bonusValue.setTextColor(0xFFBBBBBB);
        }
        TextView tvBonusProgress = v.findViewById(R.id.tv_wd_bonus_progress);
        tvBonusProgress.setText(
                r.qrCount + " / " + Welcome.QR_MAX_TIMES + " 次 · $"
                        + Cashback.format(bonus) + " / $280"
                        + (r.spendMet ? "" : "（需先满 8,000 签账）"));
        ProgressBar pbBonus = v.findViewById(R.id.progress_wd_bonus);
        pbBonus.setMax(Welcome.QR_MAX_TIMES);
        pbBonus.setProgress(r.qrCounted);
        pbBonus.setProgressTintList(android.content.res.ColorStateList.valueOf(Themer.primary(this)));

        new AlertDialog.Builder(this)
                .setTitle("迎新活动 · HSBCPULSE")
                .setView(v)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 年月选取弹窗：年份翻页 + 12 月网格。 */
    private void showMonthPicker() {
        final int[] pickYear = {year};
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(8));

        // 年份行
        LinearLayout yearRow = new LinearLayout(this);
        yearRow.setOrientation(LinearLayout.HORIZONTAL);
        yearRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView prev = new TextView(this);
        prev.setText("‹");
        prev.setTextSize(24);
        prev.setGravity(Gravity.CENTER);
        prev.setPadding(dp(16), dp(8), dp(16), dp(8));
        yearRow.addView(prev, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView tvYear = new TextView(this);
        tvYear.setTextSize(17);
        tvYear.setGravity(Gravity.CENTER);
        tvYear.setTextColor(0xFF1A1A1A);
        yearRow.addView(tvYear, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView next = new TextView(this);
        next.setText("›");
        next.setTextSize(24);
        next.setGravity(Gravity.CENTER);
        next.setPadding(dp(16), dp(8), dp(16), dp(8));
        yearRow.addView(next, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(yearRow);

        // 月份网格 3 列 x 4 行
        final TextView[][] monthViews = new TextView[12][1];
        final int primary = Themer.primary(this);
        final int primaryLight = Themer.primaryLight(this);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(10), 0, 0);

        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int m = r * 3 + c + 1;
                TextView cell = new TextView(this);
                cell.setText(m + " 月");
                cell.setTextSize(14);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(0, dp(12), 0, dp(12));
                monthViews[m - 1][0] = cell;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        year = pickYear[0];
                        month = m;
                        reload();
                        dismissPicker();
                    }
                });
                row.addView(cell, lp);
            }
            grid.addView(row);
        }
        root.addView(grid);

        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                tvYear.setText(pickYear[0] + " 年");
                for (int i = 0; i < 12; i++) {
                    TextView cell = monthViews[i][0];
                    boolean selected = (pickYear[0] == year && i + 1 == month);
                    cell.setTextColor(selected ? 0xFFFFFFFF : 0xFF1A1A1A);
                    cell.setBackgroundColor(selected ? primary : primaryLight);
                }
            }
        };
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickYear[0]--;
                refresh.run();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickYear[0]++;
                refresh.run();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .setNegativeButton(R.string.cancel, null)
                .create();
        refresh.run();
        dialog.show();
        pendingDialog = dialog;
    }

    private AlertDialog pendingDialog;

    private void dismissPicker() {
        if (pendingDialog != null) {
            pendingDialog.dismiss();
            pendingDialog = null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
