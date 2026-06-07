package com.sentinelmesh.api.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes server-side {@code AgentEvent} JSON to subscribed WebSocket clients.
 *
 * <ul>
 *   <li>{@code /ws/sessions/{id}} subscribes to one session's events.</li>
 *   <li>{@code /ws/events}       subscribes to the global firehose.</li>
 * </ul>
 *
 * <p>Each connection runs on a virtual thread (Spring's default in Boot 3.3
 * when {@code spring.threads.virtual.enabled=true}).
 */
@Component
public class EventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventWebSocketHandler.class);

    /** sessionId → set of websocket sessions watching it. UUID-keyed for cheap routing. */
    private final Map<UUID, Set<WebSocketSession>> perSession = new ConcurrentHashMap<>();
    /** Firehose subscribers (all events, all sessions). */
    private final Set<WebSocketSession> firehose = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        UUID sessionId = extractSessionId(ws.getUri().getPath());
        if (sessionId == null) {
            firehose.add(ws);
            log.debug("WS firehose connected: {}", ws.getId());
        } else {
            perSession.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(ws);
            log.debug("WS connected for session {}: {}", sessionId, ws.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        firehose.remove(ws);
        perSession.values().forEach(set -> set.remove(ws));
    }

    /** Fan a single serialized event out to firehose + per-session subscribers. */
    public void dispatch(UUID sessionId, String json) {
        TextMessage msg = new TextMessage(json);
        send(firehose, msg);
        if (sessionId != null) {
            Set<WebSocketSession> subs = perSession.get(sessionId);
            if (subs != null) send(subs, msg);
        }
    }

    private static void send(Set<WebSocketSession> targets, TextMessage msg) {
        Iterator<WebSocketSession> it = targets.iterator();
        while (it.hasNext()) {
            WebSocketSession s = it.next();
            if (!s.isOpen()) { it.remove(); continue; }
            try {
                synchronized (s) { s.sendMessage(msg); }
            } catch (IOException e) {
                log.debug("WS send failed; removing: {}", e.toString());
                it.remove();
            }
        }
    }

    /** Parse "/ws/sessions/{uuid}" → UUID, or null for "/ws/events". */
    private static UUID extractSessionId(String path) {
        if (path == null || !path.contains("/sessions/")) return null;
        String tail = path.substring(path.lastIndexOf('/') + 1);
        try { return UUID.fromString(tail); } catch (IllegalArgumentException e) { return null; }
    }
}
