package nettycase.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import util.Counter;

import java.util.List;

@SuppressWarnings("all")
public class ByteToLongDecoder extends ByteToMessageDecoder {
    /**
     *
     * decode 会根据接收的数据，被调用多次, 直到确定没有新的元素被添加到list
     * , 或者是ByteBuf 没有更多的可读字节为止
     * 如果list out 不为空，就会将list的内容传递给下一个 channelinboundhandler处理, 该处理器的方法也会被调用多次
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        System.out.println("字节 转 Long—解码器 被调用" + ", counter = " + Counter.getAndIncrement());
        /** 因为 long 8个字节, 需要判断有8个字节，才能读取一个long. **/
        if(in.readableBytes() >= 8) {
            out.add(in.readLong());
        }
    }
}
