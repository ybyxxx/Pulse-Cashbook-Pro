package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 关于页面。更新日志默认折叠，点击标题行展开/收起。
 * 底部开发者信息与反馈邮箱均可点击：GitHub 打开项目仓库，邮箱拉起邮件应用。
 */
public class AboutActivity extends Activity {

    private static final String GITHUB_URL = "https://github.com/ybyxxx/Pulse-Cashbook-Pro";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Themer.applyStatusBar(this);

        ImageView icon = findViewById(R.id.iv_icon);
        Themer.tintButton(icon, this);

        findViewById(R.id.tv_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        setupChangelog();
        setupMeta();
    }

    /** 更新日志：默认收起，点标题行展开。 */
    private void setupChangelog() {
        final TextView tvChangelog = findViewById(R.id.tv_changelog);
        final TextView tvArrow = findViewById(R.id.tv_changelog_arrow);
        View rowChangelog = findViewById(R.id.row_changelog);
        rowChangelog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = tvChangelog.getVisibility() != View.VISIBLE;
                tvChangelog.setVisibility(expand ? View.VISIBLE : View.GONE);
                tvArrow.setText(expand ? R.string.changelog_expanded : R.string.changelog_collapsed);
            }
        });
    }

    /** 开发者信息 / 反馈邮箱 / 版权，前两行各有一处可点击。 */
    private void setupMeta() {
        TextView tvDev = findViewById(R.id.tv_about_dev);
        TextView tvMail = findViewById(R.id.tv_about_mail);
        TextView tvCopy = findViewById(R.id.tv_about_copy);

        tvCopy.setText(R.string.about_copyright);

        String platform = getString(R.string.about_dev_platform);
        String devLine = getString(R.string.about_dev_line,
                getString(R.string.about_dev_name), platform);
        SpannableStringBuilder dev = new SpannableStringBuilder(devLine);
        int gi = devLine.indexOf(platform);
        if (gi >= 0) {
            dev.setSpan(linkSpan(GITHUB_URL, false), gi, gi + platform.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvDev.setText(dev);
        tvDev.setMovementMethod(LinkMovementMethod.getInstance());
        tvDev.setHighlightColor(Color.TRANSPARENT);

        String addr = getString(R.string.about_mail_addr);
        String mailLine = getString(R.string.about_mail_line, addr);
        SpannableStringBuilder mail = new SpannableStringBuilder(mailLine);
        int mi = mailLine.indexOf(addr);
        if (mi >= 0) {
            mail.setSpan(linkSpan("mailto:" + addr, true), mi, mi + addr.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvMail.setText(mail);
        tvMail.setMovementMethod(LinkMovementMethod.getInstance());
        tvMail.setHighlightColor(Color.TRANSPARENT);
    }

    /** 可点击文本：按主题色着色、不显示下划线。 */
    private ClickableSpan linkSpan(final String uri, final boolean mail) {
        return new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                try {
                    Intent it = new Intent(mail ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW,
                            Uri.parse(uri));
                    startActivity(it);
                } catch (Throwable t) {
                    Toast.makeText(AboutActivity.this, R.string.about_open_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setColor(Themer.primary(AboutActivity.this));
                ds.setUnderlineText(false);
            }
        };
    }
}
