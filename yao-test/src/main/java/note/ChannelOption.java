/*
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */

package note;

/**
 * @author wangzongyao on 2020/9/2
 */
public class ChannelOption {
    /**
     * ChannelOption.SO_BACKLOG: <br/ >
     * 对应 TCP/IP 协议 listen 函数中的 backlog 参数，用来初始化服务器可连接队列大小。服
     * 务端处理客户端连接请求是顺序处理的，所以同一时间只能处理一个客户端连接。多个客户
     * 端来的时候，服务端将不能处理的客户端连接请求放在队列中等待处理，backlog 参数指定
     * 了队列的大小。
     *
     * ChannelOption.SO_KEEPALIVE
     * 一直保持连接活动状态
     */
}
