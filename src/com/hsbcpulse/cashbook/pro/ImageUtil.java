package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** 附件截图处理：拷贝到应用私有目录并压缩。 */
public final class ImageUtil {

    private static final int MAX_SIZE = 1280;

    private ImageUtil() {}

    /** 把相册图片拷贝进应用私有目录，返回保存路径。 */
    public static String saveFromUri(Context ctx, Uri uri) throws IOException {
        File dir = new File(ctx.getFilesDir(), "attachments");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建附件目录");
        File out = new File(dir, UUID.randomUUID().toString() + ".jpg");

        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取图片");
        try {
            Bitmap bmp = decodeSampled(in);
            if (bmp == null) throw new IOException("图片解码失败");
            OutputStream os = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, os);
            } finally {
                os.close();
                bmp.recycle();
            }
        } finally {
            in.close();
        }
        return out.getAbsolutePath();
    }

    private static Bitmap decodeSampled(InputStream in) throws IOException {
        byte[] data = readAll(in);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        opts.inSampleSize = calcInSampleSize(opts.outWidth, opts.outHeight);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static int calcInSampleSize(int w, int h) {
        int sample = 1;
        while (w / (sample * 2) >= MAX_SIZE || h / (sample * 2) >= MAX_SIZE) sample *= 2;
        return sample;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 按需缩略图解码。 */
    public static Bitmap decodeThumb(String path, int maxSize) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= maxSize || opts.outHeight / (sample * 2) >= maxSize) sample *= 2;
        opts.inSampleSize = sample;
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    /** 删除附件文件（忽略不存在）。 */
    public static void deleteQuietly(String path) {
        if (path == null) return;
        File f = new File(path);
        // 只允许删除附件目录下的文件
        if (f.getAbsolutePath().contains("attachments")) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
