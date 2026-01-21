package io.github.webtransport4j.incubator;

import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;

/**
 * @author https://github.com/sanjomo
 * @date 24/12/25 1:20 am
 */

public class WebTransportSessionManager {
    private static final Logger logger = Logger.getLogger(WebTransportSessionManager.class.getName());

    // Key used to attach this manager to the Parent QUIC Channel
    public static final AttributeKey<WebTransportSessionManager> WT_SESSION_MGR = AttributeKey
            .valueOf("wt.session.manager");

    // Key: The Session ID (which is the Stream ID of the CONNECT stream)
    // Value: The Session object containing state
    private final Map<Long, WebTransportSession> sessions = new ConcurrentHashMap<>();

    /**
     * Called when a CONNECT webtransport request is accepted (200 OK).
     */
    public WebTransportSession register(QuicStreamChannel connectStream) {
        long sessionStreamId = connectStream.streamId();

        // Create the session state
        WebTransportSession session = new WebTransportSession(sessionStreamId, connectStream);

        sessions.put(sessionStreamId, session);
        logger.debug("📝 SessionManager: Registered Session ID " + sessionStreamId);
        return session;
    }

    /**
     * Required by the Demux handler to validate incoming Bidi streams.
     */
    public boolean hasSession(long sessionStreamId) {
        return sessions.containsKey(sessionStreamId);
    }

    public WebTransportSession get(long sessionStreamId) {
        return sessions.get(sessionStreamId);
    }

    /**
     * Removes a specific session (e.g., when the CONNECT stream is closed).
     */
    public void remove(long sessionStreamId) {
        WebTransportSession removed = sessions.remove(sessionStreamId);
        if (removed != null) {
            logger.debug("🗑️ SessionManager: Removed Session ID " + sessionStreamId);
        }
    }

    /**
     * Cleanup: Called when the main QUIC Connection is lost/closed.
     * Prevents memory leaks by clearing the map.
     */
    public void closeAll() {
        if (!sessions.isEmpty()) {
            logger.debug(
                    "💥 SessionManager: Closing all " + sessions.size() + " active sessions due to connection close.");
            sessions.clear();
        }
    }
}