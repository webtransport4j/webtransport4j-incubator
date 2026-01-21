package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class WebTransportDatagramHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            System.out.println("☄️ DatagramHandler received data");
            long quarterStreamId = WebTransportUtils.readVariableLengthInt(msg);
            long sessionId = quarterStreamId << 2;
            String savedPath = ctx.channel().attr(WebTransportServer.SESSION_PATH_KEY).get();
            WebTransportMessage wtMsg = new WebTransportMessage(
                    WebTransportMessage.MessageType.DATAGRAM,
                    (savedPath != null) ? savedPath : "?",
                    sessionId,
                    -1,
                    msg.retain(),
                    ctx.channel()
            );
            ctx.fireChannelRead(wtMsg);
        }
    }
