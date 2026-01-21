package io.github.webtransport4j.incubator;

/**
 * @author https://github.com/sanjomo
 * @date 20/01/26 1:01 am
 */

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Logger;

public class WebTransportMessageDispatcher extends SimpleChannelInboundHandler<WebTransportMessage> {
    
    private static final Logger logger = Logger.getLogger(WebTransportMessageDispatcher.class.getName());
    // Simulating your Business Logic Thread Pool
    private static final ExecutorService businessPool = Executors.newFixedThreadPool(4);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebTransportMessage msg) {
        // We received the clean POJO!
        // The previous handler passed us ownership, so we must be careful with the
        // payload.
        // Offload to Business Thread
        // CRITICAL: We must .retain() the payload because we are switching threads.
        // If we don't, Netty will release it as soon as this method returns.
        msg.retain();

        businessPool.submit(() -> {
            try {
                processBusinessLogic(msg);
            } finally {
                // CRITICAL: Release memory when business logic is done
                msg.release();
            }
        });
    }

    private void processBusinessLogic(WebTransportMessage msg) {
        try {
            String content = msg.getPayload().toString(StandardCharsets.UTF_8);
            logger.debug("⚡️ [APP LAYER] Dispatched to Controller:");
            logger.debug("    Path: " + msg.getPath());
            logger.debug("    Type: " + msg.getType());
            logger.debug("    Data: " + content);

            // simulating the reply
            if (msg.getType().equals(WebTransportMessage.MessageType.BIDIRECTIONAL)) {
                msg.reply("ACK BI: I received the message from " + msg.getPath() + ": " + content);
            }
            if (msg.getType().equals(WebTransportMessage.MessageType.DATAGRAM)) {
                msg.sendDatagram("ACK DG: I received the message from " + msg.getPath() + ": " + content);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}