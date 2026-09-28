package com.qimu.guide.service;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.protobuf.ByteString;
import com.moyoung.glasses.conn.CRPBleConnection;
import com.moyoung.glasses.conn.listener.CRPAiDialogueListener;
import com.moyoung.glasses.conn.protos.ImageFrame;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the shipped AAR's real assembler and AI listener dispatch without a BLE device. */
@RunWith(AndroidJUnit4.class)
public class MoyoungAiImageReceiverTest {
    private final AtomicReference<File> delivered = new AtomicReference<>();
    private CRPBleConnection connection;
    private final CRPAiDialogueListener listener = new CRPAiDialogueListener() {
        @Override public void onDialogueStart() { }
        @Override public void onDialogueAudioChange(byte[] audio) { }
        @Override public void onDialogueImageChange(File file) { delivered.set(file); }
        @Override public void onDialogueStop(boolean timeout) { }
    };

    @Before public void setUp() {
        // Mirrors the shipped connection's setAiDialogueListener implementation.
        connection = (CRPBleConnection) Proxy.newProxyInstance(
                CRPBleConnection.class.getClassLoader(), new Class<?>[]{CRPBleConnection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("setAiDialogueListener")) {
                        throw new AssertionError("Unexpected SDK call: " + method.getName());
                    }
                    com.moyoung.i.a.a().a((CRPAiDialogueListener) args[0]);
                    return null;
                });
        MoyoungAiImageReceiver.attach(connection, listener);
    }

    @After public void tearDown() {
        MoyoungAiImageReceiver.detach(connection);
    }

    @Test public void retryDiscardsPreviousPartialImageBeforeInstallingNewListener() throws Exception {
        frame(1, 255, 216, 99); // Previous capture never sent its end packet.
        MoyoungAiImageReceiver.attach(connection, listener);
        assertNull(delivered.get()); // Discard must not masquerade as a completed photo.
        frame(1, 255, 216, 10);
        frame(2, 20, 255, 217);
        end();
        assertArrayEquals(bytes(255, 216, 10, 20, 255, 217), receivedBytes());
    }

    @Test public void timeoutDetachDiscardsPartialAndIgnoresItsRemainingFrames() throws Exception {
        frame(1, 255, 216, 99);
        MoyoungAiImageReceiver.detach(connection);
        frame(2, 88, 255, 217);
        end();
        assertNull(delivered.get());
        MoyoungAiImageReceiver.attach(connection, listener);
        frame(1, 255, 216, 10, 255, 217);
        end();
        assertArrayEquals(bytes(255, 216, 10, 255, 217), receivedBytes());
    }

    @Test public void duplicateFirstFrameWithinCaptureDoesNotResetProgress() throws Exception {
        frame(1, 255, 216, 10);
        frame(1, 255, 216, 10);
        frame(2, 20, 255, 217);
        end();
        assertArrayEquals(bytes(255, 216, 10, 20, 255, 217), receivedBytes());
    }

    @Test public void missingMiddleFrameIsRejectedEvenWhenSdkEmitsFileCallback() throws Exception {
        frame(1, 255, 216, 10);
        frame(3, 20, 255, 217); // SDK drops non-sequential frames but still emits on end.
        end();
        assertNotNull(delivered.get());
        assertFalse(CapturedJpeg.hasCompleteEnvelope(delivered.get()));
    }

    private byte[] receivedBytes() throws Exception {
        assertNotNull(delivered.get());
        return Files.readAllBytes(delivered.get().toPath());
    }

    private void frame(int index, int... values) {
        com.moyoung.i.b.a().a(ImageFrame.newBuilder().setFrameIndex(index)
                .setData(ByteString.copyFrom(bytes(values))).build());
    }

    private void end() { com.moyoung.i.b.a().b(); }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
