package io.github.webtransport4j.incubator;

import org.apache.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class RawWebTransportHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = Logger.getLogger(RawWebTransportHandler.class.getName());
    private boolean protocolHeaderConsumed = false;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf data = (ByteBuf) msg;
        
        if (!protocolHeaderConsumed) {
            if (data.readableBytes() < 2)
                return;
            long streamType = WebTransportUtils.readVariableLengthInt(data);
            long sessionId = WebTransportUtils.readVariableLengthInt(data);
            ctx.channel().attr(WebTransportUtils.STREAM_TYPE_KEY).set(streamType);
            ctx.channel().attr(WebTransportUtils.SESSION_ID_KEY).set(sessionId);
            logger.debug("✅ Fixed Protocol Header | Type: " + streamType + " Session: " + sessionId);
            protocolHeaderConsumed = true;
            if (!data.isReadable()) {
                return;
            }
        }
        logger.debug("   -> Firing Raw ByteBuf...");
        ctx.fireChannelRead(data.retain());
        //do not realease as this will be handled by the next handler
        
    }
}
