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

/**
 * 字节缓冲区分配器接口
 * 
 * 该接口的实现类负责分配各种类型的字节缓冲区（ByteBuf）。
 * 
 * 这是 Netty 框架中内存管理的核心接口，提供了统一的缓冲区分配方式。
 * 
 * 所有实现此接口的类都必须是线程安全的，可以在多线程环境下安全使用。
 * 
 * 主要功能包括：
 * - 分配堆内存缓冲区（heap buffer）
 * - 分配直接内存缓冲区（direct buffer）  
 * - 分配复合缓冲区（composite buffer）
 * - 分配适合 I/O 操作的缓冲区
 * - 提供缓冲区容量计算功能
 */
public interface ByteBufAllocator {

    /**
     * 默认的字节缓冲区分配器实例
     * 
     * 这是一个全局共享的默认分配器，通常是池化的分配器实现。
     * 
     * 在大多数情况下，建议使用这个默认分配器，因为它经过了优化，
     * 能够提供良好的性能和内存使用效率。
     */
    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /**
     * 分配一个字节缓冲区
     * 
     * 分配的缓冲区类型（堆内存或直接内存）取决于具体的实现。
     * 
     * 通常情况下，实现会根据当前环境和配置选择最合适的缓冲区类型。
     * 
     * @return 新分配的字节缓冲区，初始容量由实现决定
     */
    ByteBuf buffer();

    /**
     * 分配一个具有指定初始容量的字节缓冲区
     * 
     * 分配的缓冲区类型（堆内存或直接内存）取决于具体的实现。
     * 
     * 初始容量是缓冲区创建时的大小，当数据超过这个容量时，
     * 缓冲区会自动扩展（如果允许的话）。
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @return 新分配的字节缓冲区
     */
    ByteBuf buffer(int initialCapacity);

    /**
     * 分配一个具有指定初始容量和最大容量的字节缓冲区
     * 
     * 分配的缓冲区类型（堆内存或直接内存）取决于具体的实现。
     * 
     * 最大容量限制了缓冲区能够扩展到的最大大小，超过这个限制
     * 将会抛出异常。
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @param maxCapacity 最大容量，必须大于等于 initialCapacity
     * @return 新分配的字节缓冲区
     */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个适合 I/O 操作的字节缓冲区
     * 
     * 这个方法会优先分配直接内存缓冲区，因为直接内存缓冲区
     * 在进行网络 I/O 和文件 I/O 时性能更好。
     * 
     * 直接内存缓冲区避免了 JVM 堆内存到系统内存的数据拷贝，
     * 能够提供更高的 I/O 性能。
     * 
     * @return 新分配的适合 I/O 操作的字节缓冲区
     */
    ByteBuf ioBuffer();

    /**
     * 分配一个具有指定初始容量且适合 I/O 操作的字节缓冲区
     * 
     * 这个方法会优先分配直接内存缓冲区。
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @return 新分配的适合 I/O 操作的字节缓冲区
     */
    ByteBuf ioBuffer(int initialCapacity);

    /**
     * 分配一个具有指定初始容量和最大容量且适合 I/O 操作的字节缓冲区
     * 
     * 这个方法会优先分配直接内存缓冲区。
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @param maxCapacity 最大容量，必须大于等于 initialCapacity
     * @return 新分配的适合 I/O 操作的字节缓冲区
     */
    ByteBuf ioBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个堆内存字节缓冲区
     * 
     * 堆内存缓冲区的数据存储在 JVM 堆内存中，由 JVM 的垃圾回收器管理。
     * 
     * 堆内存缓冲区的优点是内存管理简单，缺点是在进行 I/O 操作时
     * 可能需要额外的内存拷贝。
     * 
     * @return 新分配的堆内存字节缓冲区
     */
    ByteBuf heapBuffer();

    /**
     * 分配一个具有指定初始容量的堆内存字节缓冲区
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @return 新分配的堆内存字节缓冲区
     */
    ByteBuf heapBuffer(int initialCapacity);

    /**
     * 分配一个具有指定初始容量和最大容量的堆内存字节缓冲区
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @param maxCapacity 最大容量，必须大于等于 initialCapacity
     * @return 新分配的堆内存字节缓冲区
     */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个直接内存字节缓冲区
     * 
     * 直接内存缓冲区的数据存储在 JVM 堆外内存中，不受 JVM 垃圾回收器管理。
     * 
     * 直接内存缓冲区的优点是在进行 I/O 操作时性能更好，缺点是内存管理
     * 相对复杂，需要手动释放。
     * 
     * @return 新分配的直接内存字节缓冲区
     */
    ByteBuf directBuffer();

    /**
     * 分配一个具有指定初始容量的直接内存字节缓冲区
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @return 新分配的直接内存字节缓冲区
     */
    ByteBuf directBuffer(int initialCapacity);

    /**
     * 分配一个具有指定初始容量和最大容量的直接内存字节缓冲区
     * 
     * @param initialCapacity 初始容量，必须大于等于 0
     * @param maxCapacity 最大容量，必须大于等于 initialCapacity
     * @return 新分配的直接内存字节缓冲区
     */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /**
     * 分配一个复合字节缓冲区
     * 
     * 复合缓冲区可以将多个缓冲区组合成一个逻辑上的缓冲区，
     * 而不需要进行数据拷贝。
     * 
     * 这对于需要将多个数据源合并的场景非常有用，比如 HTTP 消息
     * 的头部和正文可以分别存储在不同的缓冲区中。
     * 
     * 分配的缓冲区类型（堆内存或直接内存）取决于具体的实现。
     * 
     * @return 新分配的复合字节缓冲区
     */
    CompositeByteBuf compositeBuffer();

    /**
     * 分配一个具有指定最大组件数量的复合字节缓冲区
     * 
     * 最大组件数量限制了可以添加到复合缓冲区中的子缓冲区数量。
     * 
     * 分配的缓冲区类型（堆内存或直接内存）取决于具体的实现。
     * 
     * @param maxNumComponents 最大组件数量，必须大于 0
     * @return 新分配的复合字节缓冲区
     */
    CompositeByteBuf compositeBuffer(int maxNumComponents);

    /**
     * 分配一个堆内存复合字节缓冲区
     * 
     * 这个复合缓冲区及其组件都将使用堆内存。
     * 
     * @return 新分配的堆内存复合字节缓冲区
     */
    CompositeByteBuf compositeHeapBuffer();

    /**
     * 分配一个具有指定最大组件数量的堆内存复合字节缓冲区
     * 
     * @param maxNumComponents 最大组件数量，必须大于 0
     * @return 新分配的堆内存复合字节缓冲区
     */
    CompositeByteBuf compositeHeapBuffer(int maxNumComponents);

    /**
     * 分配一个直接内存复合字节缓冲区
     * 
     * 这个复合缓冲区及其组件都将使用直接内存。
     * 
     * @return 新分配的直接内存复合字节缓冲区
     */
    CompositeByteBuf compositeDirectBuffer();

    /**
     * 分配一个具有指定最大组件数量的直接内存复合字节缓冲区
     * 
     * @param maxNumComponents 最大组件数量，必须大于 0
     * @return 新分配的直接内存复合字节缓冲区
     */
    CompositeByteBuf compositeDirectBuffer(int maxNumComponents);

    /**
     * 检查直接内存缓冲区是否使用了池化技术
     * 
     * 池化技术可以重用已分配的内存，减少内存分配和释放的开销，
     * 从而提高性能并减少垃圾回收的压力。
     * 
     * @return 如果直接内存缓冲区使用了池化技术则返回 true，否则返回 false
     */
    boolean isDirectBufferPooled();

    /**
     * 计算字节缓冲区的新容量
     * 
     * 当字节缓冲区需要扩展时，这个方法用于计算新的容量大小。
     * 
     * 计算策略通常会考虑性能和内存使用的平衡，避免频繁的内存重新分配，
     * 同时也要避免过度分配造成内存浪费。
     * 
     * @param minNewCapacity 所需的最小新容量
     * @param maxCapacity 允许的最大容量上限
     * @return 计算出的新容量，介于 minNewCapacity 和 maxCapacity 之间
     */
    int calculateNewCapacity(int minNewCapacity, int maxCapacity);
}
