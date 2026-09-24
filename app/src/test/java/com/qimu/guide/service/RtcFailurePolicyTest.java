package com.qimu.guide.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RtcFailurePolicyTest {
    @Test public void expiredAndInvalidTokensUseRenewalPath() {
        for (int code : new int[]{-1000, -1009, -1010}) {
            assertTrue(RtcFailurePolicy.isTokenError(code));
            assertFalse(RtcFailurePolicy.isPermanent(code));
        }
    }

    @Test public void legacyPermissionBanDuplicateAndLicenseErrorsAreTerminal() {
        for (int code : new int[]{-1002, -1003, -1004, -1005, -1006, -1007, -1011,
                -1012, -1014, -1017, -1018, -1019, -1020, -1021, -1022, -1023,
                -1024, -1025, -1026, -1027, -1072, -1086}) {
            assertTrue("code=" + code, RtcFailurePolicy.isPermanent(code));
        }
    }

    @Test public void modernPermissionBanAndLicenseReasonsAreTerminal() {
        for (String reason : new String[]{"ROOM_FORBIDDEN", "USER_FORBIDDEN", "KICKED_OUT",
                "ROOM_DISMISS", "DUPLICATE_LOGIN", "WITHOUT_LICENSE_AUTHENTICATE_SDK",
                "SERVER_LICENSE_EXPIRED", "LICENSE_INFORMATION_NOT_MATCH", "EXCEEDS_THE_UPPER_LIMIT"}) {
            assertTrue(reason, RtcFailurePolicy.isPermanentReason(reason));
        }
    }

    @Test public void unknownAndReconnectFailuresRemainRecoverable() {
        assertFalse(RtcFailurePolicy.isPermanent(-1001));
        assertFalse(RtcFailurePolicy.isPermanent(-1084));
        assertFalse(RtcFailurePolicy.isPermanentReason("RECONNECT"));
        assertFalse(RtcFailurePolicy.isPermanentReason("UNKNOWN"));
        assertFalse(RtcFailurePolicy.isPermanentReason(null));
    }
}
