package com.qimu.guide.service;

/** Deterministic recovery policy. All timestamps are monotonic milliseconds. */
public final class RtcRecoveryController {
    public static final long ISSUE_DELAY_MS = 3_000L;
    public static final long STAGE_TIMEOUT_MS = 20_000L;
    private static final int MAX_REBUILDS = 3;

    public enum Phase { STOPPED, ONLINE, SDK_WAIT, REJOIN_EXISTING, REBUILDING, FAILED }
    public enum Cause { NETWORK, AGENT, UNKNOWN }
    public enum Intent { READY, LISTENING, PAUSED }
    public enum Action { NONE, REJOIN_EXISTING, REBUILD_TASK, FAIL }

    private Phase phase = Phase.STOPPED;
    private Cause cause = Cause.UNKNOWN;
    private Intent intent = Intent.READY;
    private long ticket;
    private long issueSince;
    private long deadline;
    private int rebuilds;

    public void start() {
        phase = Phase.ONLINE;
        intent = Intent.READY;
        rebuilds = 0;
        ticket++;
    }

    public boolean interrupt(Cause reason, Intent priorIntent, long now) {
        if (phase == Phase.STOPPED || phase == Phase.FAILED) return false;
        cause = reason;
        if (phase != Phase.ONLINE) return false;
        intent = priorIntent;
        phase = Phase.SDK_WAIT;
        issueSince = now;
        deadline = now + STAGE_TIMEOUT_MS;
        ticket++;
        return true;
    }

    /** A manual retry with retained credentials starts by joining the existing room. */
    public void retryExisting(Intent priorIntent, long now) {
        intent = priorIntent;
        cause = Cause.UNKNOWN;
        phase = Phase.REJOIN_EXISTING;
        issueSince = now;
        deadline = now + STAGE_TIMEOUT_MS;
        rebuilds = 0;
        ticket++;
    }

    public Action onDeadline(long expectedTicket, long now) {
        if (expectedTicket != ticket || now < deadline) return Action.NONE;
        if (phase == Phase.SDK_WAIT) {
            phase = Phase.REJOIN_EXISTING;
            deadline = now + STAGE_TIMEOUT_MS;
            ticket++;
            return Action.REJOIN_EXISTING;
        }
        if (phase == Phase.REJOIN_EXISTING) {
            if (rebuilds >= MAX_REBUILDS) {
                fail();
                return Action.FAIL;
            }
            rebuilds++;
            phase = Phase.REBUILDING;
            ticket++;
            return Action.REBUILD_TASK;
        }
        return Action.NONE;
    }

    /** The backend returned an identified new task; its local join is still bounded. */
    public boolean rebuilt(long expectedTicket, long now) {
        if (phase != Phase.REBUILDING || expectedTicket != ticket) return false;
        phase = Phase.REJOIN_EXISTING;
        cause = Cause.AGENT;
        deadline = now + STAGE_TIMEOUT_MS;
        ticket++;
        return true;
    }

    public void connected() {
        if (phase == Phase.STOPPED || phase == Phase.FAILED) return;
        phase = Phase.ONLINE;
        rebuilds = 0;
        ticket++;
    }

    public void setIntent(Intent next) { intent = next; }
    public void setCause(Cause next) { cause = next; }

    public void fail() {
        phase = Phase.FAILED;
        ticket++;
    }

    public void stop() {
        phase = Phase.STOPPED;
        ticket++;
    }

    public boolean isRecovering() {
        return phase == Phase.SDK_WAIT || phase == Phase.REJOIN_EXISTING || phase == Phase.REBUILDING;
    }

    public boolean showIssue(long now) { return isRecovering() && now - issueSince >= ISSUE_DELAY_MS; }
    public boolean isCurrent(long expected) { return ticket == expected && isRecovering(); }
    public long ticket() { return ticket; }
    public long deadline() { return deadline; }
    public long issueDelay(long now) { return Math.max(0, ISSUE_DELAY_MS - (now - issueSince)); }
    public Phase phase() { return phase; }
    public Intent intent() { return intent; }

    public boolean shouldResumeAudio(boolean permission, boolean glassesConnected, boolean busy) {
        return intent == Intent.LISTENING && permission && glassesConnected && !busy;
    }

    public String issueMessage() {
        switch (cause) {
            case NETWORK: return "网络连接暂时中断，正在恢复…";
            case AGENT: return "AI 导览服务暂时未响应，正在恢复…";
            default: return "连接暂时中断，正在恢复…";
        }
    }
}
