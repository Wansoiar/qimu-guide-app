package com.qimu.guide.provisioning;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MockProvisioningApiTest {

    @Test
    public void normalizeMac_trimsAndUppercases() {
        assertEquals("AA:BB:CC:DD:EE:FF",
                MockProvisioningApi.normalizeMac(" aa:bb:cc:dd:ee:ff "));
    }

    @Test
    public void normalizeMac_handlesNull() {
        assertEquals("", MockProvisioningApi.normalizeMac(null));
    }

    @Test
    public void stableDeviceId_isDeterministicForPhoneSerial() {
        String phoneSerial = "R8Y1234ABC";

        assertEquals(MockProvisioningApi.stableDeviceIdFromSerial(phoneSerial),
                MockProvisioningApi.stableDeviceIdFromSerial(" r8y1234abc "));
        assertNotEquals(MockProvisioningApi.stableDeviceIdFromSerial(phoneSerial),
                MockProvisioningApi.stableDeviceIdFromSerial("R8Y9999XYZ"));
    }

    @Test
    public void phoneSerial_validationAcceptsInventoryFriendlyCharacters() {
        assertEquals("SN-2026_001.A", MockProvisioningApi.normalizePhoneSerial(" sn-2026_001.a "));
        assertEquals(true, MockProvisioningApi.isValidPhoneSerial("SN-2026_001.A"));
        assertEquals(false, MockProvisioningApi.isValidPhoneSerial("SN 2026/001"));
    }
}
