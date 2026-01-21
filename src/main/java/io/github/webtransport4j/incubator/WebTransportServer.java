package io.github.webtransport4j.incubator;

import io.github.webtransport4j.incubator.applayer.ServerPushService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.http3.Http3Settings;
import io.netty.handler.codec.http3.Http3UnknownFrame;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.webtransport4j.incubator.WebTransportUtils.readVariableLengthInt;

public class WebTransportServer {
    static final int PORT = 4433;
    static final AttributeKey<String> SESSION_PATH_KEY = AttributeKey.valueOf("wt.session.path.key");
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 STARTING DEBUG SERVER...");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        QuicSslContext sslContext = QuicSslContextBuilder.forServer(
                        new File("/Users/sam/Documents/localhost-key.pem"),
                        null,
                        new File("/Users/sam/Documents/localhost.pem"))
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
        Http3Settings settings = Http3Settings.defaultSettings()
                .enableConnectProtocol(true)
                .setenablewebtransport(true)
                .enableH3Datagram(true);
        ChannelHandler serverCodec = Http3.newQuicServerCodecBuilder()
                .sslContext(sslContext)
                .maxIdleTimeout(30, TimeUnit.SECONDS)
                .initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(1_000_000)
                .initialMaxStreamDataBidirectionalRemote(1_000_000)
                .initialMaxStreamsBidirectional(100)
                .datagram(10000, 10000)
                .initialMaxStreamsUnidirectional(100)
                .initialMaxStreamDataUnidirectional(1_000_000)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(new ChannelInitializer<QuicChannel>() {
                    @Override
                    protected void initChannel(QuicChannel ch) {
                        ch.pipeline().addFirst(new QuicGlobalSniffer("GLOBAL-CONN"));
                        InetSocketAddress remote = (InetSocketAddress) ch.remoteSocketAddress();
                        String ip = remote.getAddress().getHostAddress();
                        int port = remote.getPort();
                        String nettyId = ch.id().asShortText();
                        // 2. PRINT NICE LOG
                        System.out.println("\n🔌 NEW QUIC CONNECTION ESTABLISHED");
                        System.out.println("    ├── 🌍 Remote IP:   " + ip);
                        System.out.println("    ├── 🚪 Remote Port: " + port);
                        System.out.println("    └── 🆔 Channel ID:  " + nettyId);
                        ch.attr(WebTransportSessionManager.WT_SESSION_MGR).set(new WebTransportSessionManager());
                        ch.pipeline().addLast(new WebTransportDatagramHandler());
                        ch.pipeline().addLast(new WebTransportMessageDispatcher());
                        ch.pipeline().addLast(new Http3ServerConnectionHandler(
                                new ChannelInitializer<QuicStreamChannel>() {
                                    @Override
                                    protected void initChannel(QuicStreamChannel stream) {
                                        // DEBUG: Print when a stream is created
                                        // System.out.println("🌊 Stream Created: " + stream.id());
                                        QuicChannel quic = stream.parent();
                                        WebTransportSessionManager mgr = quic.attr(WebTransportSessionManager.WT_SESSION_MGR).get();
                                        stream.pipeline().addFirst(new WebTransportDetectorHandler(mgr));
                                        stream.pipeline().addLast(new Http3RequestStreamInboundHandler() {
                                            @Override
                                            protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                                                System.out.println("=== [DEBUG] Received HTTP/3 Headers ===");

                                                // Loop through all headers and print them
                                                for (Map.Entry<CharSequence, CharSequence> header : frame.headers()) {
                                                    System.out.println(header.getKey() + ": " + header.getValue());
                                                }

                                                System.out.println("=======================================");
                                                System.out.println("📜 HTTP/3 Headers Received: " + frame.headers().path());
                                                CharSequence path = frame.headers().path();
                                                CharSequence method = frame.headers().method();
                                                CharSequence protocol = frame.headers().get(":protocol");

                                                if ("CONNECT".contentEquals(method) && "webtransport".contentEquals(protocol)) {
                                                    ctx.channel().parent().attr(SESSION_PATH_KEY).set(path.toString());
                                                    System.out.println("✅ Handshake Success for Path: " + path);
                                                    Http3HeadersFrame resp = new DefaultHttp3HeadersFrame();
                                                    resp.headers().status("200");
                                                    ctx.writeAndFlush(resp);
                                                    mgr.register((QuicStreamChannel) ctx.channel());
                                                    QuicStreamChannel connectStream = (QuicStreamChannel) ctx.channel();
                                                    mgr.register(connectStream);
                                                    long sessionId = connectStream.streamId();

                                                    // Trigger server initiated uni-stream
                                                    System.out.println("⏰ Creating Server-Push Stream for Session " + sessionId);
                                                    //new ServerPushService(quic, sessionId).startPushing();
                                                    System.out.println("⏳ Creating Push Stream...");
                                                    String key = "key";
                                                    WebTransportUtils.createUniStream(quic, sessionId, key)
                                                            .addListener(future -> {
                                                                if (future.isSuccess()) {
                                                                    System.out.println("🚀 Push Stream Ready!");

                                                                    //this is for testing, remove this, just poc
                                                                    quic.eventLoop().scheduleAtFixedRate(() -> {
                                                                        if (connectStream.isActive()) {
                                                                            ServerPushService.INSTANCE.sendTo(key, String.valueOf(System.nanoTime()));
                                                                        }
                                                                    }, 0, 1, TimeUnit.SECONDS);
                                                                    connectStream.closeFuture().addListener(f -> {
                                                                        ServerPushService.INSTANCE.unregister(key);
                                                                    });
                                                                } else {
                                                                    System.err.println("❌ Failed: " + future.cause());
                                                                }
                                                            });
                                                }
                                                ReferenceCountUtil.release(frame);
                                            }
                                            @Override protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) { ctx.fireChannelRead(frame); }
                                            @Override protected void channelRead(ChannelHandlerContext ctx, Http3UnknownFrame frame) { ctx.fireChannelRead(frame); }
                                            @Override protected void channelInputClosed(ChannelHandlerContext ctx) {
                                                System.out.println("🔒 Stream Closed: " + ctx.channel().id());
                                                ctx.close();
                                            }
                                        });
                                        stream.pipeline().addLast(new WebTransportMessageDispatcher());
                                        // DEBUG: Catch-all exception handler
                                        stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                            @Override
                                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                                System.err.println("❌ PIPELINE ERROR: " + cause.getMessage());
                                                cause.printStackTrace();
                                            }
                                        });
                                    }
                                },
                                null,
                                (streamType) -> {
                                    if (streamType == 0x54) {
                                        return new ChannelInitializer<QuicStreamChannel>() {
                                            @Override
                                            protected void initChannel(QuicStreamChannel ch) {
                                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                                    private boolean sessionHeaderRead = false;
                                                    @Override
                                                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                                        if (msg instanceof ByteBuf) {
                                                            ByteBuf data = (ByteBuf) msg;
                                                            if (!sessionHeaderRead) {
                                                                readVariableLengthInt(data);
                                                                sessionHeaderRead = true;
                                                            }
                                                            if (!data.isReadable()) {
                                                                data.release();
                                                                return;
                                                            }
                                                            String savedPath = ctx.channel().parent().attr(WebTransportServer.SESSION_PATH_KEY).get();
                                                            WebTransportMessage wtMsg = new WebTransportMessage(
                                                                    WebTransportMessage.MessageType.UNIDIRECTIONAL,
                                                                    (savedPath != null) ? savedPath : "?",
                                                                    0,
                                                                    ((QuicStreamChannel) ctx.channel()).streamId(),
                                                                    data,
                                                                    ctx.channel()
                                                            );
                                                            ctx.fireChannelRead(wtMsg);
                                                        } else {
                                                            ctx.fireChannelRead(msg);
                                                        }
                                                    }
                                                });
                                                ch.pipeline().addLast(new WebTransportMessageDispatcher());
                                            }
                                        };
                                    }
                                    return null;
                                }, new DefaultHttp3SettingsFrame(settings), true
                        ));
                    }
                }).build();
        Channel ch = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(serverCodec)
                .bind(new InetSocketAddress(PORT))
                .sync()
                .channel();
        System.out.println("✅ WebTransport server listening on " + PORT);
        ch.closeFuture().sync();
    }
    // --- HANDLERS ---

    static class QuicGlobalSniffer extends ChannelInboundHandlerAdapter {
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
    static class WebTransportDetectorHandler extends ChannelInboundHandlerAdapter {
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
    static class RawWebTransportHandler extends ChannelInboundHandlerAdapter {
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
                    long streamType = readVariableLengthInt(data);
                    long sessionId = readVariableLengthInt(data);
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
    static class WebTransportDatagramHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf content) {
            System.out.println("☄️ DatagramHandler received data");
            long quarterStreamId = readVariableLengthInt(content);
            long sessionId = quarterStreamId << 2;
            String savedPath = ctx.channel().attr(SESSION_PATH_KEY).get();
            WebTransportMessage wtMsg = new WebTransportMessage(
                    WebTransportMessage.MessageType.DATAGRAM,
                    (savedPath != null) ? savedPath : "?",
                    sessionId,
                    -1,
                    content.retain(),
                    ctx.channel()
            );
            ctx.fireChannelRead(wtMsg);
        }
    }


}