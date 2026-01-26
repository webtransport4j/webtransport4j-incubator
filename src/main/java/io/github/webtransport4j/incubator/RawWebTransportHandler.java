package io.github.webtransport4j.incubator;

import org.apache.log4j.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class RawWebTransportHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = Logger.getLogger(RawWebTransportHandler.class.getName());
    
    // Track state per handler instance (per stream)
    private boolean protocolHeaderConsumed = false;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (ctx.channel() instanceof io.netty.handler.codec.quic.QuicStreamChannel) {
        long streamId = ((io.netty.handler.codec.quic.QuicStreamChannel) ctx.channel()).streamId();
    
        if (streamId == 0) {
            ctx.fireChannelRead(msg);
            return;
        }
    }
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf data = (ByteBuf) msg;
       
        if (!protocolHeaderConsumed) {
        
            if (data.readableBytes() < 2) {
                return;
            }

           
            data.markReaderIndex();

            try {
                
                long streamType = WebTransportUtils.readVariableLengthInt(data);
                long sessionId = WebTransportUtils.readVariableLengthInt(data);
                
               if (streamType == 0x41) {
            logger.info("🆕 Client Initiated BIDIRECTIONAL Stream | Session: " + sessionId + " | StreamID: " + ctx.channel().id());
        } else if (streamType == 0x54) {
            logger.info("➡️ Client Initiated UNIDIRECTIONAL Stream | Session: " + sessionId);
        } else {
            logger.warn("❓ Unknown Stream Type: " + streamType);
        }
                ctx.channel().attr(WebTransportUtils.STREAM_TYPE_KEY).set(streamType);
                ctx.channel().attr(WebTransportUtils.SESSION_ID_KEY).set(sessionId);
                
                logger.debug("✅ Protocol Header Consumed | Type: " + streamType + " Session: " + sessionId);
                protocolHeaderConsumed = true;

            } catch (Exception e) {
                // If we ran out of bytes while reading the VarInts, reset and wait
                data.resetReaderIndex();
                return;
            }
        }

       
        if (!data.isReadable()) {
            data.release();
            return;
        }

        logger.debug("   -> Firing Body (" + data.readableBytes() + " bytes) to App Layer...");
        
        ctx.fireChannelRead(data); //message dispatcher
    }
}