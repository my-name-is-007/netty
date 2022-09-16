package nettycase.groupchat;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.text.SimpleDateFormat;

public class ServerHandler extends SimpleChannelInboundHandler<String> {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 将连接信息放到集合(列表、Map)中, 进行消息的转发等功能.
     * 如果想实现私聊(点对点的聊天)功能, 可以使用Map. 在A发送的信息中包含B的标识信息, 然后从Map中找到即可.
     * public static List<Channel> channels = new ArrayList<Channel>();
     * public static Map<String, Channel> channels = new HashMap<String,Channel>();
     * Channel 组, 管理所有的channel.
     * GlobalEventExecutor.INSTANCE: 全局的事件执行器, 是一个单例.
     * 注意: 这里要定义为静态的. 因为会生成多个 ServerHandler.
     */
    private static ChannelGroup channelGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * handlerAdded: 连接建立，一旦连接，第一个被执行.
     * 将当前channel 加入到  channelGroup.
     * {@link ChannelGroup#writeAndFlush(Object)} 会遍历 自己内部所有 channel, 并发送消息, 我们不需要自己遍历.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channelGroup.writeAndFlush("[客户端]" + channel.remoteAddress() + " 加入聊天" + sdf.format(new java.util.Date()) + " \n");
        channelGroup.add(channel);
    }

    /** 断开连接, 将xx客户离开信息推送给当前在线的客户. **/
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channelGroup.writeAndFlush("[客户端]" + channel.remoteAddress() + " 离开了\n");
        System.out.println("channelGroup size" + channelGroup.size());
    }

    /** channel 处于活动状态, 提示 xx上线. **/
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println(ctx.channel().remoteAddress() + " 上线了~");
    }

    /**
     * 表示channel 处于不活动状态, 提示 xx离线了.
     * 从打印的现象来看, 此方法优先于 {@link #handlerRemoved(ChannelHandlerContext)}
     * @param ctx
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println(ctx.channel().remoteAddress() + " 离线了~");
    }

    /**
     * 读取数据
     * 1. 获取到当前channel
     * 2. 遍历channelGroup, 根据不同的情况，回送不同的消息.
     *     不是当前的channel, 转发消息.
     *     是当前channel发的, 则回显
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        Channel channel = ctx.channel();
        channelGroup.forEach(ch -> {
            if(channel != ch) {
                ch.writeAndFlush("[客户]" + channel.remoteAddress() + " 发送了消息" + msg + "\n");
            }else {
                ch.writeAndFlush("[自己]发送了消息" + msg + "\n");
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
       /** 关闭通道. **/
        ctx.close();
    }
}
