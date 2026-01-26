package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.apache.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.github.webtransport4j.incubator.WebTransportUtils.writeVarInt;

public class MessageDispatcher extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger logger = Logger.getLogger(MessageDispatcher.class.getName());
    private static final ExecutorService businessPool = Executors.newFixedThreadPool(4);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        Channel channel = ctx.channel();
        
        // 1. Debug: Log the raw hex to see invisible bytes (like 0x00)
        if (logger.isDebugEnabled()) {
             logger.debug("📦 [RAW PAYLOAD] " + ByteBufUtil.hexDump(msg));
        }

        String path;
        String transportType;
        
        // Determine Context
        if (channel instanceof QuicStreamChannel) {
            QuicStreamChannel stream = (QuicStreamChannel) channel;
            Long typeAttr = stream.attr(WebTransportUtils.STREAM_TYPE_KEY).get();
            String pathAttr = stream.parent().attr(WebTransportServer.SESSION_PATH_KEY).get();

            path = (pathAttr != null) ? pathAttr : "?";
            transportType = (typeAttr != null && typeAttr == 0x54) ? "UNIDIRECTIONAL" : "BIDIRECTIONAL";
        } else {
            WebTransportUtils.readVariableLengthInt(msg); // consume datagram ID
            String pathAttr = channel.attr(WebTransportServer.SESSION_PATH_KEY).get();
            path = (pathAttr != null) ? pathAttr : "?";
            transportType = "DATAGRAM";
        }

        // 2. Offload to Business Logic
        msg.retain(); 
        final String finalPath = path;
        final String finalType = transportType;

        businessPool.submit(() -> {
            try {
                processBusinessLogic(channel, finalPath, finalType, msg);
                //processSocketIOPacket(channel, finalPath, finalType, msg);
            } finally {
                msg.release();
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
            logger.error("Error in business logic", e);
        }
    }

    private void processSocketIOPacket(Channel channel, String path, String transportType, ByteBuf payload) {
        try {
            // Convert to String
            String rawContent = payload.toString(StandardCharsets.UTF_8);
            logger.debug("⚡️ [SOCKET.IO] " + transportType + " | Raw: " + rawContent);
            if (rawContent.isEmpty()) return;

            // 🔍 FIX: Sanitize the input to remove Ghost Bytes (0x00, 0x01, etc)
            // This finds the first index that IS NOT a control character
            int validStartIndex = 0;
            while (validStartIndex < rawContent.length() && rawContent.charAt(validStartIndex) <= 32) {
                validStartIndex++;
            }

            // If string was only garbage/control chars
            if (validStartIndex >= rawContent.length()) {
                logger.debug("⚠️ Ignored packet containing only control characters.");
                return;
            }

            // Extract the clean content
            String content = rawContent.substring(validStartIndex);

            // 1. Parse Socket.IO Packet Type
            char packetType = content.charAt(0);
            String data = (content.length() > 1) ? content.substring(1) : "";

            logger.debug("⚡️ [SOCKET.IO] " + transportType + " | Type: " + packetType + " | Data: " + data);

            // 2. Handle Types
            switch (packetType) {
                case '0': // OPEN
                    logger.info("👋 Received OPEN (Handshake). Data: " + data);
                    // Standard Socket.IO reply to Open is usually an Open packet back with session ID
                    reply(channel, "0{\"sid\":\"" + channel.id().asShortText() + "\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000}");
                    break;

                case '1': // CLOSE
                    logger.info("❌ Received CLOSE.");
                    channel.close();
                    break;

                case '2': // PING
                    logger.debug("❤️ Received PING. Sending PONG...");
                    reply(channel, "3");
                    break;

                case '3': // PONG
                    logger.debug("💓 Received PONG (Client alive).");
                    break;
                
                case '4': // MESSAGE
                    logger.info("📩 Received MESSAGE: " + data);
                    break;

                default:
                    logger.warn("⚠️ Unknown Packet Type: '" + packetType + "' (Ascii: " + (int)packetType + ")");
            }

        } catch (Exception e) {
            logger.error("Error processing packet", e);
        }
    }

    private void reply(Channel channel, String text) {
        channel.eventLoop().execute(() -> {
            ByteBuf buffer = channel.alloc().directBuffer();
            buffer.writeBytes(text.getBytes(StandardCharsets.UTF_8));
            channel.writeAndFlush(buffer);
            logger.debug("✅ Sent Reply: " + text);
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
