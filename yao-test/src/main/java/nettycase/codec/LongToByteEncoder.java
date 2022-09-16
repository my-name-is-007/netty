package nettycase.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import util.Counter;

@SuppressWarnings("all")
public class LongToByteEncoder extends MessageToByteEncoder<Long> {
    /**
     * 对数据编码.
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Long msg, ByteBuf out) {
        System.out.println("Long 转 字节—编码器 被 调用" + "msg = " + msg + ", counter = " + Counter.getAndIncrement());
        out.writeLong(msg);
    }
}
