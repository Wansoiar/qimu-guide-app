package com.qimu.guide.provisioning;

import java.util.List;

/**
 * APP 设备初始化所需的服务端边界。
 *
 * 当前由 {@link MockProvisioningApi} 实现；服务端接口上线后新增 Remote 实现即可，
 * Activity 不应感知数据来自 Mock 还是真实网络。
 */
public interface ProvisioningApi {

    interface Callback<T> {
        void onSuccess(T value);
        void onFailure(String message);
    }

    void login(String username, String password, Callback<AuthSession> callback);

    void resolvePhoneSerial(String operatorToken, String phoneSerial,
                            Callback<PhoneIdentity> callback);

    void listVenues(String operatorToken, Callback<List<Venue>> callback);

    void initialize(String operatorToken, InitializeRequest request,
                    Callback<ProvisioningSnapshot> callback);

    void reset(String operatorToken, String deviceId, Callback<Void> callback);

    final class AuthSession {
        public final String operatorToken;
        public final String operatorName;
        public final long expiresAtEpochMs;

        public AuthSession(String operatorToken, String operatorName, long expiresAtEpochMs) {
            this.operatorToken = operatorToken;
            this.operatorName = operatorName;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }
    }

    final class Venue {
        public final String id;
        public final String code;
        public final String name;
        public final String address;

        public Venue(String id, String code, String name, String address) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.address = address;
        }
    }

    final class PhoneIdentity {
        public final String phoneSerial;
        public final String deviceId;
        public final boolean existing;
        public final Venue currentVenue;

        public PhoneIdentity(String phoneSerial, String deviceId,
                             boolean existing, Venue currentVenue) {
            this.phoneSerial = phoneSerial;
            this.deviceId = deviceId;
            this.existing = existing;
            this.currentVenue = currentVenue;
        }
    }

    final class InitializeRequest {
        public final String idempotencyKey;
        public final String installId;
        public final String phoneSerial;
        public final String androidIdHash;
        public final String phoneModel;
        public final String osVersion;
        public final String appVersion;
        public final String glassesId;
        public final String glassesName;
        public final Venue venue;

        public InitializeRequest(String idempotencyKey, String installId,
                                 String phoneSerial, String androidIdHash, String phoneModel,
                                 String osVersion, String appVersion,
                                 String glassesId, String glassesName, Venue venue) {
            this.idempotencyKey = idempotencyKey;
            this.installId = installId;
            this.phoneSerial = phoneSerial;
            this.androidIdHash = androidIdHash;
            this.phoneModel = phoneModel;
            this.osVersion = osVersion;
            this.appVersion = appVersion;
            this.glassesId = glassesId;
            this.glassesName = glassesName;
            this.venue = venue;
        }
    }

    final class ProvisioningSnapshot {
        public final String installId;
        public final String deviceId;
        public final String deviceCredential;
        public final String phoneSerial;
        public final String glassesId;
        public final String glassesName;
        public final Venue venue;
        public final long configVersion;
        public final long provisionedAtEpochMs;

        public ProvisioningSnapshot(String installId, String deviceId,
                                    String deviceCredential, String phoneSerial, String glassesId,
                                    String glassesName, Venue venue,
                                    long configVersion, long provisionedAtEpochMs) {
            this.installId = installId;
            this.deviceId = deviceId;
            this.deviceCredential = deviceCredential;
            this.phoneSerial = phoneSerial;
            this.glassesId = glassesId;
            this.glassesName = glassesName;
            this.venue = venue;
            this.configVersion = configVersion;
            this.provisionedAtEpochMs = provisionedAtEpochMs;
        }
    }
}
