package com.qimu.guide.service;

/** Exactly one stop result or timeout may advance a return into cleanup/failure. */
final class ReturnConfirmationGate {
    enum Resolution { CONFIRMED, FAILED, IGNORED }

    private int operation;
    private boolean pending;
    private long deadlineMs;

    synchronized void begin(int nextOperation, long nowMs, long timeoutMs) {
        operation = nextOperation;
        pending = true;
        deadlineMs = nowMs + timeoutMs;
    }

    synchronized boolean isPending(int expectedOperation) {
        return pending && operation == expectedOperation;
    }

    synchronized long remainingMs(int expectedOperation, long nowMs) {
        return isPending(expectedOperation) ? Math.max(0, deadlineMs - nowMs) : 0;
    }

    synchronized Resolution resolve(int expectedOperation, boolean confirmed, long nowMs) {
        if (!isPending(expectedOperation)) return Resolution.IGNORED;
        pending = false;
        return confirmed && nowMs < deadlineMs ? Resolution.CONFIRMED : Resolution.FAILED;
    }
}
