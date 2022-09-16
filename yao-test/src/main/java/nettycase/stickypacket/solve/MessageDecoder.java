package nettycase.stickypacket.solve;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.nio.charset.Charset;
import java.util.List;

@SuppressWarnings("all")
public class MessageDecoder extends ReplayingDecoder<Void> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        System.out.println("消息 <===入站 解码器===> 被调用");

        int length = in.readInt();
        byte[] content = new byte[length];
        in.readBytes(content);

        /** 封装成 MessageProtocol 对象，放入 out， 传递下一个handler业务处理. **/
        MessageProtocol messageProtocol = new MessageProtocol(length, content);
        out.add(messageProtocol);
    }

    /**
     * 这个方法无业务意义, 仅仅是为了测试传进来的数据是咋样的.
     * 因为我一直好奇解决粘包的原理是什么.
     * 我在debug到 decode()方法时, 不停的调用这个方法, 传进 ByteBuf, 然后发现返回了5次不一样的数据.
     * 这说明第一次decode被调用时, 就将客户端发送的全部数据都放到了 ByteBuf中, 只是自己在拿数据时, 并未拿取全部的数据.
     * 余下的数据在下一次拿到. 而将数据拿完之后, 也就不会再调用 decode()方法了.
     * 至于说如何根据 数量决定是否调用 decode()方法, 尚不知道.
     */
    public static String handle(ByteBuf in){
        int length = in.readInt();
        byte[] content = new byte[length];
        in.readBytes(content);
        return "长度 = " + length + "内容: " + byteToString(content);
    }

    private static String byteToString(byte[] con){
        return new String(con, Charset.forName("utf-8"));
    }

}
