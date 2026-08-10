package com.qimu.guide.provisioning;

/** Single replacement point for the future RemoteProvisioningApi. */
public final class ProvisioningApiProvider {

    private static final ProvisioningApi INSTANCE = new MockProvisioningApi();

    private ProvisioningApiProvider() {
    }

    public static ProvisioningApi get() {
        return INSTANCE;
    }
}
