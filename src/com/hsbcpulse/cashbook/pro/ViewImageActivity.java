package com.hsbcpulse.cashbook.pro;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

/** 全屏查看截图。 */
public class ViewImageActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_image);

        String path = getIntent().getStringExtra("path");
        ImageView iv = findViewById(R.id.image_full);
        if (path != null) {
            Bitmap bmp = ImageUtil.decodeThumb(path, 2048);
            if (bmp != null) iv.setImageBitmap(bmp);
        }

        findViewById(R.id.tv_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
    }
}
