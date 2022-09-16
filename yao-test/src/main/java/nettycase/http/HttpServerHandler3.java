package nettycase.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObject;

/**
 * SimpleChannelInboundHandler<I> extends ChannelInboundHandlerAdapter.
 * 这里的Handler会创建多次, 还是会创建一次, 和41行代码要不要返回有关, 我新开一个浏览器发请求也有关.
 * 具体的可以去看源码, 只看这些表象去总结, 没一点儿意义, 更没意思.
 */
public class HttpServerHandler3 extends SimpleChannelInboundHandler<HttpObject> {

    static int createdTimes = 0;

    public HttpServerHandler3() {
        System.out.println("被创建" + (++createdTimes));
    }

    /**
     * 读取客户端数据.
     *
     * @param msg HttpObject 客户端和服务器端相互通讯的数据被封装成 HttpObject
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        System.out.println("处理器 ::: Handler3, 即将调用下一个");
        ctx.fireChannelRead(msg);
        System.out.println("处理器 ::: Handler3结束");

    }

}
