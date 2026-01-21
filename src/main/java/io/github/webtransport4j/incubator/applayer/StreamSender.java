package io.github.webtransport4j.incubator.applayer;

/**
 * @author https://github.com/sanjomo
 * @date 20/01/26 1:25 am
 */
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.CharsetUtil;

public class StreamSender {
    private final Channel streamChannel;

    public StreamSender(Channel streamChannel) {
        this.streamChannel = streamChannel;
    }

    public Channel getStreamChannel() {
        return streamChannel;
    }

    // Write data to the EXISTING stream
    public void send(String payload) {
        if (streamChannel.isActive()) {
            streamChannel.writeAndFlush(Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8)).addListener(future -> {
                if (!future.isSuccess()) {
                    System.err.println("❌ Push Failed: " + future.cause());
                } else {
                    System.out.println("✅ Push Sent: " + payload);
                }
            });
        } else {
            System.err.println("❌ Stream is closed, cannot push.");
        }
    }

    public void close() {
        streamChannel.close();
    }
}