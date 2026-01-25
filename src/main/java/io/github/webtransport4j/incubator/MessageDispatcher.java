package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.quic.QuicStreamChannel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;

import static io.github.webtransport4j.incubator.WebTransportUtils.writeVarInt;

public class MessageDispatcher extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger logger = Logger.getLogger(MessageDispatcher.class.getName());
    // Simulating your Business Logic Thread Pool
    private static final ExecutorService businessPool = Executors.newFixedThreadPool(4);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // We receive raw ByteBuf now!
        Channel channel = ctx.channel();

        String path;
        String type;
        ByteBuf payload;

        // 1. Determine Context (Stream vs Datagram)
        if (channel instanceof QuicStreamChannel) {
            // STREAM (Bidirectional or Unidirectional)
            QuicStreamChannel stream = (QuicStreamChannel) channel;
            Long typeAttr = stream.attr(WebTransportUtils.STREAM_TYPE_KEY).get();
            String pathAttr = stream.parent().attr(WebTransportServer.SESSION_PATH_KEY).get();

            path = (pathAttr != null) ? pathAttr : "?";

            if (typeAttr != null) {
                type = (typeAttr == 0x54) ? "UNIDIRECTIONAL" : "BIDIRECTIONAL"; // Simple heuristic based on current
                                                                                // code
            } else {
                type = "BIDIRECTIONAL"; // Default/Fallback
            }
            payload = msg; // Full message is payload

        } else {

            WebTransportUtils.readVariableLengthInt(msg);

            String pathAttr = channel.attr(WebTransportServer.SESSION_PATH_KEY).get();
            path = (pathAttr != null) ? pathAttr : "?";
            type = "DATAGRAM";
            payload = msg; // Remainder (readVariableLengthInt advanced readerIndex)
        }

        // 2. Offload to Business Logic
        // CRITICAL: Retain payload for async processing
        payload.retain();

        final String finalPath = path;
        final String finalType = type;

        businessPool.submit(() -> {
            try {
                processBusinessLogic(channel, finalPath, finalType, payload);
            } finally {
                payload.release();
            }
        });
    }

    private void processBusinessLogic(Channel channel, String path, String type, ByteBuf payload) {
        try {
            String content = payload.toString(StandardCharsets.UTF_8);
            logger.debug("⚡️ [APP LAYER] Dispatched to Controller:");
            logger.debug("    Path: " + path);
            logger.debug("    Type: " + type);
            logger.debug("    Data: " + content);

            
            
            // simulating the reply
            if ("BIDIRECTIONAL".equals(type)) {
                if (channel instanceof QuicStreamChannel) {
                    reply((QuicStreamChannel) channel, "ACK BI: I received the message from " + path + ": " + content);
                }
            }
            if ("DATAGRAM".equals(type)) {
                sendDatagram(channel, "ACK DG: I received the message from " + path + ": " + content);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reply(QuicStreamChannel channel, String text) {
    channel.eventLoop().execute(() -> {
        ByteBuf buffer = channel.alloc().directBuffer();
        buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        channel.writeAndFlush(buffer);
        logger.debug("✅ Reply Sent: " + text);
    });
}


    private void sendDatagram(Channel channel, String text) {
    Channel rawChannel = (channel instanceof QuicStreamChannel) ? channel.parent() : channel;

    rawChannel.eventLoop().execute(() -> {
        ByteBuf buffer = rawChannel.alloc().directBuffer();
        writeVarInt(buffer, 0);
        buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        rawChannel.writeAndFlush(buffer);
        logger.debug("✅ Datagram Sent: " + text);
    });
}

}