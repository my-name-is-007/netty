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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.buffer.PoolChunk.isSubpage;
import static java.lang.Math.max;

/**
 * 内存池竞技场（Arena）- Netty内存池管理的核心组件
 * 
 * 这个类是Netty内存池系统的核心，负责管理和分配不同大小的内存块。
 * 它采用了类似于jemalloc的内存分配策略，将内存分为不同的大小类别进行管理。
 * 
 * 主要功能：
 * 1. 管理小内存块（Small）和普通内存块（Normal）的分配
 * 2. 维护多个PoolChunkList来管理不同使用率的内存块
 * 3. 提供线程缓存支持以提高分配性能
 * 4. 统计内存分配和释放的各种指标
 * 
 * 内存分类：
 * - Small: 小于pageSize的内存分配，使用subpage机制
 * - Normal: pageSize到chunkSize之间的内存分配
 * - Huge: 超过chunkSize的大内存分配，不使用池化
 * 
 * @param <T> 内存类型，可以是byte[]（堆内存）或ByteBuffer（直接内存）
 */
abstract class PoolArena<T> extends SizeClasses implements PoolArenaMetric {
    
    // 检查平台是否支持Unsafe操作，用于优化内存操作
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 内存大小分类枚举
     * 
     * Small: 小内存块，通常小于一个页面大小
     * Normal: 普通内存块，介于页面大小和块大小之间
     */
    enum SizeClass {
        Small,
        Normal
    }

    // 父级内存分配器，用于获取全局配置和管理
    final PooledByteBufAllocator parent;

    // 小内存子页面池的数量
    final int numSmallSubpagePools;
    
    // 直接内存缓存对齐大小，用于优化CPU缓存性能
    final int directMemoryCacheAlignment;
    
    // 小内存子页面池数组，每个元素对应一个特定大小的内存池
    private final PoolSubpage<T>[] smallSubpagePools;

    // PoolChunkList链表，按照内存使用率分为不同等级
    // q050: 使用率50%-100%的内存块
    private final PoolChunkList<T> q050;
    
    // q025: 使用率25%-75%的内存块  
    private final PoolChunkList<T> q025;
    
    // q000: 使用率1%-50%的内存块
    private final PoolChunkList<T> q000;
    
    // qInit: 使用率0%-25%的内存块（初始状态）
    private final PoolChunkList<T> qInit;
    
    // q075: 使用率75%-100%的内存块
    private final PoolChunkList<T> q075;
    
    // q100: 使用率100%的内存块（已满）
    private final PoolChunkList<T> q100;

    // 所有PoolChunkList的只读列表，用于监控和统计
    private final List<PoolChunkListMetric> chunkListMetrics;

    // ===== 内存分配统计指标 =====
    
    // 普通内存分配次数（需要同步保护）
    private long allocationsNormal;
    
    // 小内存分配次数（使用LongCounter保证线程安全）
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    
    // 大内存分配次数（使用LongCounter保证线程安全）
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    
    // 当前活跃的大内存字节数（使用LongCounter保证线程安全）
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    // 小内存释放次数
    private long deallocationsSmall;
    
    // 普通内存释放次数
    private long deallocationsNormal;

    // 大内存释放次数（使用LongCounter保证线程安全）
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // 使用此Arena的线程缓存数量
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: 测试在高并发情况下添加填充是否有助于性能
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 构造函数 - 初始化内存池竞技场
     * 
     * @param parent 父级内存分配器
     * @param pageSize 页面大小，通常是8KB
     * @param pageShifts 页面大小的位移值，用于快速计算
     * @param chunkSize 内存块大小，通常是16MB
     * @param cacheAlignment 缓存对齐大小，用于优化性能
     */
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
            int pageShifts, int chunkSize, int cacheAlignment) {
        super(pageSize, pageShifts, chunkSize, cacheAlignment);
        this.parent = parent;
        directMemoryCacheAlignment = cacheAlignment;

        // 初始化小内存子页面池
        numSmallSubpagePools = nSubpages;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        
        // 为每个大小创建一个子页面池头节点
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead();
        }

        // 初始化PoolChunkList链表，按使用率分级管理
        // 使用率越高的放在后面，便于快速找到可用空间
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        // 设置双向链表的前驱指针
        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        // 创建监控指标列表
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * 创建一个新的子页面池头节点
     * 
     * 头节点是一个双向循环链表的哨兵节点，简化链表操作
     * 
     * @return 新创建的子页面池头节点
     */
    private PoolSubpage<T> newSubpagePoolHead() {
        PoolSubpage<T> head = new PoolSubpage<T>();
        head.prev = head;
        head.next = head;
        return head;
    }

    /**
     * 创建指定大小的子页面池数组
     * 
     * @param size 数组大小
     * @return 新创建的子页面池数组
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 判断当前Arena是否管理直接内存
     * 
     * @return true表示直接内存，false表示堆内存
     */
    abstract boolean isDirect();

    /**
     * 分配一个PooledByteBuf
     * 
     * 这是外部调用的主要入口，会创建一个新的ByteBuf并分配内存
     * 
     * @param cache 线程本地缓存，用于提高分配性能
     * @param reqCapacity 请求的容量大小
     * @param maxCapacity 最大容量限制
     * @return 分配好的PooledByteBuf
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 为已存在的ByteBuf分配内存
     * 
     * 根据请求大小选择不同的分配策略：
     * - Small: 使用子页面分配
     * - Normal: 使用普通页面分配  
     * - Huge: 直接分配大内存块
     * 
     * @param cache 线程本地缓存
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int sizeIdx = size2SizeIdx(reqCapacity);

        if (sizeIdx <= smallMaxSizeIdx) {
            // 小内存分配，使用子页面机制
            tcacheAllocateSmall(cache, buf, reqCapacity, sizeIdx);
        } else if (sizeIdx < nSizes) {
            // 普通内存分配，使用页面机制
            tcacheAllocateNormal(cache, buf, reqCapacity, sizeIdx);
        } else {
            // 大内存分配，不使用池化，直接分配
            int normCapacity = directMemoryCacheAlignment > 0
                    ? normalizeSize(reqCapacity) : reqCapacity;
            allocateHuge(buf, normCapacity);
        }
    }

    /**
     * 分配小内存块（通过线程缓存）
     * 
     * 首先尝试从线程缓存中分配，如果失败则从子页面池中分配
     * 
     * @param cache 线程本地缓存
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     * @param sizeIdx 大小索引
     */
    private void tcacheAllocateSmall(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                     final int sizeIdx) {

        if (cache.allocateSmall(this, buf, reqCapacity, sizeIdx)) {
            // 成功从缓存中分配，直接返回
            return;
        }

        /*
         * 在头节点上同步，这是必需的，因为PoolChunk#allocateSubpage(int)和
         * PoolChunk#free(long)可能会修改双向链表
         */
        final PoolSubpage<T> head = smallSubpagePools[sizeIdx];
        final boolean needsNormalAllocation;
        synchronized (head) {
            final PoolSubpage<T> s = head.next;
            needsNormalAllocation = s == head;
            if (!needsNormalAllocation) {
                // 找到可用的子页面，直接分配
                assert s.doNotDestroy && s.elemSize == sizeIdx2size(sizeIdx);
                long handle = s.allocate();
                assert handle >= 0;
                s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity, cache);
            }
        }

        if (needsNormalAllocation) {
            // 没有可用的子页面，需要分配新的普通页面
            synchronized (this) {
                allocateNormal(buf, reqCapacity, sizeIdx, cache);
            }
        }

        // 增加小内存分配计数
        incSmallAllocation();
    }

    /**
     * 分配普通内存块（通过线程缓存）
     * 
     * 首先尝试从线程缓存中分配，如果失败则从PoolChunkList中分配
     * 
     * @param cache 线程本地缓存
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     * @param sizeIdx 大小索引
     */
    private void tcacheAllocateNormal(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity,
                                      final int sizeIdx) {
        if (cache.allocateNormal(this, buf, reqCapacity, sizeIdx)) {
            // 成功从缓存中分配，直接返回
            return;
        }
        synchronized (this) {
            allocateNormal(buf, reqCapacity, sizeIdx, cache);
            ++allocationsNormal;
        }
    }

    /**
     * 分配普通内存块
     * 
     * 按照使用率从低到高的顺序尝试分配，这样可以保持内存块的使用率均衡
     * 如果所有现有块都无法分配，则创建新的内存块
     * 
     * 注意：此方法必须在synchronized(this)块中调用
     * 
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     * @param sizeIdx 大小索引
     * @param threadCache 线程缓存
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int sizeIdx, PoolThreadCache threadCache) {
        if (q050.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q025.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q000.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            qInit.allocate(buf, reqCapacity, sizeIdx, threadCache) ||
            q075.allocate(buf, reqCapacity, sizeIdx, threadCache)) {
            return;
        }

        // 所有现有块都无法分配，创建新的内存块
        PoolChunk<T> c = newChunk(pageSize, nPSizes, pageShifts, chunkSize);
        boolean success = c.allocate(buf, reqCapacity, sizeIdx, threadCache);
        assert success;
        qInit.add(c);
    }

    /**
     * 增加小内存分配计数
     */
    private void incSmallAllocation() {
        allocationsSmall.increment();
    }

    /**
     * 分配大内存块
     * 
     * 大内存块不使用池化机制，直接创建独立的内存块
     * 
     * @param buf 要分配内存的ByteBuf
     * @param reqCapacity 请求的容量大小
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    /**
     * 释放内存
     * 
     * 根据内存块类型选择不同的释放策略：
     * - 非池化内存：直接销毁
     * - 池化内存：尝试放入缓存或释放回池中
     * 
     * @param chunk 内存块
     * @param nioBuffer NIO缓冲区
     * @param handle 内存句柄
     * @param normCapacity 标准化容量
     * @param cache 线程缓存
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            // 非池化内存，直接销毁
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 池化内存，尝试缓存或释放
            SizeClass sizeClass = sizeClass(handle);
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // 成功放入缓存，不需要释放
                return;
            }

            freeChunk(chunk, handle, normCapacity, sizeClass, nioBuffer, false);
        }
    }

    /**
     * 根据句柄判断内存大小类别
     * 
     * @param handle 内存句柄
     * @return 内存大小类别
     */
    private static SizeClass sizeClass(long handle) {
        return isSubpage(handle) ? SizeClass.Small : SizeClass.Normal;
    }

    /**
     * 释放内存块
     * 
     * @param chunk 内存块
     * @param handle 内存句柄
     * @param normCapacity 标准化容量
     * @param sizeClass 大小类别
     * @param nioBuffer NIO缓冲区
     * @param finalizer 是否来自终结器调用
     */
    void freeChunk(PoolChunk<T> chunk, long handle, int normCapacity, SizeClass sizeClass, ByteBuffer nioBuffer,
                   boolean finalizer) {
        final boolean destroyChunk;
        synchronized (this) {
            // 只有在非终结器调用时才更新统计信息
            // 否则可能因为延迟类加载（如tomcat环境）导致失败
            if (!finalizer) {
                switch (sizeClass) {
                    case Normal:
                        ++deallocationsNormal;
                        break;
                    case Small:
                        ++deallocationsSmall;
                        break;
                    default:
                        throw new Error();
                }
            }
            destroyChunk = !chunk.parent.free(chunk, handle, normCapacity, nioBuffer);
        }
        if (destroyChunk) {
            // 销毁内存块不需要在同步锁中执行
            destroyChunk(chunk);
        }
    }

    /**
     * 查找指定大小索引的子页面池头节点
     * 
     * @param sizeIdx 大小索引
     * @return 子页面池头节点
     */
    PoolSubpage<T> findSubpagePoolHead(int sizeIdx) {
        return smallSubpagePools[sizeIdx];
    }

    /**
     * 重新分配ByteBuf的内存
     * 
     * 当需要扩容或缩容时调用此方法，会分配新内存并复制数据
     * 
     * @param buf 要重新分配的ByteBuf
     * @param newCapacity 新的容量大小
     * @param freeOldMemory 是否释放旧内存
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        assert newCapacity >= 0 && newCapacity <= buf.maxCapacity();

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        // 保存旧的内存信息
        PoolChunk<T> oldChunk = buf.chunk;
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;

        // 分配新内存（不影响读写索引）
        allocate(parent.threadCache(), buf, newCapacity);
        int bytesToCopy;
        if (newCapacity > oldCapacity) {
            bytesToCopy = oldCapacity;
        } else {
            buf.trimIndicesToCapacity(newCapacity);
            bytesToCopy = newCapacity;
        }
        
        // 复制数据到新内存
        memoryCopy(oldMemory, oldOffset, buf, bytesToCopy);
        
        // 释放旧内存
        if (freeOldMemory) {
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return 0;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return Collections.emptyList();
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    /**
     * 将子页面数组转换为监控指标列表
     * 
     * @param pages 子页面数组
     * @return 监控指标列表
     */
    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return 0;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return 0;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 创建新的内存块（池化）
     * 
     * @param pageSize 页面大小
     * @param maxPageIdx 最大页面索引
     * @param pageShifts 页面位移
     * @param chunkSize 块大小
     * @return 新的内存块
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize);
    
    /**
     * 创建新的内存块（非池化）
     * 
     * @param capacity 容量大小
     * @return 新的非池化内存块
     */
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    
    /**
     * 创建新的ByteBuf
     * 
     * @param maxCapacity 最大容量
     * @return 新的PooledByteBuf
     */
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    
    /**
     * 内存复制操作
     * 
     * @param src 源内存
     * @param srcOffset 源偏移量
     * @param dst 目标ByteBuf
     * @param length 复制长度
     */
    protected abstract void memoryCopy(T src, int srcOffset, PooledByteBuf<T> dst, int length);
    
    /**
     * 销毁内存块
     * 
     * @param chunk 要销毁的内存块
     */
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    /**
     * 将子页面池信息追加到字符串缓冲区
     * 
     * @param buf 字符串缓冲区
     * @param subpages 子页面数组
     */
    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    /**
     * 终结器方法 - 清理资源
     * 
     * 在对象被垃圾回收时调用，确保所有资源被正确释放
     */
    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    /**
     * 销毁所有子页面池
     * 
     * @param pages 子页面数组
     */
    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    /**
     * 销毁所有内存块列表
     * 
     * @param chunkLists 内存块列表数组
     */
    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    /**
     * 堆内存Arena实现
     * 
     * 管理堆内存（byte[]）的分配和释放
     */
    static final class HeapArena extends PoolArena<byte[]> {

        /**
         * 构造堆内存Arena
         * 
         * @param parent 父级分配器
         * @param pageSize 页面大小
         * @param pageShifts 页面位移
         * @param chunkSize 块大小
         * @param directMemoryCacheAlignment 直接内存缓存对齐（堆内存中不使用）
         */
        HeapArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                  int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        /**
         * 创建新的字节数组
         * 
         * @param size 数组大小
         * @return 新的字节数组
         */
        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxPageIdx, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(
                    this, null, newByteArray(chunkSize), pageSize, pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, null, newByteArray(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // 依赖GC回收堆内存
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, PooledByteBuf<byte[]> dst, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst.memory, dst.offset, length);
        }
    }

    /**
     * 直接内存Arena实现
     * 
     * 管理直接内存（ByteBuffer）的分配和释放
     */
    static final class DirectArena extends PoolArena<ByteBuffer> {

        /**
         * 构造直接内存Arena
         * 
         * @param parent 父级分配器
         * @param pageSize 页面大小
         * @param pageShifts 页面位移
         * @param chunkSize 块大小
         * @param directMemoryCacheAlignment 直接内存缓存对齐大小
         */
        DirectArena(PooledByteBufAllocator parent, int pageSize, int pageShifts,
                    int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, pageShifts, chunkSize,
                  directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxPageIdx,
            int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(chunkSize);
                return new PoolChunk<ByteBuffer>(this, memory, memory, pageSize, pageShifts,
                        chunkSize, maxPageIdx);
            }

            // 需要内存对齐时，分配额外空间用于对齐
            final ByteBuffer base = allocateDirect(chunkSize + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, pageSize,
                    pageShifts, chunkSize, maxPageIdx);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                ByteBuffer memory = allocateDirect(capacity);
                return new PoolChunk<ByteBuffer>(this, memory, memory, capacity);
            }

            // 需要内存对齐时，分配额外空间用于对齐
            final ByteBuffer base = allocateDirect(capacity + directMemoryCacheAlignment);
            final ByteBuffer memory = PlatformDependent.alignDirectBuffer(base, directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, base, memory, capacity);
        }

        /**
         * 分配直接内存
         * 
         * @param capacity 容量大小
         * @return 直接内存ByteBuffer
         */
        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner((ByteBuffer) chunk.base);
            } else {
                PlatformDependent.freeDirectBuffer((ByteBuffer) chunk.base);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, PooledByteBuf<ByteBuffer> dstBuf, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                // 使用Unsafe进行高性能内存复制
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dstBuf.memory) + dstBuf.offset, length);
            } else {
                // 使用NIO Buffer进行内存复制
                // 必须复制NIO缓冲区，因为它们可能被其他Netty缓冲区访问
                src = src.duplicate();
                ByteBuffer dst = dstBuf.internalNioBuffer();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstBuf.offset);
                dst.put(src);
            }
        }
    }
}
