package com.hsbcpulse.cashbook.pro;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 Android Keystore 的本地加密存储（卡片信息）。
 * 密钥不出 Keystore，密文仅存应用私有 SharedPreferences。
 */
public final class SecureStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pulse_card_aes";
    private static final String PREFS = "card_secure";

    private SecureStore() {}

    // ---------- 基础加解密 ----------

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    public static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    public static String decrypt(String blob) throws Exception {
        String[] parts = blob.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("bad blob");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }

    // ---------- 卡片信息 ----------

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveCard(Context ctx, String number, String cvv, String exp) {
        SharedPreferences.Editor ed = sp(ctx).edit();
        putEncrypted(ed, "number", number);
        putEncrypted(ed, "cvv", cvv);
        putEncrypted(ed, "exp", exp);
        ed.apply();
    }

    public static String[] loadCard(Context ctx) {
        return new String[]{
                getDecrypted(ctx, "number"),
                getDecrypted(ctx, "cvv"),
                getDecrypted(ctx, "exp")
        };
    }

    public static void clearCard(Context ctx) {
        sp(ctx).edit().clear().apply();
    }

    private static void putEncrypted(SharedPreferences.Editor ed, String key, String value) {
        if (value == null || value.isEmpty()) {
            ed.remove(key);
            return;
        }
        try {
            ed.putString(key, encrypt(value));
        } catch (Exception e) {
            // Keystore 异常时直接不落盘，避免明文存储
            ed.remove(key);
        }
    }

    private static String getDecrypted(Context ctx, String key) {
        String blob = sp(ctx).getString(key, null);
        if (blob == null) return "";
        try {
            return decrypt(blob);
        } catch (Exception e) {
            return "";
        }
    }
}
