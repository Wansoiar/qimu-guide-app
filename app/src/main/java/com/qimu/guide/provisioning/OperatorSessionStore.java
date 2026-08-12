package com.qimu.guide.provisioning;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * 加密保存运营登录态（access_token / expires_at / display_name）。
 *
 * 初始化向导与设备重置流程复用这里的 token；游客模式不读取。token 过期或已被清除时
 * {@link #isExpired()} 返回 true，界面应引导运营人员重新登录。
 */
public final class OperatorSessionStore {

    private static final String PREFS = "operator_session_secure";
    private static final String KEY_OPERATOR_TOKEN = "operator_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_DISPLAY_NAME = "display_name";

    private static volatile OperatorSessionStore instance;
    private final SharedPreferences preferences;

    public static OperatorSessionStore get(Context context) {
        if (instance == null) {
            synchronized (OperatorSessionStore.class) {
                if (instance == null) {
                    instance = new OperatorSessionStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private OperatorSessionStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            preferences = EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("无法创建运营登录安全存储", e);
        }
    }

    /** 运营 token；未登录时返回空字符串。 */
    public String token() {
        String token = preferences.getString(KEY_OPERATOR_TOKEN, "");
        return token == null ? "" : token;
    }

    /** token 过期时间戳（毫秒）；未保存时返回 0。 */
    public long expiresAtEpochMs() {
        return preferences.getLong(KEY_EXPIRES_AT, 0L);
    }

    /** 运营账号展示名；未登录时返回空字符串。 */
    public String displayName() {
        String name = preferences.getString(KEY_DISPLAY_NAME, "");
        return name == null ? "" : name;
    }

    /** 无 token 或已过期时返回 true。 */
    public boolean isExpired() {
        String token = token();
        if (token.isEmpty()) return true;
        long expiresAt = expiresAtEpochMs();
        return expiresAt > 0L && expiresAt <= System.currentTimeMillis();
    }

    /** 保存运营登录态；成功返回 true。 */
    public synchronized boolean save(String token, long expiresAtEpochMs, String displayName) {
        if (token == null || token.trim().isEmpty()) return false;
        String safeDisplayName = displayName == null ? "" : displayName;
        return preferences.edit()
                .putString(KEY_OPERATOR_TOKEN, token.trim())
                .putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
                .putString(KEY_DISPLAY_NAME, safeDisplayName)
                .commit();
    }

    /** 清除运营登录态。 */
    public synchronized void clear() {
        preferences.edit()
                .remove(KEY_OPERATOR_TOKEN)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_DISPLAY_NAME)
                .apply();
    }
}
