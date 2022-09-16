package nio.easytest;

/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * 这个程序是配合着 {@link NioClient}来运行的, 两者会形成一个死循环, 不停的给对方发数据.
 * 对于同一个连接, 肯定是先发生 连接事件、然后是可读事件、最后是可写事件.
 * 注意: channel只要cancel了, 就不能在 register() 了.
 * channel.register(selector, SelectionKey.OP_WRITE); 此处无论是 Server端还是Client端 Channel,
 * 都可以连续调用 register 方法, 如果selector相同的话, 最后一次的调用会覆盖前面关心的事件(attachment会不会覆盖不清楚, 但猜着会).
 *
 * @author wangzongyao on 2020/8/11
 */
public class NioServer {

    public static Selector selector = null;

    public static StringBuilder sb = new StringBuilder(10000);

    /**
     * 1. 创建 服务端 Channel: ServerSocketChannel
     * 2. 设置 非阻塞
     * 3. 绑定 指定端口
     * 4. 注册 至指定轮训器, 设置感兴趣事件
     */
    static ServerSocketChannel registerChannel(Selector registerdSelector, int port) throws IOException {
        //Selector.open();
        //SelectorProvider.provider().openServerSocketChannel();
        //ServerSocketChannel.open().socket();

        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.register(registerdSelector, SelectionKey.OP_ACCEPT, serverSocketChannel);
        System.out.println("serverChannel ::: " + serverSocketChannel);
        return serverSocketChannel;
    }

    /**
     * 创建选择器
     * Channel
     *     创建Channel
     *     设置非阻塞
     *     设置监听端口
     *     注册至选择器,
     */
    public static void main(String[] args) throws IOException {
        /*new Thread(() -> {
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            selector.wakeup();
        }).start();*/

        selector = Selector.open();
        registerChannel(selector, 8088);
        System.out.println("服务器成功启动,并且监听8088端口,等待请求...");
        //循环selector关注的事件.
        while(true){
            //获取已发生事件的个数. selectNow是非阻塞的,所以socket.write()后不需要唤起selector,区别于select()是阻塞的
            int selectNum = selector.select();
            if(selectNum <= 0){
                continue ;
            }
            //已选择键集
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                System.out.println("当前 Key 为 ::: " + key + ", 其 附加对象为 ::: " + key.attachment());
                //对事件进行处理.
                if(key.isAcceptable()){
                    handleAccept(key);
                }else if(key.isReadable()){
                    handleOnReadable(key);
                }else if(key.isWritable()){
                    handleOnWriteable(key);
                }else{
                    System.out.println("未关注事件......");
                }
                //从当前已选择键集中移除: 否则会同一个事件会重复处理.
                iterator.remove();
            }
        }
    }

    /**
     * 可接受事件(客户端访问服务器的时候触发)
     * 连接来了, 进行处理: 生成一个客户端
     * 注意: accept()方, 在传统的IO流中, 是阻塞的, 但是这里并不是.
     * 因为这里是连接已经发生了, 所以才走到了这个方法, 所以 accept()方法在这里是一定能拿到一个连接的.
     */
    private static void handleAccept(SelectionKey key) throws IOException{
        System.out.println("handleAccept 方法......\n\n\n");
        ServerSocketChannel ssc = (ServerSocketChannel)key.channel();
        SocketChannel sc = ssc.accept();
        sc.configureBlocking(false);
        /**
         * Socketchannel 通道注册到 Selector中, 监听读事件,
         * 此处可以通过第三个参数为 Channel 关联一个 Buffer.
         */
        sc.register(selector, SelectionKey.OP_READ);
    }

    /**
     * 读事件, 读取客户端数据(服务器开始接收客户端的套接字通道数据)
     * 根据 SelectKey 获取 客户端Channel, 从中读取数据.
     */
    private static void handleOnReadable(SelectionKey key) throws IOException {
        System.out.println("Readable 方法...");
        //这一步很关键,这行表示取消channel和selector之间的关联,直接从选择键中移除这个通道,
        // 之前的迭代器remove表示从有效键中移除,注意区别
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        channel.read(buffer);
        System.out.println("客户端访问信息: " + System.getProperty("line.separator") + new String(buffer.array()) + "\n\n\n");
        /** 这里进行业务操作........ **/
        //关注写事件.
        channel.register(selector, SelectionKey.OP_WRITE);
    }

    /**
     * 写事件(服务器响应客户端).
     */
    private static void handleOnWriteable(SelectionKey key) throws IOException {
        System.out.println("handleOnWriteable 方法...");
        //key.cancel();
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer writeBuffer = filledByteBuffer(2048*10);
        while(writeBuffer.hasRemaining()){
            channel.write(writeBuffer);
        }
        //关闭通道,成功完成一次请求响应
        //channel.close();
        System.out.println("成功响应客户端.......\n\n\n");
        channel.register(selector, SelectionKey.OP_READ);
    }

    private static ByteBuffer filledByteBuffer(int capacity){
        ByteBuffer writeBuffer = ByteBuffer.allocate(capacity);
        writeBuffer.put(("HTTP/1.1 200 OK"+System.getProperty("line.separator")).getBytes());
        writeBuffer.put(("Server:NioServerTest/1.0"+System.getProperty("line.separator")).getBytes());
        writeBuffer.put(("Content-Type:text/html;charset=UTF-8"+System.getProperty("line.separator")).getBytes());
        writeBuffer.put(("Date:"+ new Date().toLocaleString()+System.getProperty("line.separator")+System.getProperty("line.separator")).getBytes());
        writeBuffer.put(("I'm Your wild Father" + System.currentTimeMillis()).getBytes());
        writeBuffer.flip();
        return writeBuffer;
    }

}
