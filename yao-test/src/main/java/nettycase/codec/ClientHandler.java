package nettycase.codec;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.CharsetUtil;
import util.Counter;

@SuppressWarnings("all")
public class ClientHandler  extends SimpleChannelInboundHandler<Long> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Long msg) {
        System.out.println("客户端 Handler 收到服务器消息: " + msg + ", counter = " + Counter.getAndIncrement());
    }

    /** 重写channelActive 发送数据. **/
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("Client Handler 发送数据" + ", counter = " + Counter.getAndIncrement());
        //ctx.writeAndFlush(123456L);
        /**
         * 如果发送下面的数据, 则出站时的编码器不会起作用, 因为你的编码器不能解析此类型的数据.
         * 你的编码器为 {@link LongToByteEncoder}, 其父类为 {@link MessageToByteEncoder},
         * 其{@link ByteToMessageDecoder#write}会通过 acceptOutboundMessage() 判断是否能处理当前数据, 不能处理则直接放过.
         */
        ctx.writeAndFlush(Unpooled.copiedBuffer("abcdabcdabcdabcd", CharsetUtil.UTF_8));
    }
}
