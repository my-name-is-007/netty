/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer;

import io.netty.util.ByteProcessor;
import io.netty.util.ReferenceCounted;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 一个可以随机和顺序访问的零个或多个字节（八位字节）序列。
 * 这个接口为一个或多个原始字节数组（{@code byte[]}）和 {@linkplain ByteBuffer NIO缓冲区} 提供了抽象视图。
 *
 * <h3>缓冲区的创建</h3>
 *
 * 建议使用 {@link Unpooled} 中的辅助方法来创建新的缓冲区，而不是调用单个实现的构造函数。
 *
 * <h3>随机访问索引</h3>
 *
 * 就像普通的原始字节数组一样，{@link ByteBuf} 使用
 * <a href="https://en.wikipedia.org/wiki/Zero-based_numbering">基于零的索引</a>。
 * 这意味着第一个字节的索引总是 {@code 0}，最后一个字节的索引总是 {@link #capacity() capacity - 1}。
 * 例如，要遍历缓冲区的所有字节，无论其内部实现如何，您都可以执行以下操作：
 *
 * <pre>
 * {@link ByteBuf} buffer = ...;
 * for (int i = 0; i &lt; buffer.capacity(); i ++) {
 *     byte b = buffer.getByte(i);
 *     System.out.println((char) b);
 * }
 * </pre>
 *
 * <h3>顺序访问索引</h3>
 *
 * {@link ByteBuf} 提供两个指针变量来支持顺序读写操作 - 用于读操作的 {@link #readerIndex() readerIndex}
 * 和用于写操作的 {@link #writerIndex() writerIndex}。下图显示了缓冲区如何被两个指针分割成三个区域：
 *
 * <pre>
 *      +-------------------+------------------+------------------+
 *      | 可丢弃字节         |  可读字节         |  可写字节         |
 *      |                   |     (内容)       |                  |
 *      +-------------------+------------------+------------------+
 *      |                   |                  |                  |
 *      0      <=      readerIndex   <=   writerIndex    <=    capacity
 * </pre>
 *
 * <h4>可读字节（实际内容）</h4>
 *
 * 这个段是存储实际数据的地方。任何名称以 {@code read} 或 {@code skip} 开头的操作都会在当前
 * {@link #readerIndex() readerIndex} 处获取或跳过数据，并将其增加读取的字节数。
 * 如果读操作的参数也是一个 {@link ByteBuf} 且没有指定目标索引，指定缓冲区的
 * {@link #writerIndex() writerIndex} 也会一起增加。
 * <p>
 * 如果剩余内容不足，会抛出 {@link IndexOutOfBoundsException}。
 * 新分配、包装或复制的缓冲区的 {@link #readerIndex() readerIndex} 默认值是 {@code 0}。
 *
 * <pre>
 * // 遍历缓冲区的可读字节。
 * {@link ByteBuf} buffer = ...;
 * while (buffer.isReadable()) {
 *     System.out.println(buffer.readByte());
 * }
 * </pre>
 *
 * <h4>可写字节</h4>
 *
 * 这个段是需要填充的未定义空间。任何名称以 {@code write} 开头的操作都会在当前
 * {@link #writerIndex() writerIndex} 处写入数据，并将其增加写入的字节数。
 * 如果写操作的参数也是一个 {@link ByteBuf}，且没有指定源索引，指定缓冲区的
 * {@link #readerIndex() readerIndex} 也会一起增加。
 * <p>
 * 如果剩余可写字节不足，会抛出 {@link IndexOutOfBoundsException}。
 * 新分配缓冲区的 {@link #writerIndex() writerIndex} 默认值是 {@code 0}。
 * 包装或复制缓冲区的 {@link #writerIndex() writerIndex} 默认值是缓冲区的 {@link #capacity() capacity}。
 *
 * <pre>
 * // 用随机整数填充缓冲区的可写字节。
 * {@link ByteBuf} buffer = ...;
 * while (buffer.maxWritableBytes() >= 4) {
 *     buffer.writeInt(random.nextInt());
 * }
 * </pre>
 *
 * <h4>可丢弃字节</h4>
 *
 * 这个段包含已经被读操作读取的字节。最初，这个段的大小是 {@code 0}，但随着读操作的执行，
 * 其大小会增加到 {@link #writerIndex() writerIndex}。可以通过调用 {@link #discardReadBytes()}
 * 来丢弃已读字节以回收未使用的区域，如下图所示：
 *
 * <pre>
 *  调用 discardReadBytes() 之前
 *
 *      +-------------------+------------------+------------------+
 *      | 可丢弃字节         |  可读字节         |  可写字节         |
 *      +-------------------+------------------+------------------+
 *      |                   |                  |                  |
 *      0      <=      readerIndex   <=   writerIndex    <=    capacity
 *
 *
 *  调用 discardReadBytes() 之后
 *
 *      +------------------+--------------------------------------+
 *      |  可读字节         |    可写字节 (获得更多空间)             |
 *      +------------------+--------------------------------------+
 *      |                  |                                      |
 * readerIndex (0) <= writerIndex (减少)        <=        capacity
 * </pre>
 *
 * 请注意，调用 {@link #discardReadBytes()} 后，可写字节的内容没有保证。
 * 在大多数情况下，可写字节不会移动，甚至可能根据底层缓冲区实现填充完全不同的数据。
 *
 * <h4>清除缓冲区索引</h4>
 *
 * 您可以通过调用 {@link #clear()} 将 {@link #readerIndex() readerIndex} 和
 * {@link #writerIndex() writerIndex} 都设置为 {@code 0}。
 * 它不会清除缓冲区内容（例如用 {@code 0} 填充），只是清除两个指针。
 * 请注意，此操作的语义与 {@link ByteBuffer#clear()} 不同。
 *
 * <pre>
 *  调用 clear() 之前
 *
 *      +-------------------+------------------+------------------+
 *      | 可丢弃字节         |  可读字节         |  可写字节         |
 *      +-------------------+------------------+------------------+
 *      |                   |                  |                  |
 *      0      <=      readerIndex   <=   writerIndex    <=    capacity
 *
 *
 *  调用 clear() 之后
 *
 *      +---------------------------------------------------------+
 *      |             可写字节 (获得更多空间)                       |
 *      +---------------------------------------------------------+
 *      |                                                         |
 *      0 = readerIndex = writerIndex            <=            capacity
 * </pre>
 *
 * <h3>搜索操作</h3>
 *
 * 对于简单的单字节搜索，使用 {@link #indexOf(int, int, byte)} 和 {@link #bytesBefore(int, int, byte)}。
 * 当您处理以 {@code NUL} 结尾的字符串时，{@link #bytesBefore(byte)} 特别有用。
 * 对于复杂的搜索，使用 {@link #forEachByte(int, int, ByteProcessor)} 和 {@link ByteProcessor} 实现。
 *
 * <h3>标记和重置</h3>
 *
 * 每个缓冲区中有两个标记索引。一个用于存储 {@link #readerIndex() readerIndex}，
 * 另一个用于存储 {@link #writerIndex() writerIndex}。您总是可以通过调用重置方法来重新定位两个索引之一。
 * 它的工作方式类似于 {@link InputStream} 中的标记和重置方法，只是没有 {@code readlimit}。
 *
 * <h3>派生缓冲区</h3>
 *
 * 您可以通过调用以下方法之一来创建现有缓冲区的视图：
 * <ul>
 *   <li>{@link #duplicate()}</li>
 *   <li>{@link #slice()}</li>
 *   <li>{@link #slice(int, int)}</li>
 *   <li>{@link #readSlice(int)}</li>
 *   <li>{@link #retainedDuplicate()}</li>
 *   <li>{@link #retainedSlice()}</li>
 *   <li>{@link #retainedSlice(int, int)}</li>
 *   <li>{@link #readRetainedSlice(int)}</li>
 * </ul>
 * 派生缓冲区将具有独立的 {@link #readerIndex() readerIndex}、{@link #writerIndex() writerIndex}
 * 和标记索引，同时它共享其他内部数据表示，就像 NIO 缓冲区一样。
 * <p>
 * 如果需要现有缓冲区的完全新副本，请调用 {@link #copy()} 方法。
 *
 * <h4>非保留和保留的派生缓冲区</h4>
 *
 * 请注意，{@link #duplicate()}、{@link #slice()}、{@link #slice(int, int)} 和 {@link #readSlice(int)}
 * 不会在返回的派生缓冲区上调用 {@link #retain()}，因此其引用计数不会增加。
 * 如果您需要创建引用计数增加的派生缓冲区，请考虑使用 {@link #retainedDuplicate()}、
 * {@link #retainedSlice()}、{@link #retainedSlice(int, int)} 和 {@link #readRetainedSlice(int)}，
 * 它们可能返回产生较少垃圾的缓冲区实现。
 *
 * <h3>转换为现有 JDK 类型</h3>
 *
 * <h4>字节数组</h4>
 *
 * 如果 {@link ByteBuf} 由字节数组（即 {@code byte[]}）支持，您可以通过 {@link #array()} 方法直接访问它。
 * 要确定缓冲区是否由字节数组支持，应使用 {@link #hasArray()}。
 *
 * <h4>NIO 缓冲区</h4>
 *
 * 如果 {@link ByteBuf} 可以转换为共享其内容的 NIO {@link ByteBuffer}（即视图缓冲区），
 * 您可以通过 {@link #nioBuffer()} 方法获取它。要确定缓冲区是否可以转换为 NIO 缓冲区，
 * 请使用 {@link #nioBufferCount()}。
 *
 * <h4>字符串</h4>
 *
 * 各种 {@link #toString(Charset)} 方法将 {@link ByteBuf} 转换为 {@link String}。
 * 请注意，{@link #toString()} 不是转换方法。
 *
 * <h4>I/O 流</h4>
 *
 * ReferenceCounted:
 *     一般的堆内的内存可以由GC来回收,堆外的话，就要自己手动来释放，不然会造成内存泄露的
 *
 * 请参考 {@link ByteBufInputStream} 和 {@link ByteBufOutputStream}。
 */
public abstract class ByteBuf implements ReferenceCounted, Comparable<ByteBuf> {

    /**
     * 返回此缓冲区可以包含的字节数（八位字节）。
     *
     * @return 缓冲区的容量
     */
    public abstract int capacity();

    /**
     * 调整此缓冲区的容量。如果 {@code newCapacity} 小于当前容量，此缓冲区的内容将被截断。
     * 如果 {@code newCapacity} 大于当前容量，缓冲区将附加长度为 {@code (newCapacity - currentCapacity)} 的未指定数据。
     *
     * @param newCapacity 新的容量大小
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IllegalArgumentException 如果指定的 {@code newCapacity} 大于 {@link #maxCapacity()}
     */
    public abstract ByteBuf capacity(int newCapacity);

    /**
     * 返回此缓冲区允许的最大容量。此值为 {@link #capacity()} 提供上限。
     *
     * @return 最大允许容量
     */
    public abstract int maxCapacity();

    /**
     * 返回创建此缓冲区的 {@link ByteBufAllocator}。
     *
     * @return 缓冲区分配器
     */
    public abstract ByteBufAllocator alloc();

    /**
     * 返回此缓冲区的 <a href="https://en.wikipedia.org/wiki/Endianness">字节序</a>。
     *
     * @return 字节序
     * @deprecated 使用小端访问器，例如 {@code getShortLE}、{@code getIntLE}，
     * 而不是创建具有交换 {@code endianness} 的缓冲区。
     */
    @Deprecated
    public abstract ByteOrder order();

    /**
     * 返回具有指定 {@code endianness} 的缓冲区，该缓冲区共享此缓冲区的整个区域、索引和标记。
     * 修改返回缓冲区或此缓冲区的内容、索引或标记会影响彼此的内容、索引和标记。
     * 如果指定的 {@code endianness} 与此缓冲区的字节序相同，此方法可以返回 {@code this}。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param endianness 指定的字节序
     * @return 具有指定字节序的缓冲区
     * @deprecated 使用小端访问器，例如 {@code getShortLE}、{@code getIntLE}，
     * 而不是创建具有交换 {@code endianness} 的缓冲区。
     */
    @Deprecated
    public abstract ByteBuf order(ByteOrder endianness);

    /**
     * 如果此缓冲区是另一个缓冲区的包装器，则返回底层缓冲区实例。
     *
     * @return 如果此缓冲区不是包装器，则返回 {@code null}
     */
    public abstract ByteBuf unwrap();

    /**
     * 当且仅当此缓冲区由 NIO 直接缓冲区支持时返回 {@code true}。
     *
     * @return 如果是直接缓冲区则返回 true
     */
    public abstract boolean isDirect();

    /**
     * 当且仅当此缓冲区是只读的时返回 {@code true}。
     *
     * @return 如果是只读缓冲区则返回 true
     */
    public abstract boolean isReadOnly();

    /**
     * 返回此缓冲区的只读版本。
     *
     * @return 只读缓冲区
     */
    public abstract ByteBuf asReadOnly();

    /**
     * 返回此缓冲区的 {@code readerIndex}。
     *
     * @return 读索引
     */
    public abstract int readerIndex();

    /**
     * 设置此缓冲区的 {@code readerIndex}。
     *
     * @param readerIndex 新的读索引
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code readerIndex} 小于 {@code 0} 或大于 {@code this.writerIndex}
     */
    public abstract ByteBuf readerIndex(int readerIndex);

    /**
     * 返回此缓冲区的 {@code writerIndex}。
     *
     * @return 写索引
     */
    public abstract int writerIndex();

    /**
     * 设置此缓冲区的 {@code writerIndex}。
     *
     * @param writerIndex 新的写索引
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code writerIndex} 小于 {@code this.readerIndex} 或大于 {@code this.capacity}
     */
    public abstract ByteBuf writerIndex(int writerIndex);

    /**
     * 一次性设置此缓冲区的 {@code readerIndex} 和 {@code writerIndex}。
     * 当您必须担心 {@link #readerIndex(int)} 和 {@link #writerIndex(int)} 方法的调用顺序时，此方法很有用。
     * 例如，以下代码将失败：
     *
     * <pre>
     * // 创建一个 readerIndex、writerIndex 和 capacity 分别为 0、0 和 8 的缓冲区。
     * {@link ByteBuf} buf = {@link Unpooled}.buffer(8);
     *
     * // 抛出 IndexOutOfBoundsException，因为指定的 readerIndex (2) 不能大于当前的 writerIndex (0)。
     * buf.readerIndex(2);
     * buf.writerIndex(4);
     * </pre>
     *
     * 以下代码也会失败：
     *
     * <pre>
     * // 创建一个 readerIndex、writerIndex 和 capacity 分别为 0、8 和 8 的缓冲区。
     * {@link ByteBuf} buf = {@link Unpooled}.wrappedBuffer(new byte[8]);
     *
     * // readerIndex 变为 8。
     * buf.readLong();
     *
     * // 抛出 IndexOutOfBoundsException，因为指定的 writerIndex (4) 不能小于当前的 readerIndex (8)。
     * buf.writerIndex(4);
     * buf.readerIndex(2);
     * </pre>
     *
     * 相比之下，此方法保证只要指定的索引满足基本约束，无论缓冲区的当前索引值如何，
     * 它永远不会抛出 {@link IndexOutOfBoundsException}：
     *
     * <pre>
     * // 无论缓冲区的当前状态如何，只要缓冲区的容量不小于 4，以下调用总是成功的。
     * buf.setIndex(2, 4);
     * </pre>
     *
     * @param readerIndex 新的读索引
     * @param writerIndex 新的写索引
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code readerIndex} 小于 0，
     *         如果指定的 {@code writerIndex} 小于指定的 {@code readerIndex}，
     *         或如果指定的 {@code writerIndex} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setIndex(int readerIndex, int writerIndex);

    /**
     * 返回可读字节数，等于 {@code (this.writerIndex - this.readerIndex)}。
     *
     * @return 可读字节数
     */
    public abstract int readableBytes();

    /**
     * 返回可写字节数，等于 {@code (this.capacity - this.writerIndex)}。
     *
     * @return 可写字节数
     */
    public abstract int writableBytes();

    /**
     * 返回最大可能的可写字节数，等于 {@code (this.maxCapacity - this.writerIndex)}。
     *
     * @return 最大可写字节数
     */
    public abstract int maxWritableBytes();

    /**
     * 返回在不涉及内部重新分配或数据复制的情况下可以写入的最大字节数。
     * 返回值将 >= {@link #writableBytes()} 且 <= {@link #maxWritableBytes()}。
     *
     * @return 最大快速可写字节数
     */
    public int maxFastWritableBytes() {
        return writableBytes();
    }

    /**
     * 当且仅当 {@code (this.writerIndex - this.readerIndex)} 大于 {@code 0} 时返回 {@code true}。
     *
     * @return 如果缓冲区可读则返回 true
     */
    public abstract boolean isReadable();

    /**
     * 当且仅当此缓冲区包含等于或多于指定数量的元素时返回 {@code true}。
     *
     * @param size 要检查的字节数
     * @return 如果缓冲区包含足够的可读字节则返回 true
     */
    public abstract boolean isReadable(int size);

    /**
     * 当且仅当 {@code (this.capacity - this.writerIndex)} 大于 {@code 0} 时返回 {@code true}。
     *
     * @return 如果缓冲区可写则返回 true
     */
    public abstract boolean isWritable();

    /**
     * 当且仅当此缓冲区有足够的空间允许写入指定数量的元素时返回 {@code true}。
     *
     * @param size 要检查的字节数
     * @return 如果缓冲区有足够的可写空间则返回 true
     */
    public abstract boolean isWritable(int size);

    /**
     * 将此缓冲区的 {@code readerIndex} 和 {@code writerIndex} 设置为 {@code 0}。
     * 此方法与 {@link #setIndex(int, int) setIndex(0, 0)} 相同。
     * <p>
     * 请注意，此方法的行为与 NIO 缓冲区不同，NIO 缓冲区将 {@code limit} 设置为缓冲区的 {@code capacity}。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf clear();

    /**
     * 标记此缓冲区中的当前 {@code readerIndex}。您可以通过调用 {@link #resetReaderIndex()}
     * 将当前 {@code readerIndex} 重新定位到标记的 {@code readerIndex}。
     * 标记的 {@code readerIndex} 的初始值是 {@code 0}。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf markReaderIndex();

    /**
     * 将当前 {@code readerIndex} 重新定位到此缓冲区中标记的 {@code readerIndex}。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果当前 {@code writerIndex} 小于标记的 {@code readerIndex}
     */
    public abstract ByteBuf resetReaderIndex();

    /**
     * 标记此缓冲区中的当前 {@code writerIndex}。您可以通过调用 {@link #resetWriterIndex()}
     * 将当前 {@code writerIndex} 重新定位到标记的 {@code writerIndex}。
     * 标记的 {@code writerIndex} 的初始值是 {@code 0}。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf markWriterIndex();

    /**
     * 将当前 {@code writerIndex} 重新定位到此缓冲区中标记的 {@code writerIndex}。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果当前 {@code readerIndex} 大于标记的 {@code writerIndex}
     */
    public abstract ByteBuf resetWriterIndex();

    /**
     * 丢弃第 0 个索引和 {@code readerIndex} 之间的字节。
     * 它将 {@code readerIndex} 和 {@code writerIndex} 之间的字节移动到第 0 个索引，
     * 并分别将 {@code readerIndex} 和 {@code writerIndex} 设置为 {@code 0} 和 {@code oldWriterIndex - oldReaderIndex}。
     * <p>
     * 请参考类文档以获得更详细的解释。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf discardReadBytes();

    /**
     * 类似于 {@link ByteBuf#discardReadBytes()}，但此方法可能根据其内部实现丢弃一些、全部或不丢弃已读字节，
     * 以减少总体内存带宽消耗，代价是可能增加额外的内存消耗。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf discardSomeReadBytes();

    /**
     * 扩展缓冲区 {@link #capacity()} 以确保 {@linkplain #writableBytes() 可写字节} 的数量
     * 等于或大于指定值。如果此缓冲区中有足够的可写字节，此方法返回时没有副作用。
     *
     * @param minWritableBytes 期望的最小可写字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@link #writerIndex()} + {@code minWritableBytes} > {@link #maxCapacity()}。
     * @see #capacity(int)
     */
    public abstract ByteBuf ensureWritable(int minWritableBytes);

    /**
     * 扩展缓冲区 {@link #capacity()} 以确保 {@linkplain #writableBytes() 可写字节} 的数量
     * 等于或大于指定值。与 {@link #ensureWritable(int)} 不同，此方法返回状态码。
     *
     * @param minWritableBytes 期望的最小可写字节数
     * @param force 当 {@link #writerIndex()} + {@code minWritableBytes} > {@link #maxCapacity()} 时：
     *        <ul>
     *        <li>{@code true} - 缓冲区的容量扩展到 {@link #maxCapacity()}</li>
     *        <li>{@code false} - 缓冲区的容量保持不变</li>
     *        </ul>
     * @return {@code 0} 如果缓冲区有足够的可写字节，且其容量未改变。
     *         {@code 1} 如果缓冲区没有足够的字节，且其容量未改变。
     *         {@code 2} 如果缓冲区有足够的可写字节，且其容量已增加。
     *         {@code 3} 如果缓冲区没有足够的字节，但其容量已增加到最大值。
     */
    public abstract int ensureWritable(int minWritableBytes, boolean force);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个布尔值。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的布尔值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 1} 大于 {@code this.capacity}
     */
    public abstract boolean getBoolean(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个字节。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的字节值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 1} 大于 {@code this.capacity}
     */
    public abstract byte getByte(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个无符号字节。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号字节值（作为 short 返回）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 1} 大于 {@code this.capacity}
     */
    public abstract short getUnsignedByte(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 16 位短整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的短整数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract short getShort(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 16 位短整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的短整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract short getShortLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个无符号 16 位短整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号短整数值（作为 int 返回）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract int getUnsignedShort(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个无符号 16 位短整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号短整数值（小端字节序，作为 int 返回）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract int getUnsignedShortLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 24 位中等整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 24 位整数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract int getMedium(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 24 位中等整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 24 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract int getMediumLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个无符号 24 位中等整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号 24 位整数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract int getUnsignedMedium(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个无符号 24 位中等整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号 24 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract int getUnsignedMediumLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 32 位整数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract int getInt(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 32 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract int getIntLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个无符号 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号 32 位整数值（作为 long 返回）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract long getUnsignedInt(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个无符号 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的无符号 32 位整数值（小端字节序，作为 long 返回）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract long getUnsignedIntLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 64 位长整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 64 位长整数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract long getLong(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 64 位长整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的 64 位长整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract long getLongLE(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 2 字节 UTF-16 字符。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的字符值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract char getChar(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 32 位浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的浮点数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract float getFloat(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 32 位浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的浮点数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 4} 大于 {@code this.capacity}
     */
    public float getFloatLE(int index) {
        return Float.intBitsToFloat(getIntLE(index));
    }

    /**
     * 在此缓冲区的指定绝对 {@code index} 处获取一个 64 位双精度浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的双精度浮点数值
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract double getDouble(int index);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序获取一个 64 位双精度浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要读取的索引位置
     * @return 指定位置的双精度浮点数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或 {@code index + 8} 大于 {@code this.capacity}
     */
    public double getDoubleLE(int index) {
        return Double.longBitsToDouble(getLongLE(index));
    }

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标，直到目标变为不可写。
     * 此方法基本上与 {@link #getBytes(int, ByteBuf, int, int)} 相同，
     * 除了此方法将目标的 {@code writerIndex} 增加传输的字节数，
     * 而 {@link #getBytes(int, ByteBuf, int, int)} 不会。
     * 此方法不会修改源缓冲区（即 {@code this}）的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标缓冲区
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + dst.writableBytes} 大于 {@code this.capacity}
     */
    public abstract ByteBuf getBytes(int index, ByteBuf dst);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标。
     * 此方法基本上与 {@link #getBytes(int, ByteBuf, int, int)} 相同，
     * 除了此方法将目标的 {@code writerIndex} 增加传输的字节数，
     * 而 {@link #getBytes(int, ByteBuf, int, int)} 不会。
     * 此方法不会修改源缓冲区（即 {@code this}）的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标缓冲区
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code length} 大于 {@code dst.writableBytes}
     */
    public abstract ByteBuf getBytes(int index, ByteBuf dst, int length);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标。
     * 此方法不会修改源（即 {@code this}）和目标的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标缓冲区
     * @param dstIndex 目标的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果指定的 {@code dstIndex} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code dstIndex + length} 大于 {@code dst.capacity}
     */
    public abstract ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标字节数组
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + dst.length} 大于 {@code this.capacity}
     */
    public abstract ByteBuf getBytes(int index, byte[] dst);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标字节数组
     * @param dstIndex 目标的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果指定的 {@code dstIndex} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code dstIndex + length} 大于 {@code dst.length}
     */
    public abstract ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的目标，直到目标的位置达到其限制。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}，
     * 而目标的 {@code position} 将增加。
     *
     * @param index 开始传输的索引位置
     * @param dst 目标 NIO ByteBuffer
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + dst.remaining()} 大于 {@code this.capacity}
     */
    public abstract ByteBuf getBytes(int index, ByteBuffer dst);

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的流。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param out 目标输出流
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的流在 I/O 期间抛出异常
     */
    public abstract ByteBuf getBytes(int index, OutputStream out, int length) throws IOException;

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定的通道。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param out 目标通道
     * @param length 要传输的最大字节数
     * @return 实际写出到指定通道的字节数
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int getBytes(int index, GatheringByteChannel out, int length) throws IOException;

    /**
     * 从指定的绝对 {@code index} 开始将此缓冲区的数据传输到指定通道的给定文件位置。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 此方法不会修改通道的位置。
     *
     * @param index 开始传输的索引位置
     * @param out 目标文件通道
     * @param position 开始传输的文件位置
     * @param length 要传输的最大字节数
     * @return 实际写出到指定通道的字节数
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int getBytes(int index, FileChannel out, long position, int length) throws IOException;

    /**
     * 在给定索引处获取给定长度的 {@link CharSequence}。
     *
     * @param index 开始读取的索引位置
     * @param length 要读取的长度
     * @param charset 应该使用的字符集
     * @return 字符序列
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract CharSequence getCharSequence(int index, int length, Charset charset);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的布尔值。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的布尔值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 1} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setBoolean(int index, boolean value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的字节。
     * 指定值的 24 个高位被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的字节值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 1} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setByte(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 16 位短整数。
     * 指定值的 16 个高位被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的短整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setShort(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 16 位短整数。
     * 指定值的 16 个高位被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的短整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setShortLE(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 24 位中等整数。
     * 请注意，指定值中最重要的字节被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的中等整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setMedium(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 24 位中等整数。
     * 请注意，指定值中最重要的字节被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的中等整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 3} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setMediumLE(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setInt(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 32 位整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setIntLE(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 64 位长整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的长整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setLong(int index, long value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 64 位长整数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的长整数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setLongLE(int index, long value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 2 字节 UTF-16 字符。
     * 指定值的 16 个高位被忽略。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的字符值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 2} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setChar(int index, int value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 32 位浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 4} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setFloat(int index, float value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 32 位浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 4} 大于 {@code this.capacity}
     */
    public ByteBuf setFloatLE(int index, float value) {
        return setIntLE(index, Float.floatToRawIntBits(value));
    }

    /**
     * 在此缓冲区的指定绝对 {@code index} 处设置指定的 64 位双精度浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的双精度浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 8} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setDouble(int index, double value);

    /**
     * 在此缓冲区的指定绝对 {@code index} 处以小端字节序设置指定的 64 位双精度浮点数。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 要写入的索引位置
     * @param value 要设置的双精度浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         {@code index + 8} 大于 {@code this.capacity}
     */
    public ByteBuf setDoubleLE(int index, double value) {
        return setLongLE(index, Double.doubleToRawLongBits(value));
    }

    /**
     * 从指定的绝对 {@code index} 开始将指定的源缓冲区的数据传输到此缓冲区，直到源缓冲区变为不可读。
     * 此方法基本上与 {@link #setBytes(int, ByteBuf, int, int)} 相同，
     * 除了此方法将源缓冲区的 {@code readerIndex} 增加传输的字节数，
     * 而 {@link #setBytes(int, ByteBuf, int, int)} 不会。
     * 此方法不会修改此缓冲区（即 {@code this}）的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源缓冲区
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + src.readableBytes} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setBytes(int index, ByteBuf src);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源缓冲区的数据传输到此缓冲区。
     * 此方法基本上与 {@link #setBytes(int, ByteBuf, int, int)} 相同，
     * 除了此方法将源缓冲区的 {@code readerIndex} 增加传输的字节数，
     * 而 {@link #setBytes(int, ByteBuf, int, int)} 不会。
     * 此方法不会修改此缓冲区（即 {@code this}）的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源缓冲区
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code length} 大于 {@code src.readableBytes}
     */
    public abstract ByteBuf setBytes(int index, ByteBuf src, int length);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源缓冲区的数据传输到此缓冲区。
     * 此方法不会修改源（即 {@code this}）和目标的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源缓冲区
     * @param srcIndex 源的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果指定的 {@code srcIndex} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code srcIndex + length} 大于 {@code src.capacity}
     */
    public abstract ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源数组的数据传输到此缓冲区。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源字节数组
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + src.length} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setBytes(int index, byte[] src);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源数组的数据传输到此缓冲区。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源字节数组
     * @param srcIndex 源的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0}，
     *         如果指定的 {@code srcIndex} 小于 {@code 0}，
     *         如果 {@code index + length} 大于 {@code this.capacity}，或
     *         如果 {@code srcIndex + length} 大于 {@code src.length}
     */
    public abstract ByteBuf setBytes(int index, byte[] src, int srcIndex, int length);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源缓冲区的数据传输到此缓冲区，直到源缓冲区的位置达到其限制。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param src 源 NIO ByteBuffer
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + src.remaining()} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setBytes(int index, ByteBuffer src);

    /**
     * 从指定的绝对 {@code index} 开始将指定的源流的内容传输到此缓冲区。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param in 源输入流
     * @param length 要传输的字节数
     * @return 从指定通道实际读入的字节数。如果指定通道已关闭，则返回 {@code -1}。
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的流在 I/O 期间抛出异常
     */
    public abstract int setBytes(int index, InputStream in, int length) throws IOException;

    /**
     * 从指定的绝对 {@code index} 开始将指定的源通道的内容传输到此缓冲区。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始传输的索引位置
     * @param in 源通道
     * @param length 要传输的最大字节数
     * @return 从指定通道实际读入的字节数。如果指定通道已关闭，则返回 {@code -1}。
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int setBytes(int index, ScatteringByteChannel in, int length) throws IOException;

    /**
     * 从给定文件位置开始将指定的源通道的内容传输到此缓冲区的指定绝对 {@code index}。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 此方法不会修改通道的位置。
     *
     * @param index 开始传输的索引位置
     * @param in 源文件通道
     * @param position 开始传输的文件位置
     * @param length 要传输的最大字节数
     * @return 从指定通道实际读入的字节数。如果指定通道已关闭，则返回 {@code -1}。
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int setBytes(int index, FileChannel in, long position, int length) throws IOException;

    /**
     * 从指定的绝对 {@code index} 开始用 <tt>NUL (0x00)</tt> 填充此缓冲区。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始填充的索引位置
     * @param length 要写入缓冲区的 <tt>NUL</tt> 数量
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code index} 小于 {@code 0} 或
     *         如果 {@code index + length} 大于 {@code this.capacity}
     */
    public abstract ByteBuf setZero(int index, int length);

    /**
     * 在当前 {@code writerIndex} 处写入指定的 {@link CharSequence}，并将 {@code writerIndex} 增加写入的字节数。
     *
     * @param index 应该写入序列的索引位置
     * @param sequence 要写入的字符序列
     * @param charset 应该使用的字符集
     * @return 写入的字节数
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.writableBytes} 不足以写入整个序列
     */
    public abstract int setCharSequence(int index, CharSequence sequence, Charset charset);

    /**
     * 在当前 {@code readerIndex} 处获取一个布尔值，并将 {@code readerIndex} 增加 {@code 1}。
     *
     * @return 当前读位置的布尔值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 1}
     */
    public abstract boolean readBoolean();

    /**
     * 在当前 {@code readerIndex} 处获取一个字节，并将 {@code readerIndex} 增加 {@code 1}。
     *
     * @return 当前读位置的字节值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 1}
     */
    public abstract byte readByte();

    /**
     * 在当前 {@code readerIndex} 处获取一个无符号字节，并将 {@code readerIndex} 增加 {@code 1}。
     *
     * @return 当前读位置的无符号字节值（作为 short 返回）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 1}
     */
    public abstract short readUnsignedByte();

    /**
     * 在当前 {@code readerIndex} 处获取一个 16 位短整数，并将 {@code readerIndex} 增加 {@code 2}。
     *
     * @return 当前读位置的短整数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 2}
     */
    public abstract short readShort();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 16 位短整数，并将 {@code readerIndex} 增加 {@code 2}。
     *
     * @return 当前读位置的短整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 2}
     */
    public abstract short readShortLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个无符号 16 位短整数，并将 {@code readerIndex} 增加 {@code 2}。
     *
     * @return 当前读位置的无符号短整数值（作为 int 返回）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 2}
     */
    public abstract int readUnsignedShort();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个无符号 16 位短整数，并将 {@code readerIndex} 增加 {@code 2}。
     *
     * @return 当前读位置的无符号短整数值（小端字节序，作为 int 返回）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 2}
     */
    public abstract int readUnsignedShortLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个 24 位中等整数，并将 {@code readerIndex} 增加 {@code 3}。
     *
     * @return 当前读位置的 24 位整数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 3}
     */
    public abstract int readMedium();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 24 位中等整数，并将 {@code readerIndex} 增加 {@code 3}。
     *
     * @return 当前读位置的 24 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 3}
     */
    public abstract int readMediumLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个无符号 24 位中等整数，并将 {@code readerIndex} 增加 {@code 3}。
     *
     * @return 当前读位置的无符号 24 位整数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 3}
     */
    public abstract int readUnsignedMedium();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个无符号 24 位中等整数，并将 {@code readerIndex} 增加 {@code 3}。
     *
     * @return 当前读位置的无符号 24 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 3}
     */
    public abstract int readUnsignedMediumLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个 32 位整数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的 32 位整数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public abstract int readInt();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 32 位整数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的 32 位整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public abstract int readIntLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个无符号 32 位整数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的无符号 32 位整数值（作为 long 返回）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public abstract long readUnsignedInt();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个无符号 32 位整数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的无符号 32 位整数值（小端字节序，作为 long 返回）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public abstract long readUnsignedIntLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个 64 位长整数，并将 {@code readerIndex} 增加 {@code 8}。
     *
     * @return 当前读位置的 64 位长整数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 8}
     */
    public abstract long readLong();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 64 位长整数，并将 {@code readerIndex} 增加 {@code 8}。
     *
     * @return 当前读位置的 64 位长整数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 8}
     */
    public abstract long readLongLE();

    /**
     * 在当前 {@code readerIndex} 处获取一个 2 字节 UTF-16 字符，并将 {@code readerIndex} 增加 {@code 2}。
     *
     * @return 当前读位置的字符值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 2}
     */
    public abstract char readChar();

    /**
     * 在当前 {@code readerIndex} 处获取一个 32 位浮点数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的浮点数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public abstract float readFloat();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 32 位浮点数，并将 {@code readerIndex} 增加 {@code 4}。
     *
     * @return 当前读位置的浮点数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 4}
     */
    public float readFloatLE() {
        return Float.intBitsToFloat(readIntLE());
    }

    /**
     * 在当前 {@code readerIndex} 处获取一个 64 位双精度浮点数，并将 {@code readerIndex} 增加 {@code 8}。
     *
     * @return 当前读位置的双精度浮点数值
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 8}
     */
    public abstract double readDouble();

    /**
     * 在当前 {@code readerIndex} 处以小端字节序获取一个 64 位双精度浮点数，并将 {@code readerIndex} 增加 {@code 8}。
     *
     * @return 当前读位置的双精度浮点数值（小端字节序）
     * @throws IndexOutOfBoundsException
     *         如果 {@code this.readableBytes} 小于 {@code 8}
     */
    public double readDoubleLE() {
        return Double.longBitsToDouble(readLongLE());
    }

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到新创建的缓冲区，并将 {@code readerIndex} 增加传输的字节数（= {@code length}）。
     * 返回缓冲区的 {@code readerIndex} 和 {@code writerIndex} 分别为 {@code 0} 和 {@code length}。
     *
     * @param length 要传输的字节数
     * @return 包含传输字节的新创建缓冲区
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readBytes(int length);

    /**
     * 返回此缓冲区从当前 {@code readerIndex} 开始的子区域的新切片，并将 {@code readerIndex} 增加新切片的大小（= {@code length}）。
     * <p>
     * 另外请注意，此方法不会调用 {@link #retain()}，因此引用计数不会增加。
     *
     * @param length 新切片的大小
     * @return 新创建的切片
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readSlice(int length);

    /**
     * 返回此缓冲区从当前 {@code readerIndex} 开始的子区域的新保留切片，并将 {@code readerIndex} 增加新切片的大小（= {@code length}）。
     * <p>
     * 请注意，此方法返回一个 {@linkplain #retain() 保留的} 缓冲区，与 {@link #readSlice(int)} 不同。
     * 此方法的行为类似于 {@code readSlice(...).retain()}，但此方法可能返回产生较少垃圾的缓冲区实现。
     *
     * @param length 新切片的大小
     * @return 新创建的切片
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readRetainedSlice(int length);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，直到目标变为不可写，
     * 并将 {@code readerIndex} 增加传输的字节数。此方法基本上与 {@link #readBytes(ByteBuf, int, int)} 相同，
     * 除了此方法将目标的 {@code writerIndex} 增加传输的字节数，而 {@link #readBytes(ByteBuf, int, int)} 不会。
     *
     * @param dst 目标缓冲区
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code dst.writableBytes} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readBytes(ByteBuf dst);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，并将 {@code readerIndex} 增加传输的字节数（= {@code length}）。
     * 此方法基本上与 {@link #readBytes(ByteBuf, int, int)} 相同，
     * 除了此方法将目标的 {@code writerIndex} 增加传输的字节数（= {@code length}），
     * 而 {@link #readBytes(ByteBuf, int, int)} 不会。
     *
     * @param dst 目标缓冲区
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes} 或
     *         如果 {@code length} 大于 {@code dst.writableBytes}
     */
    public abstract ByteBuf readBytes(ByteBuf dst, int length);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，并将 {@code readerIndex} 增加传输的字节数（= {@code length}）。
     *
     * @param dst 目标缓冲区
     * @param dstIndex 目标的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code dstIndex} 小于 {@code 0}，
     *         如果 {@code length} 大于 {@code this.readableBytes}，或
     *         如果 {@code dstIndex + length} 大于 {@code dst.capacity}
     */
    public abstract ByteBuf readBytes(ByteBuf dst, int dstIndex, int length);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，并将 {@code readerIndex} 增加传输的字节数（= {@code dst.length}）。
     *
     * @param dst 目标字节数组
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code dst.length} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readBytes(byte[] dst);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，并将 {@code readerIndex} 增加传输的字节数（= {@code length}）。
     *
     * @param dst 目标字节数组
     * @param dstIndex 目标的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code dstIndex} 小于 {@code 0}，
     *         如果 {@code length} 大于 {@code this.readableBytes}，或
     *         如果 {@code dstIndex + length} 大于 {@code dst.length}
     */
    public abstract ByteBuf readBytes(byte[] dst, int dstIndex, int length);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的目标，直到目标的位置达到其限制，
     * 并将 {@code readerIndex} 增加传输的字节数。
     *
     * @param dst 目标 NIO ByteBuffer
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code dst.remaining()} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf readBytes(ByteBuffer dst);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的流。
     *
     * @param out 目标输出流
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     * @throws IOException
     *         如果指定的流在 I/O 期间抛出异常
     */
    public abstract ByteBuf readBytes(OutputStream out, int length) throws IOException;

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定的流。
     *
     * @param out 目标通道
     * @param length 要传输的最大字节数
     * @return 实际写出到指定通道的字节数
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int readBytes(GatheringByteChannel out, int length) throws IOException;

    /**
     * 在当前 {@code readerIndex} 处获取给定长度的 {@link CharSequence}，并将 {@code readerIndex} 增加给定长度。
     *
     * @param length 要读取的长度
     * @param charset 应该使用的字符集
     * @return 字符序列
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract CharSequence readCharSequence(int length, Charset charset);

    /**
     * 从当前 {@code readerIndex} 开始将此缓冲区的数据传输到指定通道的给定文件位置。
     * 此方法不会修改通道的位置。
     *
     * @param out 目标文件通道
     * @param position 开始传输的文件位置
     * @param length 要传输的最大字节数
     * @return 实际写出到指定通道的字节数
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int readBytes(FileChannel out, long position, int length) throws IOException;

    /**
     * 将当前 {@code readerIndex} 增加指定的 {@code length}。
     *
     * @param length 要跳过的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract ByteBuf skipBytes(int length);

    /**
     * 在当前 {@code writerIndex} 处设置指定的布尔值，并将 {@code writerIndex} 增加 {@code 1}。
     * 如果 {@code this.writableBytes} 小于 {@code 1}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的布尔值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeBoolean(boolean value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的字节，并将 {@code writerIndex} 增加 {@code 1}。
     * 指定值的 24 个高位被忽略。
     * 如果 {@code this.writableBytes} 小于 {@code 1}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的字节值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeByte(int value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 16 位短整数，并将 {@code writerIndex} 增加 {@code 2}。
     * 指定值的 16 个高位被忽略。
     * 如果 {@code this.writableBytes} 小于 {@code 2}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的短整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeShort(int value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 16 位短整数，并将 {@code writerIndex} 增加 {@code 2}。
     * 指定值的 16 个高位被忽略。
     * 如果 {@code this.writableBytes} 小于 {@code 2}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的短整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeShortLE(int value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 24 位中等整数，并将 {@code writerIndex} 增加 {@code 3}。
     * 如果 {@code this.writableBytes} 小于 {@code 3}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的中等整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeMedium(int value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 24 位中等整数，并将 {@code writerIndex} 增加 {@code 3}。
     * 如果 {@code this.writableBytes} 小于 {@code 3}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的中等整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeMediumLE(int value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 32 位整数，并将 {@code writerIndex} 增加 {@code 4}。
     * 如果 {@code this.writableBytes} 小于 {@code 4}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeInt(int value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 32 位整数，并将 {@code writerIndex} 增加 {@code 4}。
     * 如果 {@code this.writableBytes} 小于 {@code 4}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeIntLE(int value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 64 位长整数，并将 {@code writerIndex} 增加 {@code 8}。
     * 如果 {@code this.writableBytes} 小于 {@code 8}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的长整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeLong(long value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 64 位长整数，并将 {@code writerIndex} 增加 {@code 8}。
     * 如果 {@code this.writableBytes} 小于 {@code 8}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的长整数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeLongLE(long value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 2 字节 UTF-16 字符，并将 {@code writerIndex} 增加 {@code 2}。
     * 指定值的 16 个高位被忽略。
     * 如果 {@code this.writableBytes} 小于 {@code 2}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的字符值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeChar(int value);

    /**
     * 在当前 {@code writerIndex} 处设置指定的 32 位浮点数，并将 {@code writerIndex} 增加 {@code 4}。
     * 如果 {@code this.writableBytes} 小于 {@code 4}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeFloat(float value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 32 位浮点数，并将 {@code writerIndex} 增加 {@code 4}。
     * 如果 {@code this.writableBytes} 小于 {@code 4}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public ByteBuf writeFloatLE(float value) {
        return writeIntLE(Float.floatToRawIntBits(value));
    }

    /**
     * 在当前 {@code writerIndex} 处设置指定的 64 位双精度浮点数，并将 {@code writerIndex} 增加 {@code 8}。
     * 如果 {@code this.writableBytes} 小于 {@code 8}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的双精度浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeDouble(double value);

    /**
     * 在当前 {@code writerIndex} 处以小端字节序设置指定的 64 位双精度浮点数，并将 {@code writerIndex} 增加 {@code 8}。
     * 如果 {@code this.writableBytes} 小于 {@code 8}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param value 要写入的双精度浮点数值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public ByteBuf writeDoubleLE(double value) {
        return writeLongLE(Double.doubleToRawLongBits(value));
    }

    /**
     * 从当前 {@code writerIndex} 开始将指定的源缓冲区的数据传输到此缓冲区，直到源缓冲区变为不可读，
     * 并将 {@code writerIndex} 增加传输的字节数。此方法基本上与 {@link #writeBytes(ByteBuf, int, int)} 相同，
     * 除了此方法将源缓冲区的 {@code readerIndex} 增加传输的字节数，而 {@link #writeBytes(ByteBuf, int, int)} 不会。
     * 如果 {@code this.writableBytes} 小于 {@code src.readableBytes}，
     * 将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源缓冲区
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeBytes(ByteBuf src);

    /**
     * 从当前 {@code writerIndex} 开始将指定的源缓冲区的数据传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数（= {@code length}）。
     * 此方法基本上与 {@link #writeBytes(ByteBuf, int, int)} 相同，
     * 除了此方法将源缓冲区的 {@code readerIndex} 增加传输的字节数（= {@code length}），
     * 而 {@link #writeBytes(ByteBuf, int, int)} 不会。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源缓冲区
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException 如果 {@code length} 大于 {@code src.readableBytes}
     */
    public abstract ByteBuf writeBytes(ByteBuf src, int length);

    /**
     * 从当前 {@code writerIndex} 开始将指定的源缓冲区的数据传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数（= {@code length}）。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源缓冲区
     * @param srcIndex 源的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code srcIndex} 小于 {@code 0}，或
     *         如果 {@code srcIndex + length} 大于 {@code src.capacity}
     */
    public abstract ByteBuf writeBytes(ByteBuf src, int srcIndex, int length);

    /**
     * 从当前 {@code writerIndex} 开始将指定的源数组的数据传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数（= {@code src.length}）。
     * 如果 {@code this.writableBytes} 小于 {@code src.length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源字节数组
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeBytes(byte[] src);

    /**
     * 从当前 {@code writerIndex} 开始将指定的源数组的数据传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数（= {@code length}）。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源字节数组
     * @param srcIndex 源的第一个索引
     * @param length 要传输的字节数
     * @return 返回当前缓冲区实例，支持链式调用
     * @throws IndexOutOfBoundsException
     *         如果指定的 {@code srcIndex} 小于 {@code 0}，或
     *         如果 {@code srcIndex + length} 大于 {@code src.length}
     */
    public abstract ByteBuf writeBytes(byte[] src, int srcIndex, int length);

    /**
     * 从当前 {@code writerIndex} 开始将指定的源缓冲区的数据传输到此缓冲区，直到源缓冲区的位置达到其限制，
     * 并将 {@code writerIndex} 增加传输的字节数。
     * 如果 {@code this.writableBytes} 小于 {@code src.remaining()}，
     * 将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param src 源 NIO ByteBuffer
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeBytes(ByteBuffer src);

    /**
     * 从当前 {@code writerIndex} 开始将指定的流的内容传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param in 源输入流
     * @param length 要传输的字节数
     * @return 从指定流实际读入的字节数
     * @throws IOException 如果指定的流在 I/O 期间抛出异常
     */
    public abstract int writeBytes(InputStream in, int length) throws IOException;

    /**
     * 从当前 {@code writerIndex} 开始将指定的通道的内容传输到此缓冲区，并将 {@code writerIndex} 增加传输的字节数。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param in 源通道
     * @param length 要传输的最大字节数
     * @return 从指定通道实际读入的字节数
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int writeBytes(ScatteringByteChannel in, int length) throws IOException;

    /**
     * 从给定文件位置开始将指定的通道的内容传输到此缓冲区的当前 {@code writerIndex}，并将 {@code writerIndex} 增加传输的字节数。
     * 此方法不会修改通道的位置。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param in 源文件通道
     * @param position 开始传输的文件位置
     * @param length 要传输的最大字节数
     * @return 从指定通道实际读入的字节数
     * @throws IOException
     *         如果指定的通道在 I/O 期间抛出异常
     */
    public abstract int writeBytes(FileChannel in, long position, int length) throws IOException;

    /**
     * 从当前 {@code writerIndex} 开始用 <tt>NUL (0x00)</tt> 填充此缓冲区，并将 {@code writerIndex} 增加指定的 {@code length}。
     * 如果 {@code this.writableBytes} 小于 {@code length}，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param length 要写入缓冲区的 <tt>NUL</tt> 数量
     * @return 返回当前缓冲区实例，支持链式调用
     */
    public abstract ByteBuf writeZero(int length);

    /**
     * 在当前 {@code writerIndex} 处写入指定的 {@link CharSequence}，并将 {@code writerIndex} 增加写入的字节数。
     * 如果 {@code this.writableBytes} 不足以写入整个序列，将调用 {@link #ensureWritable(int)} 尝试扩展容量以适应。
     *
     * @param sequence 要写入的字符序列
     * @param charset 应该使用的字符集
     * @return 写入的字节数
     */
    public abstract int writeCharSequence(CharSequence sequence, Charset charset);

    /**
     * 在此缓冲区中定位指定 {@code value} 的第一次出现。搜索从指定的 {@code fromIndex}（包含）到指定的 {@code toIndex}（不包含）进行。
     * <p>
     * 如果 {@code fromIndex} 大于 {@code toIndex}，搜索将从 {@code fromIndex}（不包含）向下到 {@code toIndex}（包含）以相反顺序执行。
     * <p>
     * 请注意，较低的索引总是包含的，较高的总是不包含的。
     * <p>
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param fromIndex 开始搜索的索引位置
     * @param toIndex 结束搜索的索引位置
     * @param value 要搜索的字节值
     * @return 如果找到，返回第一次出现的绝对索引。否则返回 {@code -1}。
     */
    public abstract int indexOf(int fromIndex, int toIndex, byte value);

    /**
     * 在此缓冲区中定位指定 {@code value} 的第一次出现。搜索从当前 {@code readerIndex}（包含）到当前 {@code writerIndex}（不包含）进行。
     * <p>
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param value 要搜索的字节值
     * @return 当前 {@code readerIndex} 和第一次出现之间的字节数（如果找到）。否则返回 {@code -1}。
     */
    public abstract int bytesBefore(byte value);

    /**
     * 在此缓冲区中定位指定 {@code value} 的第一次出现。搜索从当前 {@code readerIndex}（包含）开始，持续指定的 {@code length}。
     * <p>
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param length 搜索的长度
     * @param value 要搜索的字节值
     * @return 当前 {@code readerIndex} 和第一次出现之间的字节数（如果找到）。否则返回 {@code -1}。
     * @throws IndexOutOfBoundsException
     *         如果 {@code length} 大于 {@code this.readableBytes}
     */
    public abstract int bytesBefore(int length, byte value);

    /**
     * 在此缓冲区中定位指定 {@code value} 的第一次出现。搜索从指定的 {@code index}（包含）开始，持续指定的 {@code length}。
     * <p>
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始搜索的索引位置
     * @param length 搜索的长度
     * @param value 要搜索的字节值
     * @return 指定 {@code index} 和第一次出现之间的字节数（如果找到）。否则返回 {@code -1}。
     * @throws IndexOutOfBoundsException
     *         如果 {@code index + length} 大于 {@code this.capacity}
     */
    public abstract int bytesBefore(int index, int length, byte value);

    /**
     * 使用指定的 {@code processor} 以升序遍历此缓冲区的可读字节。
     *
     * @param processor 字节处理器
     * @return 如果处理器遍历到或超出可读字节的末尾，返回 {@code -1}。
     *         如果 {@link ByteProcessor#process(byte)} 返回 {@code false}，返回最后访问的索引。
     */
    public abstract int forEachByte(ByteProcessor processor);

    /**
     * 使用指定的 {@code processor} 以升序遍历此缓冲区的指定区域。
     * （即 {@code index}、{@code (index + 1)}、.. {@code (index + length - 1)}）
     *
     * @param index 开始遍历的索引位置
     * @param length 遍历的长度
     * @param processor 字节处理器
     * @return 如果处理器遍历到或超出指定区域的末尾，返回 {@code -1}。
     *         如果 {@link ByteProcessor#process(byte)} 返回 {@code false}，返回最后访问的索引。
     */
    public abstract int forEachByte(int index, int length, ByteProcessor processor);

    /**
     * 使用指定的 {@code processor} 以降序遍历此缓冲区的可读字节。
     *
     * @param processor 字节处理器
     * @return 如果处理器遍历到或超出可读字节的开始，返回 {@code -1}。
     *         如果 {@link ByteProcessor#process(byte)} 返回 {@code false}，返回最后访问的索引。
     */
    public abstract int forEachByteDesc(ByteProcessor processor);

    /**
     * 使用指定的 {@code processor} 以降序遍历此缓冲区的指定区域。
     * （即 {@code (index + length - 1)}、{@code (index + length - 2)}、... {@code index}）
     *
     * @param index 开始遍历的索引位置
     * @param length 遍历的长度
     * @param processor 字节处理器
     * @return 如果处理器遍历到或超出指定区域的开始，返回 {@code -1}。
     *         如果 {@link ByteProcessor#process(byte)} 返回 {@code false}，返回最后访问的索引。
     */
    public abstract int forEachByteDesc(int index, int length, ByteProcessor processor);

    /**
     * 返回此缓冲区可读字节的副本。修改返回缓冲区或此缓冲区的内容完全不会相互影响。
     * 此方法与 {@code buf.copy(buf.readerIndex(), buf.readableBytes())} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @return 此缓冲区可读字节的副本
     */
    public abstract ByteBuf copy();

    /**
     * 返回此缓冲区子区域的副本。修改返回缓冲区或此缓冲区的内容完全不会相互影响。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始复制的索引位置
     * @param length 要复制的长度
     * @return 此缓冲区指定子区域的副本
     */
    public abstract ByteBuf copy(int index, int length);

    /**
     * 返回此缓冲区可读字节的切片。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。此方法与 {@code buf.slice(buf.readerIndex(), buf.readableBytes())} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 另外请注意，此方法不会调用 {@link #retain()}，因此引用计数不会增加。
     *
     * @return 此缓冲区可读字节的切片
     */
    public abstract ByteBuf slice();

    /**
     * 返回此缓冲区可读字节的保留切片。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。此方法与 {@code buf.slice(buf.readerIndex(), buf.readableBytes())} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 请注意，此方法返回一个 {@linkplain #retain() 保留的} 缓冲区，与 {@link #slice()} 不同。
     * 此方法的行为类似于 {@code slice().retain()}，但此方法可能返回产生较少垃圾的缓冲区实现。
     *
     * @return 此缓冲区可读字节的保留切片
     */
    public abstract ByteBuf retainedSlice();

    /**
     * 返回此缓冲区子区域的切片。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 另外请注意，此方法不会调用 {@link #retain()}，因此引用计数不会增加。
     *
     * @param index 开始切片的索引位置
     * @param length 切片的长度
     * @return 此缓冲区指定子区域的切片
     */
    public abstract ByteBuf slice(int index, int length);

    /**
     * 返回此缓冲区子区域的保留切片。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 请注意，此方法返回一个 {@linkplain #retain() 保留的} 缓冲区，与 {@link #slice(int, int)} 不同。
     * 此方法的行为类似于 {@code slice(...).retain()}，但此方法可能返回产生较少垃圾的缓冲区实现。
     *
     * @param index 开始切片的索引位置
     * @param length 切片的长度
     * @return 此缓冲区指定子区域的保留切片
     */
    public abstract ByteBuf retainedSlice(int index, int length);

    /**
     * 返回共享此缓冲区整个区域的缓冲区。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 读取器和写入器标记不会被复制。另外请注意，此方法不会调用 {@link #retain()}，因此引用计数不会增加。
     *
     * @return 一个缓冲区，其可读内容等同于 {@link #slice()} 返回的缓冲区。
     * 但是，此缓冲区将共享底层缓冲区的容量，因此如果需要，允许访问所有底层内容。
     */
    public abstract ByteBuf duplicate();

    /**
     * 返回共享此缓冲区整个区域的保留缓冲区。修改返回缓冲区或此缓冲区的内容会影响彼此的内容，
     * 但它们维护单独的索引和标记。此方法与 {@code buf.slice(0, buf.capacity())} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * <p>
     * 请注意，此方法返回一个 {@linkplain #retain() 保留的} 缓冲区，与 {@link #slice(int, int)} 不同。
     * 此方法的行为类似于 {@code duplicate().retain()}，但此方法可能返回产生较少垃圾的缓冲区实现。
     *
     * @return 此缓冲区的保留副本
     */
    public abstract ByteBuf retainedDuplicate();

    /**
     * 返回构成此缓冲区的 NIO {@link ByteBuffer} 的最大数量。请注意，{@link #nioBuffers()}
     * 或 {@link #nioBuffers(int, int)} 可能返回较少数量的 {@link ByteBuffer}。
     *
     * @return 如果此缓冲区没有底层 {@link ByteBuffer}，返回 {@code -1}。
     *         如果此缓冲区至少有一个底层 {@link ByteBuffer}，返回底层 {@link ByteBuffer} 的数量。
     *         请注意，此方法不返回 {@code 0} 以避免混淆。
     * @see #nioBuffer()
     * @see #nioBuffer(int, int)
     * @see #nioBuffers()
     * @see #nioBuffers(int, int)
     */
    public abstract int nioBufferCount();

    /**
     * 将此缓冲区的可读字节公开为 NIO {@link ByteBuffer}。返回的缓冲区要么共享要么包含此缓冲区的复制内容，
     * 而更改返回的 NIO 缓冲区的位置和限制不会影响此缓冲区的索引和标记。
     * 此方法与 {@code buf.nioBuffer(buf.readerIndex(), buf.readableBytes())} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 请注意，如果此缓冲区是动态缓冲区并且调整了其容量，返回的 NIO 缓冲区将不会看到此缓冲区的更改。
     *
     * @return 此缓冲区可读字节的 NIO ByteBuffer
     * @throws UnsupportedOperationException
     *         如果此缓冲区无法创建与自身共享内容的 {@link ByteBuffer}
     * @see #nioBufferCount()
     * @see #nioBuffers()
     * @see #nioBuffers(int, int)
     */
    public abstract ByteBuffer nioBuffer();

    /**
     * 将此缓冲区的子区域公开为 NIO {@link ByteBuffer}。返回的缓冲区要么共享要么包含此缓冲区的复制内容，
     * 而更改返回的 NIO 缓冲区的位置和限制不会影响此缓冲区的索引和标记。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 请注意，如果此缓冲区是动态缓冲区并且调整了其容量，返回的 NIO 缓冲区将不会看到此缓冲区的更改。
     *
     * @param index 开始公开的索引位置
     * @param length 要公开的长度
     * @return 此缓冲区指定子区域的 NIO ByteBuffer
     * @throws UnsupportedOperationException
     *         如果此缓冲区无法创建与自身共享内容的 {@link ByteBuffer}
     * @see #nioBufferCount()
     * @see #nioBuffers()
     * @see #nioBuffers(int, int)
     */
    public abstract ByteBuffer nioBuffer(int index, int length);

    /**
     * 仅供内部使用：公开内部 NIO 缓冲区。
     *
     * @param index 开始公开的索引位置
     * @param length 要公开的长度
     * @return 内部 NIO ByteBuffer
     */
    public abstract ByteBuffer internalNioBuffer(int index, int length);

    /**
     * 将此缓冲区的可读字节公开为 NIO {@link ByteBuffer} 数组。返回的缓冲区要么共享要么包含此缓冲区的复制内容，
     * 而更改返回的 NIO 缓冲区的位置和限制不会影响此缓冲区的索引和标记。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 请注意，如果此缓冲区是动态缓冲区并且调整了其容量，返回的 NIO 缓冲区将不会看到此缓冲区的更改。
     *
     * @return 此缓冲区可读字节的 NIO ByteBuffer 数组
     * @throws UnsupportedOperationException
     *         如果此缓冲区无法创建与自身共享内容的 {@link ByteBuffer}
     * @see #nioBufferCount()
     * @see #nioBuffer()
     * @see #nioBuffer(int, int)
     */
    public abstract ByteBuffer[] nioBuffers();

    /**
     * 将此缓冲区指定索引和长度的字节公开为 NIO {@link ByteBuffer} 数组。
     * 返回的缓冲区要么共享要么包含此缓冲区的复制内容，而更改返回的 NIO 缓冲区的位置和限制不会影响此缓冲区的索引和标记。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     * 请注意，如果此缓冲区是动态缓冲区并且调整了其容量，返回的 NIO 缓冲区将不会看到此缓冲区的更改。
     *
     * @param index 开始公开的索引位置
     * @param length 要公开的长度
     * @return 此缓冲区指定区域的 NIO ByteBuffer 数组
     * @throws UnsupportedOperationException
     *         如果此缓冲区无法创建与自身共享内容的 {@link ByteBuffer}
     * @see #nioBufferCount()
     * @see #nioBuffer()
     * @see #nioBuffer(int, int)
     */
    public abstract ByteBuffer[] nioBuffers(int index, int length);

    /**
     * 当且仅当此缓冲区有支持字节数组时返回 {@code true}。
     * 如果此方法返回 true，您可以安全地调用 {@link #array()} 和 {@link #arrayOffset()}。
     *
     * @return 如果此缓冲区有可访问的支持字节数组则返回 true
     */
    public abstract boolean hasArray();

    /**
     * 返回此缓冲区的支持字节数组。
     *
     * @return 支持字节数组
     * @throws UnsupportedOperationException
     *         如果没有可访问的支持字节数组
     */
    public abstract byte[] array();

    /**
     * 返回此缓冲区支持字节数组中第一个字节的偏移量。
     *
     * @return 支持字节数组中的偏移量
     * @throws UnsupportedOperationException
     *         如果没有可访问的支持字节数组
     */
    public abstract int arrayOffset();

    /**
     * 当且仅当此缓冲区有指向支持数据的低级内存地址的引用时返回 {@code true}。
     *
     * @return 如果此缓冲区有内存地址则返回 true
     */
    public abstract boolean hasMemoryAddress();

    /**
     * 返回指向支持数据第一个字节的低级内存地址。
     *
     * @return 内存地址
     * @throws UnsupportedOperationException
     *         如果此缓冲区不支持访问低级内存地址
     */
    public abstract long memoryAddress();

    /**
     * 如果此 {@link ByteBuf} 实现由单个内存区域支持，则返回 {@code true}。
     * 复合缓冲区实现必须返回 false，即使它们当前持有 ≤ 1 个组件。
     * 对于返回 {@code true} 的缓冲区，保证成功调用 {@link #discardReadBytes()}
     * 将使 {@link #maxFastWritableBytes()} 的值增加当前的 {@code readerIndex}。
     * <p>
     * 此方法默认返回 {@code false}，{@code false} 返回值并不一定意味着实现是复合的或者它不是由单个内存区域支持的。
     *
     * @return 如果此缓冲区是连续的则返回 true
     */
    public boolean isContiguous() {
        return false;
    }

    /**
     * 使用指定的字符集将此缓冲区的可读字节解码为字符串。
     * 此方法与 {@code buf.toString(buf.readerIndex(), buf.readableBytes(), charsetName)} 相同。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param charset 要使用的字符集
     * @return 解码后的字符串
     * @throws UnsupportedCharsetException
     *         如果当前 VM 不支持指定的字符集名称
     */
    public abstract String toString(Charset charset);

    /**
     * 使用指定的字符集将此缓冲区的子区域解码为字符串。
     * 此方法不会修改此缓冲区的 {@code readerIndex} 或 {@code writerIndex}。
     *
     * @param index 开始解码的索引位置
     * @param length 要解码的长度
     * @param charset 要使用的字符集
     * @return 解码后的字符串
     */
    public abstract String toString(int index, int length, Charset charset);

    /**
     * 返回从此缓冲区内容计算的哈希码。如果有一个字节数组与此数组 {@linkplain #equals(Object) 相等}，
     * 两个数组应该返回相同的值。
     *
     * @return 此缓冲区的哈希码
     */
    @Override
    public abstract int hashCode();

    /**
     * 确定指定缓冲区的内容是否与此数组的内容相同。这里的"相同"意味着：
     * <ul>
     * <li>两个缓冲区内容的大小相同，并且</li>
     * <li>两个缓冲区内容的每个字节都相同。</li>
     * </ul>
     * 请注意，它不比较 {@link #readerIndex()} 或 {@link #writerIndex()}。
     * 对于 {@code null} 和不是 {@link ByteBuf} 类型实例的对象，此方法也返回 {@code false}。
     *
     * @param obj 要比较的对象
     * @return 如果内容相同则返回 true
     */
    @Override
    public abstract boolean equals(Object obj);

    /**
     * 将指定缓冲区的内容与此缓冲区的内容进行比较。比较以与各种语言的字符串比较函数相同的方式执行，
     * 如 {@code strcmp}、{@code memcmp} 和 {@link String#compareTo(String)}。
     *
     * @param buffer 要比较的缓冲区
     * @return 比较结果
     */
    @Override
    public abstract int compareTo(ByteBuf buffer);

    /**
     * 返回此缓冲区的字符串表示形式。此方法不一定返回缓冲区的全部内容，
     * 而是返回关键属性的值，如 {@link #readerIndex()}、{@link #writerIndex()} 和 {@link #capacity()}。
     *
     * @return 此缓冲区的字符串表示形式
     */
    @Override
    public abstract String toString();

    /**
     * 增加此对象的引用计数指定的增量值。
     *
     * @param increment 引用计数的增量值
     * @return 返回当前缓冲区实例，支持链式调用
     */
    @Override
    public abstract ByteBuf retain(int increment);

    /**
     * 增加此对象的引用计数 1。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    @Override
    public abstract ByteBuf retain();

    /**
     * 记录此对象的当前访问位置以进行调试。
     * 如果此对象被确定为泄漏，此操作记录的信息将通过 {@link ResourceLeakDetector} 提供给您。
     * 此方法是 {@link #touch(Object) touch(null)} 的快捷方式。
     *
     * @return 返回当前缓冲区实例，支持链式调用
     */
    @Override
    public abstract ByteBuf touch();

    /**
     * 记录此对象的当前访问位置以及用于调试的附加任意信息。
     * 如果此对象被确定为泄漏，此操作记录的信息将通过 {@link ResourceLeakDetector} 提供给您。
     *
     * @param hint 附加的调试信息
     * @return 返回当前缓冲区实例，支持链式调用
     */
    @Override
    public abstract ByteBuf touch(Object hint);

    /**
     * 由 {@link AbstractByteBuf#ensureAccessible()} 内部使用，尝试防止在缓冲区被释放后使用缓冲区（尽力而为）。
     *
     * @return 如果缓冲区可访问则返回 true
     */
    boolean isAccessible() {
        return refCnt() != 0;
    }
}
