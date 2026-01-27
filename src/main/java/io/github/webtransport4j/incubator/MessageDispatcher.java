package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder; // CHANGED: Use this instead of SimpleChannelInboundHandler
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.apache.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// 1. EXTEND ByteToMessageDecoder (Crucial for handling fragmentation)
public class MessageDispatcher extends ByteToMessageDecoder {

    private static final Logger logger = Logger.getLogger(MessageDispatcher.class.getName());
    private static final ExecutorService businessPool = Executors.newFixedThreadPool(4);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Channel channel = ctx.channel();

        // --- DATAGRAMS (No Stream Framing) ---
        if (!(channel instanceof QuicStreamChannel)) {
            // Datagrams are always whole packets
            ByteBuf fullMsg = in.readBytes(in.readableBytes());
            processDatagramPacket(ctx, fullMsg);
            return;
        }

        // --- STREAMS (Handle Headers & Fragmentation) ---
        while (in.isReadable()) {
            in.markReaderIndex(); // Save current position

            // 1. Check if we have enough bytes for the smallest header (1 byte)
            if (in.readableBytes() < 1) {
                in.resetReaderIndex();
                return; // Wait for more network data
            }

            // 2. Read the Header
            int firstByte = in.readUnsignedByte();
            long length = firstByte & 0x7F; // Remove binary flag

            // 3. Handle Extended Lengths (126 -> 2 bytes, 127 -> 8 bytes)
            if (length == 126) {
                if (in.readableBytes() < 2) {
                    in.resetReaderIndex();
                    return; // Wait for length bytes
                }
                length = in.readUnsignedShort();
            } else if (length == 127) {
                if (in.readableBytes() < 8) {
                    in.resetReaderIndex();
                    return; // Wait for length bytes
                }
                length = in.readLong();
            }

            // 4. Check if we have the FULL payload
            if (in.readableBytes() < length) {
                in.resetReaderIndex(); // REWIND! Wait for the rest of the body.
                // logger.debug("⏳ Waiting for body... Need " + length + " bytes");
                return;
            }

            // 5. Read the payload
            ByteBuf payload = in.readBytes((int) length);

            // 6. Process
            dispatchStreamPacket(ctx, payload);
        }
    }

    private void dispatchStreamPacket(ChannelHandlerContext ctx, ByteBuf msg) {
        Channel channel = ctx.channel();
        QuicStreamChannel stream = (QuicStreamChannel) channel;
        Long typeAttr = stream.attr(WebTransportUtils.STREAM_TYPE_KEY).get();
        String pathAttr = stream.parent().attr(WebTransportServer.SESSION_PATH_KEY).get();

        String path = (pathAttr != null) ? pathAttr : "?";
        String transportType = (typeAttr != null && typeAttr == 0x54) ? "UNIDIRECTIONAL" : "BIDIRECTIONAL";

        msg.retain();
        businessPool.submit(() -> {
            try {
                processSocketIOPacket(channel, path, transportType, msg);
            } finally {
                msg.release();
            }
        });
    }

    private void processDatagramPacket(ChannelHandlerContext ctx, ByteBuf msg) {
        Channel channel = ctx.channel();
        WebTransportUtils.readVariableLengthInt(msg); // consume datagram ID
        String pathAttr = channel.attr(WebTransportServer.SESSION_PATH_KEY).get();
        String path = (pathAttr != null) ? pathAttr : "?";

        msg.retain();
        businessPool.submit(() -> {
            try {
                processSocketIOPacket(channel, path, "DATAGRAM", msg);
            } finally {
                msg.release();
            }
        });
    }

    private void processSocketIOPacket(Channel channel, String path, String transportType, ByteBuf payload) {
        try {
            String content = payload.toString(StandardCharsets.UTF_8);
            if (content.isEmpty())
                return;

            // 1. Parse Engine.IO Packet Type
            char engineType = content.charAt(0);
            String data = (content.length() > 1) ? content.substring(1) : "";

            logger.debug("⚡️ [SOCKET.IO] " + transportType + " | Type: " + engineType + " | Data: " + data);

            switch (engineType) {
                case '0': // ENGINE.IO OPEN
                    // Reply with Handshake Data
                    reply(channel,
                            "0{\"sid\":\"69f5ba63-c0ed-4eb8-9010-f4a0c27725d4\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":60000}");
                     channel.eventLoop().scheduleAtFixedRate(() -> {
                        if (channel.isActive()) {
                            logger.debug("❤️ Sending Server PING");
                            reply(channel, "2"); // Engine.IO PING packet
                        }
                    }, 25, 25, TimeUnit.SECONDS);
                    break;

                case '1': // CLOSE
                    logger.info("❌ Received CLOSE.");
                    channel.close();
                    break;

                case '2': // PING
                    // Reply with PONG (3)
                    logger.debug("❤️ Received PING. Sending PONG...");
                    reply(channel, "3");
                    break;

                case '3': // PONG
                    logger.debug("💓 Received PONG (Client alive).");
                    break;

                case '4': // MESSAGE
                    // Check for Socket.IO Layer 2 Connect (40)
                    if (data.startsWith("0")) {
                        logger.info("🔌 Client Connecting to Namespace: " + data);
                        // V4 Fix: Must return SID object
                        reply(channel, "40{\"sid\":\"69f5ba63-c0ed-4eb8-9010-f4a0c27725d4\"}");
                    } else {
                        logger.info("📩 Received MESSAGE: " + data);
                        reply(channel,"4"+ data);
                    }
                    break;

                default:
                    logger.warn("⚠️ Unknown Packet Type: '" + engineType + "'");
            }
        } catch (Exception e) {
            logger.error("Error processing packet", e);
        }
    }

    private void reply(Channel channel, String text) {
        channel.eventLoop().execute(() -> {
            ByteBuf buffer = channel.alloc().directBuffer();
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            int len = data.length;

            // --- HEADER ENCODING ---
            if (len < 126) {
                buffer.writeByte(len);
            } else if (len < 65536) {
                buffer.writeByte(126);
                buffer.writeShort(len);
            } else {
                buffer.writeByte(127);
                buffer.writeLong(len);
            }
            buffer.writeBytes(data);

            channel.writeAndFlush(buffer).addListener(future -> {
                if (!future.isSuccess()) {
                    logger.error("❌ Write Failed", future.cause());
                }
            });
            logger.debug("✅ Replied: " + text);
        });
    }
}