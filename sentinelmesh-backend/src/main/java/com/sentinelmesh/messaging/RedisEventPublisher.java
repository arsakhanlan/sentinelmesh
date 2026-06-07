package com.sentinelmesh.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelmesh.api.ws.EventWebSocketHandler;
import com.sentinelmesh.common.error.SentinelMeshException;
import com.sentinelmesh.domain.model.AgentEvent;
import com.sentinelmesh.domain.port.out.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans every {@link AgentEvent} two ways:
 *  <ol>
 *    <li>In-process direct WebSocket dispatch (cheap, single-instance path).</li>
 *    <li>Redis pub/sub broadcast on the global + per-session topics so that
 *        other instances and external subscribers (audit pipelines, frontend
 *        polling fallbacks) can listen in.</li>
 *  </ol>
 *
 * <p>Multi-instance setups should disable the in-process path on the
 * publishing instance to avoid duplicate delivery; for v1 we run one instance.
 */
@Component
public class RedisEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final EventWebSocketHandler ws;

    public RedisEventPublisher(StringRedisTemplate redis, ObjectMapper mapper,
                                EventWebSocketHandler ws) {
        this.redis = redis;
        this.mapper = mapper;
        this.ws = ws;
    }

    @Override
    public void publish(AgentEvent event) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new SentinelMeshException("Failed to serialize AgentEvent", e);
        }
        // Direct in-process dispatch (cheap, immediate)
        ws.dispatch(event.sessionId(), json);
        // Redis broadcast (best-effort)
        try {
            redis.convertAndSend(EventTopics.AGENT_EVENTS, json);
            redis.convertAndSend(EventTopics.forSession(event.sessionId()), json);
        } catch (Exception ex) {
            log.warn("Redis publish failed (continuing): {}", ex.toString());
        }
    }
}
