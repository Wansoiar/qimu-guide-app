package com.qimu.guide.provisioning;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/** 加密保存设备初始化结果，手机 SN 是身份找回键。 */
public final class ProvisioningStore {

    private static final String PREFS = "device_provisioning_secure";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_PHONE_SN = "phone_sn";
    private static final String KEY_GLASSES_MAC = "glasses_mac";
    private static final String KEY_GLASSES_NAME = "glasses_name";
    private static final String KEY_VENUE_ID = "venue_id";
    private static final String KEY_VENUE_CODE = "venue_code";
    private static final String KEY_VENUE_NAME = "venue_name";
    private static final String KEY_VENUE_ADDRESS = "venue_address";
    private static final String KEY_PROVISIONED_AT = "provisioned_at";

    private static volatile ProvisioningStore instance;
    private final SharedPreferences preferences;

    public static ProvisioningStore get(Context context) {
        if (instance == null) {
            synchronized (ProvisioningStore.class) {
                if (instance == null) {
                    instance = new ProvisioningStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ProvisioningStore(Context context) {
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
            throw new IllegalStateException("无法创建设备安全存储", e);
        }
    }

    public boolean isInitialized() {
        return !value(KEY_DEVICE_ID).isEmpty();
    }

    public synchronized boolean save(ProvisioningApi.ProvisioningSnapshot snapshot) {
        if (snapshot == null || snapshot.venue == null) return false;
        return preferences.edit()
                .putString(KEY_DEVICE_ID, snapshot.deviceId)
                .putString(KEY_PHONE_SN, snapshot.phoneSerial)
                .putString(KEY_GLASSES_MAC, snapshot.glassesId)
                .putString(KEY_GLASSES_NAME, snapshot.glassesName)
                .putString(KEY_VENUE_ID, snapshot.venue.id)
                .putString(KEY_VENUE_CODE, snapshot.venue.code)
                .putString(KEY_VENUE_NAME, snapshot.venue.name)
                .putString(KEY_VENUE_ADDRESS, snapshot.venue.address)
                .putLong(KEY_PROVISIONED_AT, snapshot.provisionedAtEpochMs)
                .commit();
    }

    public ProvisioningApi.ProvisioningSnapshot snapshot() {
        if (!isInitialized()) return null;
        ProvisioningApi.Venue venue = new ProvisioningApi.Venue(
                value(KEY_VENUE_ID), value(KEY_VENUE_CODE),
                value(KEY_VENUE_NAME), value(KEY_VENUE_ADDRESS));
        return new ProvisioningApi.ProvisioningSnapshot(
                value(KEY_DEVICE_ID), value(KEY_PHONE_SN),
                value(KEY_GLASSES_MAC), value(KEY_GLASSES_NAME), venue,
                preferences.getLong(KEY_PROVISIONED_AT, 0L));
    }

    public synchronized boolean clearProvisioning() {
        return preferences.edit()
                .remove(KEY_DEVICE_ID)
                .remove(KEY_PHONE_SN)
                .remove(KEY_GLASSES_MAC)
                .remove(KEY_GLASSES_NAME)
                .remove(KEY_VENUE_ID)
                .remove(KEY_VENUE_CODE)
                .remove(KEY_VENUE_NAME)
                .remove(KEY_VENUE_ADDRESS)
                .remove(KEY_PROVISIONED_AT)
                .commit();
    }

    private String value(String key) {
        String value = preferences.getString(key, "");
        return value == null ? "" : value;
    }
}
