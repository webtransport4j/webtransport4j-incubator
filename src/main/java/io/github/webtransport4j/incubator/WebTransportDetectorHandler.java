package io.github.webtransport4j.incubator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

public class WebTransportDetectorHandler extends ChannelInboundHandlerAdapter {
        private final WebTransportSessionManager mgr;
        private boolean checked = false;
        WebTransportDetectorHandler(WebTransportSessionManager mgr) {
            this.mgr = mgr;
        }
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (checked) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (msg instanceof ByteBuf) {
                ByteBuf debugData = (ByteBuf) msg;
                String rawHex = ByteBufUtil.hexDump(debugData);
                System.out.println("📦 [RAW HANDLER] Incoming Bytes: " + debugData.readableBytes());
                System.out.println("   HEX: [" + rawHex + "]");
                System.out.println("   TXT: " + debugData.duplicate().toString(StandardCharsets.UTF_8));
                ByteBuf in = (ByteBuf) msg;
                if (in.readableBytes() < 1) return;
                // Read the first byte (peek)
                int firstByte = in.getByte(in.readerIndex()) & 0xFF;
                int secondByte = in.getByte(in.readerIndex() + 1) & 0xFF;

                System.out.println("🔍 CHECKING BYTE: 0x" + Integer.toHexString(firstByte) + " (Stream: " + ctx.channel().id() + ")");


                // Standard HTTP/3 Frames (Single Byte VLI)
                boolean isStandardHttp3 = (
                        firstByte == 0x00 || // DATA
                                firstByte == 0x01 || // HEADERS
                                firstByte == 0x03 || // CANCEL_PUSH
                                firstByte == 0x04 || // SETTINGS
                                firstByte == 0x05 || // PUSH_PROMISE
                                firstByte == 0x07 || // GOAWAY
                                firstByte == 0x0d    // MAX_PUSH_ID
                );

                // WebTransport Streams (Type 65(0x41)=0x4041, Type 84=0x4054)
                // Both start with 0x40 because they are 2-byte VLIs
                // Uni directional type 84=0x40 0x54 handled in Http3ServerConnectionHandler's unknownInboundStreamHandlerFactory
                // so no need to handle uni directional steam WT here
                boolean isWebTransport = (firstByte == 0x40 && secondByte == 0x41);

                if (isStandardHttp3) {
                    System.out.println("👉 Decision: Standard HTTP/3");
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(msg);
                } else if (isWebTransport) {
                    System.out.println("👉 Decision: Raw WebTransport");
                    ChannelPipeline p = ctx.pipeline();
                    // SAFE REMOVAL LOGIC
                    // we should remove http3 logic as it is no more http3, its webtrasnport/raw quic stream
                    List<String> toRemove = new ArrayList<>();
                    for (String name : p.names()) {
                        ChannelHandler h = p.get(name);
                        if (h == this || h instanceof QuicGlobalSniffer) continue;
                        if ((h != null && h.getClass().getName().startsWith("io.netty.handler.codec.http3."))) {
                            toRemove.add(name);
                        }
                    }
                    for (String name : toRemove) {
                        System.out.println("   🗑 Removing handler: " + name);
                        p.remove(name);
                    }
                    System.out.println("   ➕ Adding RawWebTransportHandler");
                    p.addLast(new RawWebTransportHandler());
                    System.out.println("   ➕ Adding WebTransportMessageDispatcher");
                    p.addLast(new WebTransportMessageDispatcher());
                    p.remove(this);
                    System.out.println("🔥 FIRING RAW DATA to next handler...");
                    ctx.fireChannelRead(msg);
                } else {
                    System.out.println("👉 Unknown Frame: 0x" + Integer.toHexString(firstByte));
                }
                checked = true;
            } else {
                ctx.fireChannelRead(msg);
            }
        }
    }