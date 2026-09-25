package effortanalyzer.wl15;

import effortanalyzer.library.DeprecatedApi;
import java.util.ArrayList;
import java.util.List;

/** Netty 4.0.x to 4.1.135.Final migration rules. */
public class Netty4Rules {

    private static final String LIBRARY = "Netty 4.1.135.Final";

    private Netty4Rules() {}

    public static List<DeprecatedApi> load() {
        List<DeprecatedApi> rules = new ArrayList<>();

        // ChannelHandlerContext.attr() deprecated -- use Channel.attr()
        add(rules, "io.netty.channel.ChannelHandlerContext", "attr", "HIGH",
                "Use Channel.attr(key) instead of ChannelHandlerContext.attr(key).",
                "ChannelHandlerContext.attr() deprecated in Netty 4.1 -- use Channel.attr()");

        // ChannelInboundHandlerAdapter -- still present but userEventTriggered deprecated
        add(rules, "io.netty.channel.ChannelInboundHandlerAdapter", "userEventTriggered", "HIGH",
                "Replace userEventTriggered() with channelInboundEvent() (Netty 4.1.86+ event model).",
                "ChannelInboundHandlerAdapter.userEventTriggered() deprecated -- use channelInboundEvent()");

        // ByteBuf reference counting changes
        add(rules, "io.netty.buffer.ByteBuf", "refCnt", "WARNING",
                "Verify reference counting; ByteBuf lifecycle management tightened in 4.1.x.",
                "ByteBuf.refCnt() usage -- ensure paired retain()/release() calls");

        add(rules, "io.netty.buffer.ByteBuf", "discardReadBytes", "WARNING",
                "Review discardReadBytes() usage; prefer discardSomeReadBytes() for GC pressure reduction.",
                "ByteBuf.discardReadBytes() retained but discardSomeReadBytes() preferred in 4.1");

        // Unpooled allocator changes
        add(rules, "io.netty.buffer.Unpooled", "directBuffer", "WARNING",
                "Prefer PooledByteBufAllocator for high-throughput scenarios.",
                "Unpooled.directBuffer() retained but PooledByteBufAllocator recommended in 4.1");

        // NioEventLoopGroup constructor
        add(rules, "io.netty.channel.nio.NioEventLoopGroup", null, "WARNING",
                "Verify NioEventLoopGroup thread count; default changed in 4.1.x.",
                "NioEventLoopGroup default thread count formula changed in Netty 4.1");

        // ChannelFuture / ChannelPromise
        add(rules, "io.netty.channel.ChannelFuture", "addListener", "WARNING",
                "Consider using ChannelFutureListener.CLOSE_ON_FAILURE for error handling.",
                "ChannelFuture.addListener() retained; verify listener removal on channel close");

        // EpollEventLoopGroup -- linux-specific
        add(rules, "io.netty.channel.epoll.EpollEventLoopGroup", null, "WARNING",
                "Verify Epoll availability at runtime; use Epoll.isAvailable() guard.",
                "EpollEventLoopGroup: native transport detection pattern changed in 4.1.x");

        // HttpObjectDecoder
        add(rules, "io.netty.handler.codec.http.HttpObjectDecoder", null, "WARNING",
                "Review HttpObjectDecoder constructor; maxChunkSize default changed.",
                "HttpObjectDecoder constructor parameters changed in Netty 4.1.x");

        // SslContext builder
        add(rules, "io.netty.handler.ssl.SslContextBuilder", "forClient", "WARNING",
                "Review SslContextBuilder.forClient(); TLS defaults tightened in 4.1.x.",
                "SslContextBuilder default TLS version changed to TLSv1.2+ in Netty 4.1.x");

        // FixedChannelPool
        add(rules, "io.netty.channel.pool.FixedChannelPool", null, "WARNING",
                "Verify FixedChannelPool AcquireTimeoutAction; constructor signature changed.",
                "FixedChannelPool constructor overloads added in 4.1.x; review usage");

        // netty-codec: HttpHeaders changes
        add(rules, "io.netty.handler.codec.http.HttpHeaders", "addHeader", "HIGH",
                "Replace HttpHeaders.addHeader() with HttpHeaders.add(); method renamed.",
                "HttpHeaders.addHeader() removed; use HttpHeaders.add()");

        add(rules, "io.netty.handler.codec.http.HttpHeaders", "setHeader", "HIGH",
                "Replace HttpHeaders.setHeader() with HttpHeaders.set(); method renamed.",
                "HttpHeaders.setHeader() removed; use HttpHeaders.set()");

        add(rules, "io.netty.handler.codec.http.HttpHeaders", "getHeader", "HIGH",
                "Replace HttpHeaders.getHeader() with HttpHeaders.get(); method renamed.",
                "HttpHeaders.getHeader() removed; use HttpHeaders.get()");

        // DefaultChannelGroup changes
        add(rules, "io.netty.channel.group.DefaultChannelGroup", null, "WARNING",
                "Verify DefaultChannelGroup constructor; executor param required in 4.1.x.",
                "DefaultChannelGroup now requires EventExecutor in constructor");

        return rules;
    }

    private static void add(List<DeprecatedApi> rules, String className, String methodName,
                             String severity, String replacement, String description) {
        rules.add(new DeprecatedApi(LIBRARY, className, methodName, severity, replacement, description));
    }
}