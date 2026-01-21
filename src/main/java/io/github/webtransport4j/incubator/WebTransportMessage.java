package io.github.webtransport4j.incubator;

import io.github.webtransport4j.incubator.applayer.StreamSender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCounted;

import java.nio.charset.StandardCharsets;

import static io.github.webtransport4j.incubator.WebTransportUtils.writeVarInt;

public final class WebTransportMessage implements ReferenceCounted {

    public enum MessageType {
        DATAGRAM,
        UNIDIRECTIONAL,
        BIDIRECTIONAL
    }

    private final MessageType type;
    private final String path;
    private final long sessionId;
    private final long streamId;
    private final ByteBuf payload;
    private final Channel channel;

    public WebTransportMessage(MessageType type, String path, long sessionId, long streamId, ByteBuf payload, Channel channel) {
        this.type = type;
        this.path = path;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.payload = payload;
        this.channel = channel;
    }

    public String getPath() { return path; }
    public MessageType getType() { return type; }
    public long getSessionId() { return sessionId; }
    public long getStreamId() { return streamId; }
    public ByteBuf getPayload() { return payload; }
    public void reply(String text) {
        if (type == MessageType.UNIDIRECTIONAL) {
            System.err.println("❌ ERROR: Cannot 'reply' to a Unidirectional Stream. Use sendDatagram() or openStream() instead.");
            return;
        }

        ByteBuf buffer = channel.alloc().directBuffer();


        writeVarInt(buffer, 0x41);
        writeVarInt(buffer, this.sessionId);
        buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(buffer).addListener(future -> {
            if (!future.isSuccess()) {
                System.err.println("❌ Reply Failed: " + future.cause());
            } else {
                System.out.println("✅ Reply Sent: " + text);
            }
        });
    }
    public void sendDatagram(String text) {
        Channel rawChannel = (channel instanceof QuicStreamChannel) ? channel.parent() : channel;
        ByteBuf buffer = rawChannel.alloc().directBuffer();
        writeVarInt(buffer, 0);
        buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        rawChannel.writeAndFlush(buffer).addListener(future -> {
            if (!future.isSuccess()) {
                System.err.println("❌ Reply Failed: " + future.cause());
            } else {
                System.out.println("✅ Reply Sent: " + text);
            }
        });
    }


    /**
     * PATTERN A: Fire-and-Forget (Create -> Push -> Close)
     * Best for: Notifications, Events, Alerts
     */
    public void pushEvent(String routePath, String payload) {
        openRawStream(routePath, (stream) -> {
            // Write payload and IMMEDIATELY CLOSE (addListener -> close)
            stream.writeAndFlush(Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8))
                    .addListener(ChannelFutureListener.CLOSE);
        });
    }

    /**
     * PATTERN B: Live Feed (Create -> Return Handle)
     * Best for: Tickers, GPS, Logs, Chat
     */
    public void openLiveFeed(String routePath, java.util.function.Consumer<StreamSender> onReady) {
        openRawStream(routePath, (stream) -> {
            // Do NOT close. Wrap it and give it to the controller.
            StreamSender sender = new StreamSender(stream);
            onReady.accept(sender);
        });
    }

    // --- Internal Helper ---
    private void openRawStream(String routePath, java.util.function.Consumer<Channel> onStreamReady) {
        // 1. Get Connection
        QuicChannel connection = (channel instanceof QuicStreamChannel)
                ? ((QuicStreamChannel) channel).parent()
                : (QuicChannel) channel;

        // 2. Create Stream
        connection.createStream(QuicStreamType.UNIDIRECTIONAL, null).addListener(future -> {
            if (future.isSuccess()) {
                QuicStreamChannel stream = (QuicStreamChannel) future.get();

                // 3. Write Header [Len][Path] so client can route it
                ByteBuf header = Unpooled.buffer();
                byte[] pathBytes = routePath.getBytes(CharsetUtil.UTF_8);
                header.writeByte(pathBytes.length);
                header.writeBytes(pathBytes);
                stream.write(header); // Just write, don't flush yet

                // 4. Hand off to caller
                onStreamReady.accept(stream);
            }
        });
    }
    // --- REFERENCE COUNTING (Mandatory for ByteBuf safety) ---
    @Override
    public int refCnt() { return payload.refCnt(); }

    @Override
    public WebTransportMessage retain() {
        payload.retain();
        return this;
    }

    @Override
    public WebTransportMessage retain(int increment) {
        payload.retain(increment);
        return this;
    }

    @Override
    public WebTransportMessage touch() {
        payload.touch();
        return this;
    }

    @Override
    public WebTransportMessage touch(Object hint) {
        payload.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return payload.release();
    }

    @Override
    public boolean release(int decrement) {
        return payload.release(decrement);
    }

    @Override
    public String toString() {
        return "Msg{path='" + path + "', stream=" + streamId + "}";
    }
}