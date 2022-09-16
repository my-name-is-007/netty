package nettycase.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;

import java.util.concurrent.ConcurrentHashMap;

public class ServerInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast("HttpServerCodec",new HttpServerCodec());
        pipeline.addLast("HttpServerHandler", new HttpServerHandler());
//        pipeline.addLast("HttpServerHandler3", new HttpServerHandler3());
//        pipeline.addLast("HttpServerHandler2", new HttpServerHandler2());

        System.out.println(Thread.currentThread().getName() + "—初始化完成, 成功加入两个处理器");
    }

}
