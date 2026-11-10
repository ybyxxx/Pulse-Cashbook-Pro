package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 关于页面。更新日志默认折叠，点击标题行展开/收起。 */
public class AboutActivity extends Activity {

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
}
