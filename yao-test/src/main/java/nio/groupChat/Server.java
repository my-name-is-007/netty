
package nio.groupChat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;

public class Server {

    private Selector selector;
    private ServerSocketChannel serverChannel;

    public Server(int port) throws IOException {
        serverChannel =  ServerSocketChannel.open();
        serverChannel.socket().bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void listen() throws IOException {
        System.out.println("服务器开始监听, 线程 = " + Thread.currentThread().getName());
        while (true) {
            if(selector.select() <= 0){
                System.out.println("等待....");
            }
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                if(key.isAcceptable()) {
                    actionOnReadable(key);
                }
                if(key.isReadable()) {
                    readData(key);
                }
                //当前的key 删除, 防止重复处理.
                iterator.remove();
            }
        }
    }

    private void actionOnReadable(SelectionKey k) throws IOException {
        SocketChannel sc = serverChannel.accept();
        sc.configureBlocking(false);
        sc.register(selector, SelectionKey.OP_READ);
        System.out.println(sc.getRemoteAddress() + " 上线 ");
    }

    //读取客户端消息
    private void readData(SelectionKey key) {
        SocketChannel channel = null;
        try {
            channel = (SocketChannel) key.channel();
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            //根据count的值做处理
            if(channel.read(buffer) > 0) {
                //把缓存区的数据转成字符串
                String msg = new String(buffer.array());
                //输出该消息.
                System.out.println("server收到来自客户端 " + channel.getRemoteAddress() + " 的消息: " + msg);
                //向其它的客户端转发消息(去掉自己)
                sendInfoToOtherClients(msg, channel);
            }

        }catch (IOException e) {
            try {
                System.out.println(channel.getRemoteAddress() + " 离线了..");
                //取消注册
                key.cancel();
                //关闭通道
                channel.close();
            }catch (IOException e2) {
                e2.printStackTrace();
            }
        }
    }

    //转发消息给其它客户(通道)
    private void sendInfoToOtherClients(String msg, SocketChannel self ) throws  IOException{

        System.out.println("服务端线程 " + Thread.currentThread().getName()+ " 转发数据给客户端");
        for(SelectionKey key: selector.keys()) {
            //通过 key  取出对应的 SocketChannel
            Channel targetChannel = key.channel();
            if (targetChannel == self) {
                continue ;
            }
            //排除自己
            if(targetChannel instanceof SocketChannel) {
                SocketChannel dstChannel = (SocketChannel)targetChannel;
                dstChannel.write(ByteBuffer.wrap(msg.getBytes()));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Server groupChatServer = new Server(80);
        groupChatServer.listen();
    }
}

