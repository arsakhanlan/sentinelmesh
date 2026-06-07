package com.sentinelmesh.messaging;

public final class EventTopics {
    private EventTopics() {}
    public static final String AGENT_EVENTS = "sentinelmesh:agent-events";
    public static String forSession(java.util.UUID sessionId) {
        return AGENT_EVENTS + ":" + sessionId;
    }
}
