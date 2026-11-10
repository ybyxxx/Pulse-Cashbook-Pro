package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executor;

/**
 * 卡片信息备份：卡号 / 有效期 / CVV。
 * 进入前必须通过指纹验证（API 28+ BiometricPrompt，低版本回退锁屏凭据验证）。
 * 数据经 Android Keystore 加密后仅存本机。
 */
public class CardInfoActivity extends Activity {

    private static final int REQ_CONFIRM_CREDENTIAL = 2001;

    // 框架 BiometricPrompt 错误码（android.hardware.biometrics.BiometricConstants）
    private static final int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    private static final int BIOMETRIC_ERROR_CANCELED = 5;
    private static final int BIOMETRIC_ERROR_USER_CANCELED = 10;
    private static final int BIOMETRIC_ERROR_NO_BIOMETRICS = 11;
    private static final int BIOMETRIC_ERROR_HW_NOT_PRESENT = 12;
    private static final int BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13;
    private static final int BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL = 14;

    private LinearLayout layoutLocked;
    private ScrollView layoutForm;
    private EditText etNumber, etExp, etCvv;
    private TextView tvClear, tvIssueDate;
    private CancellationSignal cancelSignal;
    private boolean authenticated = false;
    private boolean authInProgress = false;
    private String issueDate = "";
    /** 当前卡片（下卡日期按卡存储）。 */
    private CashbookDb db;
    private long cardId = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_card_info);

        db = new CashbookDb(this);
        cardId = Prefs.getCurrentCardId(this);

        Themer.applyStatusBar(this);
        Themer.tintButton(findViewById(R.id.btn_auth), this);
        Themer.tintButton(findViewById(R.id.btn_save), this);

        layoutLocked = findViewById(R.id.layout_locked);
        layoutForm = findViewById(R.id.layout_form);
        etNumber = findViewById(R.id.et_card_number);
        etExp = findViewById(R.id.et_card_exp);
        etCvv = findViewById(R.id.et_card_cvv);
        tvClear = findViewById(R.id.tv_clear);
        tvIssueDate = findViewById(R.id.tv_card_issue_date);

        tvIssueDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { pickIssueDate(); }
        });

        findViewById(R.id.tv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btn_auth).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { authenticate(); }
        });
        findViewById(R.id.tv_auth_fallback).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { authenticateCredential(); }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { save(); }
        });
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { confirmClear(); }
        });

        // 进入即弹出验证（延迟到界面就绪，兼容部分 ROM 要求 Activity 完全恢复后才能拉起验证）
        layoutLocked.post(new Runnable() {
            @Override
            public void run() { authenticate(); }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cancelSignal != null) {
            cancelSignal.cancel();
            cancelSignal = null;
        }
        authInProgress = false;
        if (db != null) db.close();
    }

    // ---------- 身份验证 ----------

    private void authenticate() {
        if (authenticated || authInProgress) return;
        if (Build.VERSION.SDK_INT >= 28) {
            authenticateBiometric();
        } else {
            authenticateCredential();
        }
    }

    /**
     * 指纹验证。部分厂商 ROM（MIUI/HyperOS 等）的框架 BiometricPrompt
     * 可能抛异常或无法拉起，全程兜底并回退锁屏凭据验证。
     */
    private void authenticateBiometric() {
        try {
            final Executor executor = getMainExecutor();
            if (cancelSignal != null) cancelSignal.cancel();
            cancelSignal = new CancellationSignal();
            authInProgress = true;

            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(this)
                            .setTitle(getString(R.string.card_auth_title))
                            .setSubtitle(getString(R.string.card_auth_subtitle))
                            .setNegativeButton(getString(R.string.card_auth_cancel), executor,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            authInProgress = false;
                                        }
                                    })
                            .build();

            // 用 4 参数重载（CryptoObject 传 null），兼容 API 28
            prompt.authenticate(null, cancelSignal, executor,
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            authInProgress = false;
                            onAuthSuccess();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            authInProgress = false;
                            // 用户主动取消/负按钮不打扰；硬件不可用则引导锁屏密码
                            if (errorCode == BIOMETRIC_ERROR_CANCELED
                                    || errorCode == BIOMETRIC_ERROR_USER_CANCELED
                                    || errorCode == BIOMETRIC_ERROR_NEGATIVE_BUTTON) {
                                return;
                            }
                            if (errorCode == BIOMETRIC_ERROR_HW_UNAVAILABLE
                                    || errorCode == BIOMETRIC_ERROR_NO_BIOMETRICS
                                    || errorCode == BIOMETRIC_ERROR_HW_NOT_PRESENT
                                    || errorCode == BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL) {
                                Toast.makeText(CardInfoActivity.this,
                                        R.string.card_auth_unsupported, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(CardInfoActivity.this,
                                        getString(R.string.card_auth_failed), Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            // 单次指纹不匹配，不重置流程，继续等待
                            Toast.makeText(CardInfoActivity.this,
                                    getString(R.string.card_auth_failed), Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (Throwable t) {
            // ROM 指纹服务异常：回退锁屏凭据验证，绝不让界面崩溃
            authInProgress = false;
            cancelSignal = null;
            authenticateCredential();
        }
    }

    /** API 26-27 或指纹不可用时：锁屏凭据（PIN / 图案 / 密码）验证。 */
    private void authenticateCredential() {
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardSecure()) {
                Toast.makeText(this, "设备未设置锁屏保护，无法验证身份", Toast.LENGTH_LONG).show();
                return;
            }
            Intent it = km.createConfirmDeviceCredentialIntent(
                    getString(R.string.card_auth_title), getString(R.string.card_auth_subtitle));
            if (it != null) {
                authInProgress = true;
                startActivityForResult(it, REQ_CONFIRM_CREDENTIAL);
            }
        } catch (Throwable t) {
            authInProgress = false;
            Toast.makeText(this, R.string.card_auth_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM_CREDENTIAL) {
            authInProgress = false;
            if (resultCode == RESULT_OK) {
                onAuthSuccess();
            }
        }
    }

    private void onAuthSuccess() {
        try {
            authenticated = true;
            layoutLocked.setVisibility(View.GONE);
            layoutForm.setVisibility(View.VISIBLE);
            tvClear.setVisibility(View.VISIBLE);

            String[] card = SecureStore.loadCard(this);
            etNumber.setText(card[0]);
            etCvv.setText(card[1]);
            etExp.setText(card[2]);

            Card cur = db.getCard(cardId);
            issueDate = cur == null || cur.issueDate == null ? "" : cur.issueDate;
            tvIssueDate.setText(issueDate.isEmpty()
                    ? getString(R.string.card_issue_date_hint) : issueDate);
        } catch (Throwable t) {
            // 极端情况下也不闪退，至少进入表单
            authenticated = true;
            layoutLocked.setVisibility(View.GONE);
            layoutForm.setVisibility(View.VISIBLE);
            tvClear.setVisibility(View.VISIBLE);
        }
    }

    /** 下卡日期选择（迎新 60 日窗口起算点）。 */
    private void pickIssueDate() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int y = cal.get(java.util.Calendar.YEAR);
        int m = cal.get(java.util.Calendar.MONTH);
        int d = cal.get(java.util.Calendar.DAY_OF_MONTH);
        if (!issueDate.isEmpty() && issueDate.length() >= 10) {
            String[] p = issueDate.split("-");
            try {
                y = Integer.parseInt(p[0]);
                m = Integer.parseInt(p[1]) - 1;
                d = Integer.parseInt(p[2]);
            } catch (Exception ignored) {}
        }
        new android.app.DatePickerDialog(this, new android.app.DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(android.widget.DatePicker view, int year, int month, int dayOfMonth) {
                issueDate = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                tvIssueDate.setText(issueDate);
                db.updateCardIssueDate(cardId, issueDate);
                Toast.makeText(CardInfoActivity.this, "下卡日期已保存", Toast.LENGTH_SHORT).show();
            }
        }, y, m, d).show();
    }

    // ---------- 保存 / 清除 ----------

    private void save() {
        if (!authenticated) return;
        SecureStore.saveCard(this,
                etNumber.getText().toString().trim(),
                etCvv.getText().toString().trim(),
                etExp.getText().toString().trim());
        Toast.makeText(this, R.string.card_saved, Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.card_clear)
                .setMessage(R.string.card_clear_msg)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SecureStore.clearCard(CardInfoActivity.this);
                        etNumber.setText("");
                        etExp.setText("");
                        etCvv.setText("");
                        Toast.makeText(CardInfoActivity.this, R.string.card_cleared, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
