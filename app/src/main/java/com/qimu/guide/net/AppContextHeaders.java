package com.qimu.guide.net;

import com.qimu.guide.QimuApplication;
import com.qimu.guide.provisioning.ProvisioningApi;
import com.qimu.guide.provisioning.ProvisioningStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** 对话/RTC 链路统一公参 header（后端 apps/api/services/app_context.py 读取）。 */
public final class AppContextHeaders {

    private AppContextHeaders() {
    }

    /**
     * 组装对话/RTC 链路公参：
     *
     * - X-Device-Id：初始化后端的 device_id
     * - X-Glasses-Sn：初始化后端的眼镜 device_id（glasses.device_id）
     * - X-Order-Id：当前导览会话订单号
     * - X-Phone-Number：游客手机号 —— TODO(订单接口)：手机号后续由订单接口提供后再带；
     *   当前 App 仅有手机 SN（provisioning phone_sn），不冒充手机号发送。
     *
     * 只返回非空值；未初始化/未在导览时对应 header 缺失（后端按可空处理）。
     */
    public static Map<String, String> dialogue() {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            ProvisioningApi.ProvisioningSnapshot snap =
                    ProvisioningStore.get(QimuApplication.getAppContext()).snapshot();
            if (snap != null) {
                putNonEmpty(headers, "X-Device-Id", snap.deviceId);
                putNonEmpty(headers, "X-Glasses-Sn", snap.glassesId);
            }
        } catch (Exception ignored) {
            // 读取失败不阻断对话请求
        }
        try {
            TourSessionManager.TourSession tour = TourSessionManager.get().current();
            if (tour != null) {
                putNonEmpty(headers, "X-Order-Id", tour.orderNo);
            }
        } catch (Exception ignored) {
        }
        return headers;
    }

    private static void putNonEmpty(Map<String, String> headers, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            headers.put(name, value.trim());
        }
    }
}
