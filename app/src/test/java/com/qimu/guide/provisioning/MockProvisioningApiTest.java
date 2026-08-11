package com.qimu.guide.provisioning;

import com.qimu.guide.config.OperatorConfigStore;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MockProvisioningApiTest {

    @Test
    public void mockVenues_startsWithRealRagDemoVenue() {
        List<ProvisioningApi.Venue> venues = MockProvisioningApi.mockVenues();

        assertEquals(OperatorConfigStore.DEFAULT_VENUE_ID, venues.get(0).id);
        assertEquals(OperatorConfigStore.DEFAULT_VENUE_NAME, venues.get(0).name);
        assertEquals("demo-spec005", venues.get(0).code);
    }

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
