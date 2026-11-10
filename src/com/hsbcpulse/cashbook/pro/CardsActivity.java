package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 卡片管理：新增 / 重命名 / 设置下卡日期 / 删除 / 切换当前卡。
 *
 * 删除卡片会连同该卡全部账单一起删除（需二次确认），且不允许删除最后一张卡。
 */
public class CardsActivity extends Activity {

    private CashbookDb db;
    private ListView listView;
    private final List<Card> cards = new ArrayList<>();
    private CardAdapter adapter;
    private long currentId = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cards);

        db = new CashbookDb(this);
        Themer.applyStatusBar(this);

        listView = findViewById(R.id.list_cards);
        adapter = new CardAdapter();
        listView.setAdapter(adapter);

        findViewById(R.id.tv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btn_add_card).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showAddDialog(); }
        });

        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectCard(cards.get(position));
            }
        });

        listView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view,
                                           int position, long id) {
                showCardActions(cards.get(position));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        cards.clear();
        cards.addAll(db.getCards());
        currentId = Prefs.getCurrentCardId(this);
        boolean exists = false;
        for (Card c : cards) if (c.id == currentId) exists = true;
        if (!exists && !cards.isEmpty()) {
            currentId = cards.get(0).id;
            Prefs.setCurrentCardId(this, currentId);
        }
        adapter.notifyDataSetChanged();
    }

    private void selectCard(Card c) {
        currentId = c.id;
        Prefs.setCurrentCardId(this, c.id);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已切换到 " + c.displayName(), Toast.LENGTH_SHORT).show();
    }

    // ---------- 操作菜单 ----------

    private void showCardActions(final Card c) {
        final String[] actions = {
                getString(R.string.cards_rename),
                getString(R.string.cards_set_issue),
                getString(R.string.cards_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(c.displayName())
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showRenameDialog(c);
                        else if (which == 1) showIssueDateDialog(c);
                        else confirmDelete(c);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showRenameDialog(final Card c) {
        final EditText et = new EditText(this);
        et.setText(c.displayName());
        et.setSelection(et.getText().length());
        int pad = dp(20);
        et.setPadding(pad, dp(8), pad, dp(8));

        new AlertDialog.Builder(this)
                .setTitle(R.string.cards_rename)
                .setView(et)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(CardsActivity.this, R.string.cards_name_required,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        c.name = name;
                        db.updateCard(c);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showIssueDateDialog(final Card c) {
        Calendar cal = Calendar.getInstance();
        if (c.hasIssueDate()) {
            try {
                String[] p = c.issueDate.split("-");
                cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            } catch (Exception ignored) {}
        }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                db.updateCardIssueDate(c.id, date);
                Toast.makeText(CardsActivity.this, R.string.cards_issue_saved, Toast.LENGTH_SHORT).show();
                reload();
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void confirmDelete(final Card c) {
        if (cards.size() <= 1) {
            Toast.makeText(this, R.string.cards_keep_one, Toast.LENGTH_LONG).show();
            return;
        }
        int count = db.countOrders(c.id);
        new AlertDialog.Builder(this)
                .setTitle(R.string.cards_delete)
                .setMessage("删除「" + c.displayName() + "」将同时删除该卡的 "
                        + count + " 笔账单与截图，且无法恢复。")
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        List<String> files = db.deleteCard(c.id);
                        for (String p : files) ImageUtil.deleteQuietly(p);
                        if (Prefs.getCurrentCardId(CardsActivity.this) == c.id) {
                            List<Card> rest = db.getCards();
                            if (!rest.isEmpty()) {
                                Prefs.setCurrentCardId(CardsActivity.this, rest.get(0).id);
                            }
                        }
                        Toast.makeText(CardsActivity.this, R.string.cards_deleted,
                                Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- 新增 ----------

    private void showAddDialog() {
        final EditText et = new EditText(this);
        et.setHint(R.string.cards_name_hint);
        int pad = dp(20);
        et.setPadding(pad, dp(8), pad, dp(8));

        final TextView tvIssue = new TextView(this);
        tvIssue.setText(R.string.cards_issue_unset);
        tvIssue.setTextSize(13);
        tvIssue.setTextColor(0xFF666666);
        tvIssue.setPadding(pad, dp(8), pad, dp(8));

        final String[] issue = {""};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(et);
        box.addView(tvIssue);

        tvIssue.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Calendar cal = Calendar.getInstance();
                new DatePickerDialog(CardsActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                        issue[0] = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                        tvIssue.setText("下卡日期：" + issue[0]);
                    }
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)).show();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.cards_add)
                .setView(box)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(CardsActivity.this, R.string.cards_name_required,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        long id = db.insertCard(name, issue[0]);
                        Prefs.setCurrentCardId(CardsActivity.this, id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- 列表 ----------

    private class CardAdapter extends BaseAdapter {
        @Override
        public int getCount() { return cards.size(); }

        @Override
        public Object getItem(int position) { return cards.get(position); }

        @Override
        public long getItemId(int position) { return cards.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Card c = cards.get(position);

            LinearLayout row = new LinearLayout(CardsActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(0xFFFFFFFF);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));

            TextView name = new TextView(CardsActivity.this);
            name.setTextSize(15);
            name.setTextColor(0xFF1A1A1A);
            boolean current = c.id == currentId;
            name.setText((current ? "✓ " : "") + c.displayName()
                    + (current ? "  （当前）" : ""));
            if (current) name.setTextColor(Themer.primary(CardsActivity.this));
            row.addView(name, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView sub = new TextView(CardsActivity.this);
            sub.setTextSize(12);
            sub.setTextColor(0xFF999999);
            sub.setText("下卡日期 " + (c.hasIssueDate() ? c.issueDate : "未设置")
                    + " · " + db.countOrders(c.id) + " 笔账单");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            row.addView(sub, lp);

            return row;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
