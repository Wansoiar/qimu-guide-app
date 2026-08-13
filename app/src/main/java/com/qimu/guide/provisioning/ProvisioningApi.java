package com.qimu.guide.provisioning;

import java.util.List;

/**
 * APP 设备初始化所需的服务端边界。
 *
 * 当前由 {@link MockProvisioningApi} 实现；服务端接口改造完成后新增 Remote 实现即可，
 * Activity 不应感知数据来自 Mock 还是真实网络。
 */
public interface ProvisioningApi {

    interface Callback<T> {
        void onSuccess(T value);
        void onFailure(String message);
    }

    void login(String username, String password, Callback<AuthSession> callback);

    void listVenues(String operatorToken, Callback<List<Venue>> callback);

    void initialize(String operatorToken, DeviceReportRequest request,
                    Callback<ProvisioningSnapshot> callback);

    void reset(String operatorToken, String deviceId, Callback<Void> callback);

    final class AuthSession {
        public final String operatorToken;
        public final String displayName;
        public final long expiresAtEpochMs;

        public AuthSession(String operatorToken, String displayName, long expiresAtEpochMs) {
            this.operatorToken = operatorToken;
            this.displayName = displayName;
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

    final class DeviceReportRequest {
        public final String phoneSerial;
        public final String phoneModel;
        public final String osVersion;
        public final String appVersion;
        public final String glassesId;
        public final String glassesName;
        public final Venue venue;

        public DeviceReportRequest(String phoneSerial, String phoneModel, String osVersion,
                                   String appVersion, String glassesId, String glassesName,
                                   Venue venue) {
            this.phoneSerial = phoneSerial;
            this.phoneModel = phoneModel;
            this.osVersion = osVersion;
            this.appVersion = appVersion;
            this.glassesId = glassesId;
            this.glassesName = glassesName;
            this.venue = venue;
        }
    }

    final class ProvisioningSnapshot {
        public final String deviceId;
        public final String phoneSerial;
        public final String glassesId;
        public final String glassesName;
        public final Venue venue;
        public final long provisionedAtEpochMs;

        public ProvisioningSnapshot(String deviceId, String phoneSerial, String glassesId,
                                    String glassesName, Venue venue, long provisionedAtEpochMs) {
            this.deviceId = deviceId;
            this.phoneSerial = phoneSerial;
            this.glassesId = glassesId;
            this.glassesName = glassesName;
            this.venue = venue;
            this.provisionedAtEpochMs = provisionedAtEpochMs;
        }
    }
}
