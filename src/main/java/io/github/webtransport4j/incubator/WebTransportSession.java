package io.github.webtransport4j.incubator;

import io.netty.handler.codec.quic.QuicStreamChannel;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author https://github.com/sanjomo
 * @date 24/12/25 1:21 am
 */
public class WebTransportSession {

    public final long sessionStreamId;
    final QuicStreamChannel connectStream;

    // Phase 1: announced via WT_STREAM capsule
    private final Set<Long> announcedStreams =
            ConcurrentHashMap.newKeySet();

    // Phase 2: actual channels
    private final Map<Long, QuicStreamChannel> activeStreams =
            new ConcurrentHashMap<>();

    WebTransportSession(long sessionStreamId,
                        QuicStreamChannel connectStream) {
        this.sessionStreamId = sessionStreamId;
        this.connectStream = connectStream;
    }

    public void registerStream(long wtStreamId) {
        announcedStreams.add(wtStreamId);
    }

    public void attachStream(QuicStreamChannel ch) {
        long id = ch.streamId();

        if (!announcedStreams.contains(id)) {
            // Protocol violation or attack
            ch.close();
            return;
        }

        activeStreams.put(id, ch);
    }
}
