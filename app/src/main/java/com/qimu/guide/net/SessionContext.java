package com.qimu.guide.net;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次导览会话的上下文（对齐后端 /v1/query 的 venue_id / session_id / client_query_id）。
 *
 * 阶段 2：venue_id / session_id 先用固定占位 UUID 跑通 happy path
 * （后端 orchestrator 不校验其是否真实存在，query_persist 打开时只用于落 query_log / 查多轮历史）。
 * 阶段 3 接真素材时，venue_id 换成知识库里真实的馆 ID。
 *
 * session_id 代表"一次连续游览"，同一 App 生命周期内固定，用于后端多轮历史（指代延续）。
 * client_query_id 每问一次递增，供后端去重 / 关联日志。
 */
public final class SessionContext {

    private static final SessionContext INSTANCE = new SessionContext();

    public static SessionContext get() {
        return INSTANCE;
    }

    // 占位 venue（阶段 3 换真实馆 ID）
    private volatile String venueId = "11111111-1111-1111-1111-111111111111";
    // 一次 App 生命周期 = 一次会话
    private final String sessionId = UUID.randomUUID().toString();
    private final AtomicInteger querySeq = new AtomicInteger(0);

    private SessionContext() {}

    public String venueId() {
        return venueId;
    }

    public void setVenueId(String venueId) {
        this.venueId = venueId;
    }

    public String sessionId() {
        return sessionId;
    }

    /** 每次提问生成一个新的 client_query_id。 */
    public String nextClientQueryId() {
        return "c-" + querySeq.incrementAndGet();
    }
}
