package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicStreamChannel;

public class RawWebTransportHandler extends ChannelInboundHandlerAdapter {
        private boolean protocolHeaderConsumed = false;
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                ctx.fireChannelRead(msg);
                return;
            }
            ByteBuf data = (ByteBuf) msg;
            boolean shouldRelease = true; //this handler is responsible
            try {

                if (!protocolHeaderConsumed) {
                    if (data.readableBytes() < 2) return;
                    long streamType = WebTransportUtils.readVariableLengthInt(data);
                    long sessionId = WebTransportUtils.readVariableLengthInt(data);
                    System.out.println("✅ Fixed Protocol Header | Type: " + streamType + " Session: " + sessionId);
                    protocolHeaderConsumed = true;
                    if (!data.isReadable()) {
                        return;
                    }
                }
                String savedPath = ctx.channel().parent().attr(WebTransportServer.SESSION_PATH_KEY).get();
                WebTransportMessage wtMsg = new WebTransportMessage(
                        WebTransportMessage.MessageType.BIDIRECTIONAL,
                        (savedPath != null) ? savedPath : "?",
                        0,
                        ((QuicStreamChannel) ctx.channel()).streamId(),
                        data,
                        ctx.channel()
                );
                System.out.println("   -> Created Message. Firing...");
                ctx.fireChannelRead(wtMsg);
                shouldRelease = false; // this handler is not responsible
            } finally {
                if (shouldRelease) {
                    data.release();
                }
            }
        }
    }
