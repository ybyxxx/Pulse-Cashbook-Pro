package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;import android.widget.Toast;

/** 设置：主题颜色 / 卡片信息备份 / 关于。 */
public class SettingsActivity extends Activity {

    private TextView tvThemeValue;
    private TextView tvRulesValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Themer.applyStatusBar(this);
        tvThemeValue = findViewById(R.id.tv_theme_value);
        tvThemeValue.setText(Themer.NAMES[Themer.index(this)]);
        Themer.tintText(tvThemeValue, this);

        findViewById(R.id.tv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        findViewById(R.id.row_theme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickTheme(); }
        });

        findViewById(R.id.row_cards).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, CardsActivity.class));
            }
        });

        findViewById(R.id.row_card).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, CardInfoActivity.class));
            }
        });

        findViewById(R.id.row_about).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });

        findViewById(R.id.row_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickExportFormat(); }
        });

        findViewById(R.id.row_restore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickRestoreFile(); }
        });

        tvRulesValue = findViewById(R.id.tv_rules_value);
        tvRulesValue.setText(rulesLabel());
        findViewById(R.id.row_rules).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showRulesDialog(); }
        });

        // 迎新活动模式开关
        final android.widget.Switch switchWelcome = findViewById(R.id.switch_welcome);
        switchWelcome.setChecked(Prefs.isWelcomeEnabled(this));
        switchWelcome.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setWelcomeEnabled(SettingsActivity.this, isChecked);
                Toast.makeText(SettingsActivity.this,
                        isChecked ? "迎新活动模式已开启" : "迎新活动模式已关闭",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Themer.applyStatusBar(this);
        tvThemeValue.setText(Themer.NAMES[Themer.index(this)]);
        Themer.tintText(tvThemeValue, this);
        if (tvRulesValue != null) tvRulesValue.setText(rulesLabel());
    }

    /** 主题色选择：色点 + 名称 列表（整行单选，圆点本身不可直接点击以免多选）。 */
    private void pickTheme() {
        final int current = Themer.index(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(10), pad, dp(6));

        final int[] checked = {current};
        final android.widget.RadioButton[] radios = new android.widget.RadioButton[Themer.NAMES.length];

        for (int i = 0; i < Themer.NAMES.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Themer.COLORS[i]);
            dot.setBackground(circle);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            dotLp.setMarginEnd(dp(12));
            row.addView(dot, dotLp);

            TextView name = new TextView(this);
            name.setText(Themer.NAMES[i]);
            name.setTextSize(15);
            name.setTextColor(0xFF1A1A1A);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(name, nameLp);

            final android.widget.RadioButton rb = new android.widget.RadioButton(this);
            // 关键：圆点自身不可点击/聚焦，避免脱离行逻辑独立切换造成多选
            rb.setClickable(false);
            rb.setFocusable(false);
            rb.setDuplicateParentStateEnabled(false);
            rb.setChecked(i == current);
            radios[i] = rb;
            row.addView(rb);

            final int idx = i;
            View.OnClickListener pick = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checked[0] = idx;
                    for (int j = 0; j < radios.length; j++) radios[j].setChecked(j == idx);
                }
            };
            row.setOnClickListener(pick);
            list.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_picker_title)
                .setView(list)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setThemeIndex(SettingsActivity.this, checked[0]);
                        tvThemeValue.setText(Themer.NAMES[checked[0]]);
                        Themer.tintText(tvThemeValue, SettingsActivity.this);
                        Themer.applyStatusBar(SettingsActivity.this);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 返现规则 ----------

    private String rulesLabel() {
        RuleSet rs = Cashback.rules();
        return rs.version.isEmpty() ? "内置" : rs.version;
    }

    private void showRulesDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_rules)
                .setMessage(RulesStore.describe(this, Cashback.rules()))
                .setPositiveButton(R.string.rules_check, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { checkRulesUpdate(); }
                })
                .setNeutralButton(R.string.rules_set_url, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) { editRulesUrl(); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void checkRulesUpdate() {
        Toast.makeText(this, R.string.rules_checking, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                RulesStore.refresh(SettingsActivity.this, new RulesStore.Callback() {
                    @Override
                    public void onResult(boolean ok, final String message, RuleSet ruleSet) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
                                if (tvRulesValue != null) tvRulesValue.setText(rulesLabel());
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private void editRulesUrl() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(RulesStore.url(this));
        et.setHint(R.string.rules_url_hint);
        et.setSingleLine(true);
        int pad = dp(20);
        et.setPadding(pad, dp(8), pad, dp(8));

        new AlertDialog.Builder(this)
                .setTitle(R.string.rules_set_url)
                .setView(et)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = et.getText().toString().trim();
                        if (!url.isEmpty() && !url.startsWith("https://")) {
                            Toast.makeText(SettingsActivity.this, R.string.rules_url_invalid,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        RulesStore.setUrl(SettingsActivity.this, url);
                        if (tvRulesValue != null) tvRulesValue.setText(rulesLabel());
                        Toast.makeText(SettingsActivity.this,
                                url.isEmpty() ? "已清空规则地址，仅使用内置/缓存规则"
                                        : "规则地址已保存，点「检查更新」拉取",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- 导出 ----------

    /** 选择导出格式 → 拉起系统文件选择器（免存储权限）。 */
    private void pickExportFormat() {
        if (ExportHelper.countOrders(this) == 0) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] items = {
                getString(R.string.export_csv),
                getString(R.string.export_json)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.export_chooser_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean csv = which == 0;
                        try {
                            startActivityForResult(
                                    csv ? ExportHelper.createCsvIntent() : ExportHelper.createJsonIntent(),
                                    csv ? ExportHelper.REQ_CSV : ExportHelper.REQ_JSON);
                        } catch (android.content.ActivityNotFoundException e) {
                            Toast.makeText(SettingsActivity.this,
                                    "没有可用的文件管理器", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        android.net.Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == ImportHelper.REQ_PICK_JSON) {
            onRestoreFilePicked(uri);
            return;
        }

        String content;
        if (requestCode == ExportHelper.REQ_CSV) {
            content = ExportHelper.buildCsv(this);
        } else if (requestCode == ExportHelper.REQ_JSON) {
            content = ExportHelper.buildJson(this);
        } else {
            return;
        }

        int count = ExportHelper.countOrders(this);
        String err = ExportHelper.write(this, uri, content);
        if (err == null) {
            Toast.makeText(this, getString(R.string.export_done, count), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.export_failed, err), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 数据恢复 ----------

    private void pickRestoreFile() {
        try {
            startActivityForResult(ImportHelper.createIntent(), ImportHelper.REQ_PICK_JSON);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_LONG).show();
        }
    }

    /** 读文件 → 显示摘要 → 让用户选恢复方式。 */
    private void onRestoreFilePicked(android.net.Uri uri) {
        final ImportParser.Result result = ImportHelper.read(this, uri);
        if (!result.ok) {
            Toast.makeText(this, getString(R.string.restore_failed, result.error),
                    Toast.LENGTH_LONG).show();
            return;
        }

        String extra = result.skipped > 0
                ? getString(R.string.restore_extra, result.skipped) : "";
        String cardName = result.cardName.isEmpty() ? "（未记录）" : result.cardName;
        String summary = getString(R.string.restore_summary,
                result.appVersion.isEmpty() ? "未知" : result.appVersion,
                result.exportedAt.isEmpty() ? "未知" : result.exportedAt,
                cardName, result.count(), extra);

        final CharSequence[] modes = {
                getString(R.string.restore_mode_merge),
                getString(R.string.restore_mode_new),
                getString(R.string.restore_mode_replace)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_restore)
                .setMessage(summary)
                .setItems(modes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == ImportHelper.MODE_REPLACE) {
                            confirmReplace(result);
                        } else {
                            applyRestore(result, which);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmReplace(final ImportParser.Result result) {
        long cardId = Prefs.getCurrentCardId(this);
        int existing = ImportHelper.countOrders(this, cardId);
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_replace_title)
                .setMessage(getString(R.string.restore_replace_msg, existing))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyRestore(result, ImportHelper.MODE_REPLACE);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void applyRestore(ImportParser.Result result, int mode) {
        long cardId = Prefs.getCurrentCardId(this);
        String message = ImportHelper.apply(this, result, cardId, mode);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
