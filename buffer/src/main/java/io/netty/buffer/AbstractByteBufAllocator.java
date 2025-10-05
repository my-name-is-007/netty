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

import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakTracker;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

/**
 * ByteBuf分配器的抽象骨架实现类
 * 
 * 这个类提供了ByteBuf分配器的基础实现，定义了创建各种类型ByteBuf的通用逻辑。
 * 它是一个抽象类，子类需要实现具体的堆内存和直接内存ByteBuf的创建方法。
 * 
 * 主要功能包括：
 * 1. 提供默认的缓冲区分配策略（堆内存 vs 直接内存）
 * 2. 实现内存泄漏检测功能
 * 3. 提供容量计算算法
 * 4. 支持组合缓冲区的创建
 */
public abstract class AbstractByteBufAllocator implements ByteBufAllocator {

    /** 默认初始容量：256字节 */
    static final int DEFAULT_INITIAL_CAPACITY = 256;

    /** 默认最大容量：Integer的最大值 */
    static final int DEFAULT_MAX_CAPACITY = Integer.MAX_VALUE;

    /** 默认最大组件数量：16个 */
    static final int DEFAULT_MAX_COMPONENTS = 16;

    /** 容量计算阈值：4MB，超过此阈值时使用不同的扩容策略 */
    static final int CALCULATE_THRESHOLD = 1048576 * 4; // 4 MiB page

    static {
        // 将当前类的toLeakAwareBuffer方法排除在资源泄漏检测之外
        // 因为这个方法本身就是用来创建泄漏检测包装器的
        ResourceLeakDetector.addExclusions(AbstractByteBufAllocator.class, "toLeakAwareBuffer");
    }

    /**
     * 将普通ByteBuf包装成具有泄漏检测功能的ByteBuf
     * 
     * 根据当前的泄漏检测级别，选择不同的包装器：
     * - SIMPLE：使用简单的泄漏检测包装器
     * - ADVANCED/PARANOID：使用高级的泄漏检测包装器
     * - DISABLED：不进行包装，直接返回原始ByteBuf
     * 
     * @param buf 需要包装的原始ByteBuf
     * @return 包装后的ByteBuf，如果泄漏检测被禁用则返回原始ByteBuf
     */
    protected static ByteBuf toLeakAwareBuffer(ByteBuf buf) {
        ResourceLeakTracker<ByteBuf> leak;

        switch (ResourceLeakDetector.getLevel()) {
            case SIMPLE:
                // 简单模式：只检测是否有泄漏，不记录详细的调用栈信息
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new SimpleLeakAwareByteBuf(buf, leak);
                }
                break;

            case ADVANCED:
            case PARANOID:
                // 高级模式：检测泄漏并记录详细的调用栈信息，便于调试
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new AdvancedLeakAwareByteBuf(buf, leak);
                }
                break;

            default:
                // 禁用模式：不进行任何包装
                break;
        }
        return buf;
    }

    /**
     * 将组合ByteBuf包装成具有泄漏检测功能的组合ByteBuf
     * 
     * 与toLeakAwareBuffer(ByteBuf)方法类似，但专门用于组合缓冲区
     * 
     * @param buf 需要包装的原始组合ByteBuf
     * @return 包装后的组合ByteBuf
     */
    protected static CompositeByteBuf toLeakAwareBuffer(CompositeByteBuf buf) {
        ResourceLeakTracker<ByteBuf> leak;

        switch (ResourceLeakDetector.getLevel()) {
            case SIMPLE:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new SimpleLeakAwareCompositeByteBuf(buf, leak);
                }
                break;

            case ADVANCED:
            case PARANOID:
                leak = AbstractByteBuf.leakDetector.track(buf);
                if (leak != null) {
                    buf = new AdvancedLeakAwareCompositeByteBuf(buf, leak);
                }
                break;

            default:
                break;
        }
        return buf;
    }

    /** 是否默认使用直接内存缓冲区 */
    private final boolean directByDefault;

    /** 空的ByteBuf实例，用于优化零容量的缓冲区分配 */
    private final ByteBuf emptyBuf;

    /**
     * 默认构造函数
     * 
     * 创建一个默认使用堆内存缓冲区的分配器实例
     */
    protected AbstractByteBufAllocator() {
        this(false);
    }

    /**
     * 构造函数
     * 
     * @param preferDirect 是否优先使用直接内存缓冲区
     *                     true表示buffer()方法应该尝试分配直接内存缓冲区而不是堆内存缓冲区
     *                     注意：只有在平台支持Unsafe操作时，这个参数才会生效
     */
    protected AbstractByteBufAllocator(boolean preferDirect) {
        // 只有在preferDirect为true且平台支持Unsafe操作时，才默认使用直接内存
        directByDefault = preferDirect && PlatformDependent.hasUnsafe();

        // 创建一个空的ByteBuf实例，用于优化零容量缓冲区的分配
        emptyBuf = new EmptyByteBuf(this);
    }

    /**
     * 分配一个具有默认初始容量的缓冲区
     * 
     * 根据directByDefault的设置，选择分配堆内存或直接内存缓冲区
     * 
     * @return 新分配的ByteBuf实例
     */
    @Override
    public ByteBuf buffer() {
        if (directByDefault) {
            return directBuffer();
        }
        return heapBuffer();
    }

    /**
     * 分配一个指定初始容量的缓冲区
     * 
     * @param initialCapacity 初始容量
     * @return 新分配的ByteBuf实例
     */
    @Override
    public ByteBuf buffer(int initialCapacity) {
        if (directByDefault) {
            return directBuffer(initialCapacity);
        }
        return heapBuffer(initialCapacity);
    }

    /**
     * 分配一个指定初始容量和最大容量的缓冲区
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 新分配的ByteBuf实例
     */
    @Override
    public ByteBuf buffer(int initialCapacity, int maxCapacity) {
        if (directByDefault) {
            return directBuffer(initialCapacity, maxCapacity);
        }
        return heapBuffer(initialCapacity, maxCapacity);
    }

    /**
     * 分配一个适合I/O操作的缓冲区
     * 
     * I/O缓冲区通常使用直接内存，因为：
     * 1. 直接内存可以避免在I/O操作时的额外内存拷贝
     * 2. 提高I/O性能
     * 
     * 只有在平台支持Unsafe操作或直接内存缓冲区被池化时，才使用直接内存
     * 
     * @return 适合I/O操作的ByteBuf实例
     */
    @Override
    public ByteBuf ioBuffer() {
        if (PlatformDependent.hasUnsafe() || isDirectBufferPooled()) {
            return directBuffer(DEFAULT_INITIAL_CAPACITY);
        }
        return heapBuffer(DEFAULT_INITIAL_CAPACITY);
    }

    /**
     * 分配一个指定初始容量的I/O缓冲区
     * 
     * @param initialCapacity 初始容量
     * @return 适合I/O操作的ByteBuf实例
     */
    @Override
    public ByteBuf ioBuffer(int initialCapacity) {
        if (PlatformDependent.hasUnsafe() || isDirectBufferPooled()) {
            return directBuffer(initialCapacity);
        }
        return heapBuffer(initialCapacity);
    }

    /**
     * 分配一个指定初始容量和最大容量的I/O缓冲区
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 适合I/O操作的ByteBuf实例
     */
    @Override
    public ByteBuf ioBuffer(int initialCapacity, int maxCapacity) {
        if (PlatformDependent.hasUnsafe() || isDirectBufferPooled()) {
            return directBuffer(initialCapacity, maxCapacity);
        }
        return heapBuffer(initialCapacity, maxCapacity);
    }

    /**
     * 分配一个堆内存缓冲区，使用默认的初始容量和最大容量
     * 
     * 堆内存缓冲区的数据存储在JVM堆中，由垃圾收集器管理
     * 
     * @return 堆内存ByteBuf实例
     */
    @Override
    public ByteBuf heapBuffer() {
        return heapBuffer(DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }

    /**
     * 分配一个指定初始容量的堆内存缓冲区
     * 
     * @param initialCapacity 初始容量
     * @return 堆内存ByteBuf实例
     */
    @Override
    public ByteBuf heapBuffer(int initialCapacity) {
        return heapBuffer(initialCapacity, DEFAULT_MAX_CAPACITY);
    }

    /**
     * 分配一个指定初始容量和最大容量的堆内存缓冲区
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 堆内存ByteBuf实例
     */
    @Override
    public ByteBuf heapBuffer(int initialCapacity, int maxCapacity) {
        // 如果初始容量和最大容量都为0，返回空缓冲区实例
        if (initialCapacity == 0 && maxCapacity == 0) {
            return emptyBuf;
        }

        // 验证参数的有效性
        validate(initialCapacity, maxCapacity);

        // 调用抽象方法创建具体的堆内存缓冲区
        return newHeapBuffer(initialCapacity, maxCapacity);
    }

    /**
     * 分配一个直接内存缓冲区，使用默认的初始容量和最大容量
     * 
     * 直接内存缓冲区的数据存储在JVM堆外，不受垃圾收集器管理
     * 通常用于I/O操作以提高性能
     * 
     * @return 直接内存ByteBuf实例
     */
    @Override
    public ByteBuf directBuffer() {
        return directBuffer(DEFAULT_INITIAL_CAPACITY, DEFAULT_MAX_CAPACITY);
    }

    /**
     * 分配一个指定初始容量的直接内存缓冲区
     * 
     * @param initialCapacity 初始容量
     * @return 直接内存ByteBuf实例
     */
    @Override
    public ByteBuf directBuffer(int initialCapacity) {
        return directBuffer(initialCapacity, DEFAULT_MAX_CAPACITY);
    }

    /**
     * 分配一个指定初始容量和最大容量的直接内存缓冲区
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 直接内存ByteBuf实例
     */
    @Override
    public ByteBuf directBuffer(int initialCapacity, int maxCapacity) {
        // 如果初始容量和最大容量都为0，返回空缓冲区实例
        if (initialCapacity == 0 && maxCapacity == 0) {
            return emptyBuf;
        }

        // 验证参数的有效性
        validate(initialCapacity, maxCapacity);

        // 调用抽象方法创建具体的直接内存缓冲区
        return newDirectBuffer(initialCapacity, maxCapacity);
    }

    /**
     * 创建一个组合缓冲区，使用默认的最大组件数量
     * 
     * 组合缓冲区可以将多个ByteBuf组合成一个逻辑上的ByteBuf，
     * 而不需要进行内存拷贝
     * 
     * @return 组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeBuffer() {
        if (directByDefault) {
            return compositeDirectBuffer();
        }
        return compositeHeapBuffer();
    }

    /**
     * 创建一个指定最大组件数量的组合缓冲区
     * 
     * @param maxNumComponents 最大组件数量
     * @return 组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeBuffer(int maxNumComponents) {
        if (directByDefault) {
            return compositeDirectBuffer(maxNumComponents);
        }
        return compositeHeapBuffer(maxNumComponents);
    }

    /**
     * 创建一个堆内存组合缓冲区，使用默认的最大组件数量
     * 
     * @return 堆内存组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeHeapBuffer() {
        return compositeHeapBuffer(DEFAULT_MAX_COMPONENTS);
    }

    /**
     * 创建一个指定最大组件数量的堆内存组合缓冲区
     * 
     * @param maxNumComponents 最大组件数量
     * @return 堆内存组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeHeapBuffer(int maxNumComponents) {
        return toLeakAwareBuffer(new CompositeByteBuf(this, false, maxNumComponents));
    }

    /**
     * 创建一个直接内存组合缓冲区，使用默认的最大组件数量
     * 
     * @return 直接内存组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeDirectBuffer() {
        return compositeDirectBuffer(DEFAULT_MAX_COMPONENTS);
    }

    /**
     * 创建一个指定最大组件数量的直接内存组合缓冲区
     * 
     * @param maxNumComponents 最大组件数量
     * @return 直接内存组合ByteBuf实例
     */
    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        return toLeakAwareBuffer(new CompositeByteBuf(this, true, maxNumComponents));
    }

    /**
     * 验证初始容量和最大容量参数的有效性
     * 
     * @param initialCapacity 初始容量，必须大于等于0
     * @param maxCapacity 最大容量，必须大于等于初始容量
     * @throws IllegalArgumentException 如果参数无效
     */
    private static void validate(int initialCapacity, int maxCapacity) {
        // 检查初始容量是否为非负数
        checkPositiveOrZero(initialCapacity, "initialCapacity");

        // 检查初始容量是否不大于最大容量
        if (initialCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "initialCapacity: %d (expected: not greater than maxCapacity(%d)",
                    initialCapacity, maxCapacity));
        }
    }

    /**
     * 创建一个堆内存ByteBuf的抽象方法
     * 
     * 子类必须实现此方法来提供具体的堆内存缓冲区创建逻辑
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 新创建的堆内存ByteBuf实例
     */
    protected abstract ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity);

    /**
     * 创建一个直接内存ByteBuf的抽象方法
     * 
     * 子类必须实现此方法来提供具体的直接内存缓冲区创建逻辑
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 新创建的直接内存ByteBuf实例
     */
    protected abstract ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity);

    /**
     * 返回分配器的字符串表示
     * 
     * @return 包含类名和directByDefault设置的字符串
     */
    @Override
    public String toString() {
        return StringUtil.simpleClassName(this) + "(directByDefault: " + directByDefault + ')';
    }

    /**
     * 计算新的缓冲区容量
     * 
     * 这个方法实现了一个智能的容量扩展算法：
     * 1. 如果所需容量小于等于4MB，采用倍增策略（从64字节开始）
     * 2. 如果所需容量大于4MB，采用固定增量策略（每次增加4MB）
     * 
     * 这样的设计可以在小容量时快速扩展，在大容量时避免过度分配内存
     * 
     * @param minNewCapacity 所需的最小新容量
     * @param maxCapacity 允许的最大容量
     * @return 计算出的新容量
     * @throws IllegalArgumentException 如果参数无效
     */
    @Override
    public int calculateNewCapacity(int minNewCapacity, int maxCapacity) {
        // 检查最小新容量是否为非负数
        checkPositiveOrZero(minNewCapacity, "minNewCapacity");

        // 检查最小新容量是否不大于最大容量
        if (minNewCapacity > maxCapacity) {
            throw new IllegalArgumentException(String.format(
                    "minNewCapacity: %d (expected: not greater than maxCapacity(%d)",
                    minNewCapacity, maxCapacity));
        }

        final int threshold = CALCULATE_THRESHOLD; // 4 MiB 阈值

        // 如果正好等于阈值，直接返回阈值
        if (minNewCapacity == threshold) {
            return threshold;
        }

        // 如果超过阈值，使用固定增量策略而不是倍增策略
        // 这样可以避免在大容量时分配过多的内存
        if (minNewCapacity > threshold) {
            // 计算以阈值为单位的新容量
            int newCapacity = minNewCapacity / threshold * threshold;

            // 如果新容量加上一个阈值会超过最大容量，则直接使用最大容量
            if (newCapacity > maxCapacity - threshold) {
                newCapacity = maxCapacity;
            } else {
                // 否则增加一个阈值
                newCapacity += threshold;
            }
            return newCapacity;
        }

        // 未超过阈值时，使用倍增策略，从64字节开始倍增直到4MB
        // 这样可以在小容量时快速扩展，减少频繁的内存重新分配
        int newCapacity = 64;
        while (newCapacity < minNewCapacity) {
            newCapacity <<= 1; // 左移1位相当于乘以2
        }

        // 确保新容量不超过最大容量
        return Math.min(newCapacity, maxCapacity);
    }
}
