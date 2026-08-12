package com.qimu.guide.provisioning;

/** 设备初始化网络层的单一切换点。 */
public final class ProvisioningApiProvider {

    /** 后端 report/reset 改造上线前切 true 走 Mock 联调；上线后置 false 走真实接口。 */
    private static final boolean USE_MOCK = false;

    private static final ProvisioningApi INSTANCE = USE_MOCK
            ? new MockProvisioningApi() : new RemoteProvisioningApi();

    private ProvisioningApiProvider() {
    }

    /** 当前是否使用 Mock 联调实现，界面可据此显示联调提示。 */
    public static boolean isMock() {
        return USE_MOCK;
    }

    public static ProvisioningApi get() {
        return INSTANCE;
    }
}
