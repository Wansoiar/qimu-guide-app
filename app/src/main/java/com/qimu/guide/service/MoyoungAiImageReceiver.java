package com.qimu.guide.service;

import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener;

/** Compatibility boundary for my_galsses_sdk_1.0.0_20260720_release.aar. */
public final class MoyoungAiImageReceiver {
    private MoyoungAiImageReceiver() { }

    /** Start each capture with an empty assembler, including after an earlier timeout. */
    public static void attach(CRPBleConnection connection, CRPAiDialogueListener listener) {
        com.moyoung.i.b receiver = com.moyoung.i.b.a();
        synchronized (receiver) {
            connection.setAiDialogueListener(null);
            // The SDK's public listener setter does not reset its singleton image writer.
            // Its end-of-image method closes the writer and resets the frame index. Detach
            // first so a discarded partial image cannot be delivered as a successful capture.
            // Keep this version-specific call here; exercise the actual AAR in regression tests.
            receiver.b();
            connection.setAiDialogueListener(listener);
        }
    }

    public static void detach(CRPBleConnection connection) {
        attach(connection, null);
    }
}
