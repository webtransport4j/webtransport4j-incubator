package io.github.webtransport4j.incubator;

import org.apache.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class WebTransportDatagramHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger logger = Logger.getLogger(WebTransportDatagramHandler.class.getName());

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        logger.debug("☄️ DatagramHandler received data");
        ctx.fireChannelRead(msg.retain());
    }
}
