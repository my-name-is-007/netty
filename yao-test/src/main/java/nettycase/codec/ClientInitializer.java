package nettycase.codec;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;


public class ClientInitializer extends ChannelInitializer<SocketChannel> {
    @Override
    protected void initChannel(SocketChannel ch) {

        ChannelPipeline pipeline = ch.pipeline();

        //加入一个出站的handler 对数据进行一个编码
        pipeline.addLast(new LongToByteEncoder());

        //这时一个入站的解码器(入站handler )
        pipeline.addLast(new ByteToLongDecoder());
//        pipeline.addLast(new ByteToLongDecoder2());
        //加入一个自定义的handler ， 处理业务
        pipeline.addLast(new ClientHandler());


    }
}
