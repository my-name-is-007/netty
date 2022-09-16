/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package note;

/**
 * 初始化过程.
 * @author wangzongyao on 2020/9/9
 */
public abstract class Init {

    /**
     * bind() 方法过程: ↓
     * <ul>
     *     <li>initAndRegister()</li>
     *     <li>initAndRegister()</li>
     *     <li>initAndRegister()</li>
     * </ul>
     * initAndRegister()
     * 	    // 新建一个Channel
     *         channel = channelFactory.newChannel();
     * 		    this.parent = parent;
     * 			id = newId();
     * 			unsafe = newUnsafe();
     * 			pipeline = newChannelPipeline();
     * 			this.ch = ch;
     * 			this.readInterestOp = readInterestOp;
     * 			ch.configureBlocking(false);
     * 			config = new NioServerSocketChannelConfig(this, javaChannel().socket());
     *         //初始化Channel
     *         init(channel);
     * 		    //1. 设置 Channel 的 option 和 attr
     * 			//2. 向 Channel关联的Pipeline中添加 Handler: ChannelInitializer.
     * 			    //本质是入栈适配器, 一般是程序员自己在childHandler()方法中加入自己的初始化器, 但是bossGroup的初始化器, 是在这里, 由程序自己做的.
     * 				//当 NioServerSocketChannel 在 EventLoop 注册成功时, 添加的这个 ChannelInitializer 的init方法就会被调用
     * 				//在这个init中主要做了两件事情: 添加你为bossGroup设置的Handler, 通过执行器
     * 		//向EventLoopGroup中注册一个channel
     *         ChannelFuture regFuture = config().group().register(channel);
     * 	doBind0();
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     *
     */

}
