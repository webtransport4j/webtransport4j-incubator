package io.github.webtransport4j.incubator;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class QuicGlobalSniffer extends ChannelInboundHandlerAdapter {
        private final String prefix;
        public QuicGlobalSniffer(String prefix) {
            this.prefix = prefix;
        }
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                ByteBuf data = (ByteBuf) msg;
                int len = data.readableBytes();
                String hex = ByteBufUtil.hexDump(data);
                // Formatting for readability
                if (len > 0) {
                    System.out.println("👀 [" + prefix + "] ID:" + ctx.channel().id().asShortText() + " LEN:" + len);
                    System.out.println("    HEX: " + hex);
                }
            } else {
                System.out.println("👀 [" + prefix + "] MsgType: " + msg.getClass().getSimpleName());
            }
            // Pass it on!
            ctx.fireChannelRead(msg);
        }
    }
