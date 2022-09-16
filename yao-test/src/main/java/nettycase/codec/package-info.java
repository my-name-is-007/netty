/**
 * 本包 使用自定义的编解码器, 讲解 出站入站 机制.
 * 并分析其源码.
 *
 * 注意: ↓
 * 1. 编、解码器, 接收的消息类型必须与自己能处理的消息类型一致, 否则 该 Handler 不会被执行.
 * 2. 在解码器 进行数据解码时, 需要判断 缓存区(ByteBuf)的数据是否足够, 否则接收到的结果会期望结果可能不一致
 * Copyright (c) 2017-2020 jdjr All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@jd.com
 */
package nettycase.codec;