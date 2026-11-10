package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;

/** 记一笔 / 编辑订单。 */
public class EditOrderActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 1001;

    private CashbookDb db;
    private long orderId = -1;
    private Order order;
    /** 本单归属卡片（新建时取当前选中卡片，编辑时沿用原卡片）。 */
    private long cardId = 1;

    private TextView tvDate, tvTitle, tvDelete, tvCashbackTotal, tvCashbackDetail, tvThresholdStatus, tvShopWorldStatus;
    private EditText etAmount, etNote, etRefund;
    private Spinner spinnerCurrency;
    private RadioGroup rgPayMethod;
    private CheckBox cbPayConfirmed, cbDining;
    private LinearLayout layoutAttachments, layoutRefund, layoutQuickButtons;

    private final List<String> attachments = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        db = new CashbookDb(this);
        orderId = getIntent().getLongExtra("order_id", -1);
        cardId = Prefs.getCurrentCardId(this);

        Themer.applyStatusBar(this);
        Themer.applyEdit(this);

        tvTitle = findViewById(R.id.tv_title);
        tvDate = findViewById(R.id.tv_date);
        tvDelete = findViewById(R.id.tv_delete);
        tvCashbackTotal = findViewById(R.id.tv_cashback_total);
        tvCashbackDetail = findViewById(R.id.tv_cashback_detail);
        tvThresholdStatus = findViewById(R.id.tv_threshold_status);
        tvShopWorldStatus = findViewById(R.id.tv_shopworld_status);
        etAmount = findViewById(R.id.et_amount);
        etNote = findViewById(R.id.et_note);
        etRefund = findViewById(R.id.et_refund);
        layoutRefund = findViewById(R.id.layout_refund);
        spinnerCurrency = findViewById(R.id.spinner_currency);
        rgPayMethod = findViewById(R.id.rg_pay_method);
        cbPayConfirmed = findViewById(R.id.cb_pay_confirmed);
        cbDining = findViewById(R.id.cb_dining);
        layoutAttachments = findViewById(R.id.layout_attachments);
        layoutQuickButtons = findViewById(R.id.layout_quick_buttons);

        // 币种：CNY 优先
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, new String[]{"CNY", "HKD"});
        spinnerCurrency.setAdapter(currencyAdapter);

        // 加载已有订单
        if (orderId > 0) {
            order = db.getOrder(orderId);
            if (order == null) { finish(); return; }
            cardId = order.cardId;
            tvTitle.setText(R.string.edit_order);
            tvDelete.setVisibility(View.VISIBLE);
            layoutRefund.setVisibility(View.VISIBLE);
            tvDate.setText(order.date);
            etAmount.setText(Cashback.format(order.amount));
            etRefund.setText(order.refundAmount > 0 ? Cashback.format(order.refundAmount) : "");
            etNote.setText(order.note);
            spinnerCurrency.setSelection("HKD".equals(order.currency) ? 1 : 0);
            checkPayMethod(order.payMethod);
            cbPayConfirmed.setChecked(order.payConfirmed);
            cbDining.setChecked(order.dining);
            attachments.addAll(order.attachments);
        } else {
            order = new Order();
            tvDate.setText(today());
            spinnerCurrency.setSelection(0); // 默认 CNY
        }

        updateConfirmVisibility();
        renderAttachments();
        renderQuickButtons();
        refreshCalc();

        // 日期选择
        tvDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });

        // 实时计算
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshCalc(); }
        };
        etAmount.addTextChangedListener(watcher);
        etRefund.addTextChangedListener(watcher);

        rgPayMethod.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                updateConfirmVisibility();
                refreshCalc();
            }
        });
        cbPayConfirmed.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                refreshCalc();
            }
        });
        cbDining.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                refreshCalc();
            }
        });

        findViewById(R.id.tv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        tvDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(EditOrderActivity.this)
                        .setTitle(R.string.confirm_delete_title)
                        .setMessage(R.string.confirm_delete_msg)
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                List<String> paths = db.deleteOrder(orderId);
                                for (String p : paths) ImageUtil.deleteQuietly(p);
                                Toast.makeText(EditOrderActivity.this, R.string.deleted, Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { save(); }
        });
    }

    private String today() {
        Calendar cal = Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private void pickDate() {
        String[] parts = tvDate.getText().toString().split("-");
        int y = parts.length > 0 ? parse(parts[0], 2026) : 2026;
        int m = parts.length > 1 ? parse(parts[1], 1) - 1 : 0;
        int d = parts.length > 2 ? parse(parts[2], 1) : 1;
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                tvDate.setText(String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth));
                refreshCalc();
            }
        }, y, m, d).show();
    }

    private int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------- 支付方式单选 ----------

    private void checkPayMethod(String method) {
        String n = Order.normalizePayMethod(method);
        int id = -1;
        if (Order.PAY_APPLE.equals(n)) id = R.id.rb_apple;
        else if (Order.PAY_UNION_QR.equals(n)) id = R.id.rb_unionqr;
        else if (Order.PAY_ALIPAY_WECHAT.equals(n)) id = R.id.rb_alipaywechat;
        else if (Order.PAY_JUMP_UNION.equals(n)) id = R.id.rb_jump_union;
        else if (Order.PAY_ONLINE_CARD.equals(n)) id = R.id.rb_online_card;
        if (id > 0) rgPayMethod.check(id);
    }

    private String selectedPayMethod() {
        int id = rgPayMethod.getCheckedRadioButtonId();
        if (id == R.id.rb_apple) return Order.PAY_APPLE;
        if (id == R.id.rb_unionqr) return Order.PAY_UNION_QR;
        if (id == R.id.rb_alipaywechat) return Order.PAY_ALIPAY_WECHAT;
        if (id == R.id.rb_jump_union) return Order.PAY_JUMP_UNION;
        if (id == R.id.rb_online_card) return Order.PAY_ONLINE_CARD;
        return Order.PAY_NONE;
    }

    /** 跳转云闪付（支付加成确认）或支付宝/微信（赏世界确认）显示「自行确认」勾选。 */
    private void updateConfirmVisibility() {
        String m = selectedPayMethod();
        boolean needs = Order.PAY_JUMP_UNION.equals(m) || Order.PAY_ALIPAY_WECHAT.equals(m);
        cbPayConfirmed.setVisibility(needs ? View.VISIBLE : View.GONE);
        if (needs) {
            if (Order.PAY_ALIPAY_WECHAT.equals(m)) {
                cbPayConfirmed.setText("已自行确认：此笔可参与赏世界活动");
            } else {
                cbPayConfirmed.setText("已自行确认：此笔跳转云闪付可享 +2%");
            }
        }
        if (!needs) cbPayConfirmed.setChecked(false);
    }

    // ---------- 表单组装 / 计算 ----------

    /** 组装当前表单为 Order（不含 id）。 */
    private Order collect() {
        Order o = new Order();
        o.id = orderId;
        o.date = tvDate.getText().toString().trim();
        if (o.date.isEmpty()) o.date = today();
        try {
            o.amount = Double.parseDouble(etAmount.getText().toString().trim());
        } catch (Exception e) {
            o.amount = 0;
        }
        try {
            o.refundAmount = Double.parseDouble(etRefund.getText().toString().trim());
        } catch (Exception e) {
            o.refundAmount = 0;
        }
        if (o.refundAmount < 0) o.refundAmount = 0;
        if (o.refundAmount > o.amount) o.refundAmount = o.amount;
        o.currency = (String) spinnerCurrency.getSelectedItem();
        o.note = etNote.getText().toString().trim();
        o.payMethod = selectedPayMethod();
        o.payConfirmed = cbPayConfirmed.isChecked();
        o.dining = cbDining.isChecked();
        o.attachments = new java.util.ArrayList<>(attachments);
        o.cardId = cardId;
        return o;
    }

    private int yearOf(String date) {
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (Exception e) {
            return Calendar.getInstance().get(Calendar.YEAR);
        }
    }

    /** 当月（按订单日期所在月）累计与达标状态。 */
    private boolean thresholdMetFor(Order o) {
        String monthKey = o.date.length() >= 7 ? o.date.substring(0, 7) : today().substring(0, 7);
        boolean sw = Prefs.isShopWorld(this, yearOf(o.date));
        double[] stats = db.monthStats(monthKey, cardId, sw);
        double total = stats[0];
        if (orderId > 0) {
            Order old = db.getOrder(orderId);
            if (old != null) total -= old.netAmount();
        }
        total += o.netAmount();
        return total >= Cashback.threshold();
    }

    private void refreshCalc() {
        Order o = collect();
        boolean met = thresholdMetFor(o);
        boolean sw = Prefs.isShopWorld(this, yearOf(o.date));

        // 各项额度剩余（编辑时排除自身）
        double payRemaining = Cashback.payCap();
        double worldRemaining = Cashback.worldCap();
        double diningRemaining = Cashback.diningCap();
        try {
            int yr = yearOf(o.date);
            List<Order> yearOrders = db.getOrdersByYearLight(yr, cardId);
            // 升序排列
            yearOrders.sort((a, b) -> {
                int cmp = a.date.compareTo(b.date);
                if (cmp != 0) return cmp;
                return Long.compare(a.createdAt, b.createdAt);
            });
            double[] caps = Cashback.capUsageBefore(yearOrders, o.date, orderId, sw, met);
            payRemaining = Math.max(0, Cashback.payCap() - caps[0]);
            worldRemaining = Math.max(0, Cashback.worldCap() - caps[1]);
            diningRemaining = Math.max(0, Cashback.diningCap() - caps[2]);
        } catch (Exception ignored) {}

        double cb = Cashback.cashbackOf(o, met, sw, payRemaining, worldRemaining, diningRemaining);
        tvCashbackTotal.setText(o.currencySymbol() + Cashback.format(cb));
        tvCashbackDetail.setText(Cashback.breakdown(o, met, sw, payRemaining, worldRemaining, diningRemaining));

        int yr = yearOf(o.date);
        if (sw && !o.shopWorldEligible()) {
            tvShopWorldStatus.setText("赏世界 2%：" + yr + " 年度已报名 · 支付宝/微信默认不参与（可自行确认）");
        } else if (sw) {
            tvShopWorldStatus.setText("赏世界 2%：" + yr + " 年度已报名，本单生效");
        } else {
            tvShopWorldStatus.setText("赏世界 2%：" + yr + " 年度未报名（主页可开关）");
        }

        String monthKey = o.date.length() >= 7 ? o.date.substring(0, 7) : today().substring(0, 7);
        double[] stats = db.monthStats(monthKey, cardId, sw);
        double total = stats[0];
        if (orderId > 0) {
            Order old = db.getOrder(orderId);
            if (old != null) total -= old.netAmount();
        }
        total += o.netAmount();
        if (total >= Cashback.threshold()) {
            tvThresholdStatus.setText("本月累计 " + Cashback.format(total) + " / " + Cashback.n0(Cashback.threshold()) + " ✔ 已达标");
        } else {
            tvThresholdStatus.setText("本月累计 " + Cashback.format(total) + " / " + Cashback.n0(Cashback.threshold()) + " · 还差 " + Cashback.format(Cashback.threshold() - total));
        }
    }

    private void save() {
        String amountStr = etAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            Toast.makeText(this, R.string.need_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        Order o = collect();
        if (o.amount <= 0) {
            Toast.makeText(this, R.string.need_amount, Toast.LENGTH_SHORT).show();
            return;
        }
        // 退款金额不能超过订单金额
        String refundStr = etRefund.getText().toString().trim();
        if (!refundStr.isEmpty()) {
            try {
                double refund = Double.parseDouble(refundStr);
                if (refund > o.amount) {
                    Toast.makeText(this, R.string.refund_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception ignored) {}
        }
        if (orderId > 0) {
            db.updateOrder(o);
        } else {
            o.createdAt = System.currentTimeMillis();
            db.insertOrder(o);
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------- 附件 ----------

    private void renderAttachments() {
        layoutAttachments.removeAllViews();

        // 添加按钮
        ImageView addBtn = new ImageView(this);
        addBtn.setImageResource(android.R.drawable.ic_input_add);
        addBtn.setBackgroundResource(R.drawable.bg_input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(72));
        lp.setMargins(0, 0, dp(8), 0);
        addBtn.setLayoutParams(lp);
        addBtn.setPadding(dp(20), dp(20), dp(20), dp(20));
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickImage(); }
        });
        layoutAttachments.addView(addBtn);

        for (int i = 0; i < attachments.size(); i++) {
            final String path = attachments.get(i);
            final int index = i;
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(72), dp(72));
            ilp.setMargins(0, 0, dp(8), 0);
            iv.setLayoutParams(ilp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap bmp = ImageUtil.decodeThumb(path, 144);
            if (bmp != null) iv.setImageBitmap(bmp);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent it = new Intent(EditOrderActivity.this, ViewImageActivity.class);
                    it.putExtra("path", path);
                    startActivity(it);
                }
            });
            iv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(EditOrderActivity.this)
                            .setTitle("删除截图")
                            .setMessage("确定删除这张截图吗？")
                            .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ImageUtil.deleteQuietly(attachments.remove(index));
                                    renderAttachments();
                                }
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                }
            });
            layoutAttachments.addView(iv);
        }
    }

    private void pickImage() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(it, REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                String path = ImageUtil.saveFromUri(this, uri);
                attachments.add(path);
                renderAttachments();
            } catch (IOException e) {
                Toast.makeText(this, "保存截图失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- 快捷选择按钮 ----------

    /** 渲染快捷按钮行：美团、拼多多、自定义按钮、+ 添加按钮。 */
    private void renderQuickButtons() {
        layoutQuickButtons.removeAllViews();

        // 内置：美团（ApplePay + 餐饮）
        layoutQuickButtons.addView(buildQuickBtn("美团", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etNote.setText("美团");
                checkPayMethod(Order.PAY_APPLE);
                cbDining.setChecked(true);
                refreshCalc();
            }
        }, false, -1));

        // 内置：拼多多（ApplePay）
        layoutQuickButtons.addView(buildQuickBtn("拼多多", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etNote.setText("拼多多");
                checkPayMethod(Order.PAY_APPLE);
                cbDining.setChecked(false);
                refreshCalc();
            }
        }, false, -1));

        // 自定义按钮
        org.json.JSONArray arr = loadQuickButtons();
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject obj = arr.getJSONObject(i);
                final String name = obj.optString("name", "按钮");
                final String note = obj.optString("note", "");
                final String payMethod = obj.optString("payMethod", "");
                final boolean dining = obj.optBoolean("dining", false);
                final int idx = i;

                View btn = buildQuickBtn(name, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        etNote.setText(note);
                        if (!payMethod.isEmpty()) checkPayMethod(payMethod);
                        cbDining.setChecked(dining);
                        refreshCalc();
                    }
                }, true, idx);
                layoutQuickButtons.addView(btn);
            } catch (Exception ignored) {}
        }

        // + 添加自定义按钮
        layoutQuickButtons.addView(buildQuickBtn("＋", new View.OnClickListener() {
            @Override
            public void onClick(View v) { showQuickButtonDialog(-1); }
        }, false, -1));
    }

    /** 构建单个快捷按钮。isCustom 时长按可编辑/删除。index 为自定义按钮索引（-1 = 非自定义）。 */
    private View buildQuickBtn(String label, View.OnClickListener click, final boolean isCustom, final int index) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(13);
        btn.setTextColor(0xFF333333);
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF0F0F0);
        bg.setCornerRadius(dp(16));
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        btn.setLayoutParams(lp);

        btn.setOnClickListener(click);
        if (isCustom) {
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showQuickButtonDialog(index);
                    return true;
                }
            });
        }
        return btn;
    }

    /** 加载自定义按钮 JSON 数组。 */
    private org.json.JSONArray loadQuickButtons() {
        try {
            return new org.json.JSONArray(Prefs.getQuickButtons(this));
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }

    /** 新建（index<0）或编辑（index>=0）自定义按钮弹窗。 */
    private void showQuickButtonDialog(final int editIndex) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(4));

        final EditText etName = new EditText(this);
        etName.setHint("按钮名称（如：京东）");
        etName.setTextSize(14);
        root.addView(etName);

        final EditText etBtnNote = new EditText(this);
        etBtnNote.setHint("预设备注（点击按钮后自动填入）");
        etBtnNote.setTextSize(14);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(8);
        etBtnNote.setLayoutParams(np);
        root.addView(etBtnNote);

        // 支付方式下拉
        final Spinner spPay = new Spinner(this);
        ArrayAdapter<String> payAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"不设置", "Apple Pay", "云闪付 QR", "支付宝/微信", "跳转云闪付", "在线/实体卡"});
        spPay.setAdapter(payAdapter);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        sp.topMargin = dp(8);
        spPay.setLayoutParams(sp);
        root.addView(spPay);

        final CheckBox cbBtnDining = new CheckBox(this);
        cbBtnDining.setText("餐饮类（额外 +3%）");
        cbBtnDining.setTextSize(14);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(4);
        cbBtnDining.setLayoutParams(cp);
        root.addView(cbBtnDining);

        // 编辑模式：填充现有值
        if (editIndex >= 0) {
            try {
                org.json.JSONObject obj = loadQuickButtons().getJSONObject(editIndex);
                etName.setText(obj.optString("name", ""));
                etBtnNote.setText(obj.optString("note", ""));
                String pm = obj.optString("payMethod", "");
                int sel = 0;
                if (Order.PAY_APPLE.equals(pm)) sel = 1;
                else if (Order.PAY_UNION_QR.equals(pm)) sel = 2;
                else if (Order.PAY_ALIPAY_WECHAT.equals(pm)) sel = 3;
                else if (Order.PAY_JUMP_UNION.equals(pm)) sel = 4;
                else if (Order.PAY_ONLINE_CARD.equals(pm)) sel = 5;
                spPay.setSelection(sel);
                cbBtnDining.setChecked(obj.optBoolean("dining", false));
            } catch (Exception ignored) {}
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(editIndex >= 0 ? "编辑快捷按钮" : "添加快捷按钮")
                .setView(root)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = etName.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(EditOrderActivity.this, "请输入按钮名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String note = etBtnNote.getText().toString().trim();
                        String payMethod = "";
                        switch (spPay.getSelectedItemPosition()) {
                            case 1: payMethod = Order.PAY_APPLE; break;
                            case 2: payMethod = Order.PAY_UNION_QR; break;
                            case 3: payMethod = Order.PAY_ALIPAY_WECHAT; break;
                            case 4: payMethod = Order.PAY_JUMP_UNION; break;
                            case 5: payMethod = Order.PAY_ONLINE_CARD; break;
                            default: break;
                        }
                        boolean dining = cbBtnDining.isChecked();

                        org.json.JSONArray arr = loadQuickButtons();
                        try {
                            org.json.JSONObject obj = new org.json.JSONObject();
                            obj.put("name", name);
                            obj.put("note", note);
                            obj.put("payMethod", payMethod);
                            obj.put("dining", dining);
                            if (editIndex >= 0 && editIndex < arr.length()) {
                                arr.put(editIndex, obj);
                            } else {
                                arr.put(obj);
                            }
                            Prefs.setQuickButtons(EditOrderActivity.this, arr.toString());
                        } catch (Exception e) {
                            Toast.makeText(EditOrderActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                        }
                        renderQuickButtons();
                    }
                })
                .setNegativeButton("取消", null);

        if (editIndex >= 0) {
            builder.setNeutralButton("删除", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    org.json.JSONArray arr = loadQuickButtons();
                    org.json.JSONArray newArr = new org.json.JSONArray();
                    try {
                        for (int i = 0; i < arr.length(); i++) {
                            if (i != editIndex) newArr.put(arr.get(i));
                        }
                        Prefs.setQuickButtons(EditOrderActivity.this, newArr.toString());
                    } catch (Exception ignored) {}
                    renderQuickButtons();
                }
            });
        }
        builder.show();
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }
}
