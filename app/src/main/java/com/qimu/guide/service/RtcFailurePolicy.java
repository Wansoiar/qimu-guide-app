package com.qimu.guide.service;

/** Legacy error values verified against the bundled ByteRTC 3.60 SDK. */
public final class RtcFailurePolicy {
    private RtcFailurePolicy() { }

    public static boolean isTokenError(int code) {
        return code == -1000 || code == -1009 || code == -1010;
    }

    public static boolean isPermanent(int code) {
        switch (code) {
            case -1002: // no publish permission
            case -1003: // no subscribe permission
            case -1004: // duplicate login
            case -1005: // missing app id
            case -1006: // kicked out
            case -1007: // invalid room id
            case -1011: // room dismissed
            case -1012: // no SDK license
            case -1014: // different user id
            case -1017: // server license expired
            case -1018: // license limit
            case -1019: case -1020: case -1021: case -1022:
            case -1023: case -1024: case -1025: case -1026: case -1027:
            case -1072: // SDK library could not be loaded
            case -1086: // wrong area code
                return true;
            default:
                return false;
        }
    }

    public static boolean isPermanentReason(String reason) {
        if (reason == null) return false;
        return reason.contains("FORBIDDEN") || reason.contains("LICENSE")
                || "KICKED_OUT".equals(reason) || "ROOM_DISMISS".equals(reason)
                || "DUPLICATE_LOGIN".equals(reason) || "EXCEEDS_THE_UPPER_LIMIT".equals(reason);
    }
}
