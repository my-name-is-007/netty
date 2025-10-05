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

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThreadExecutorMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 池化的ByteBuf分配器实现类
 * 
 * 这是Netty中最重要的内存分配器，采用了池化技术来管理内存，大大提高了内存分配和回收的效率。
 * 
 * 主要特性：
 * 1. 内存池化：预先分配大块内存，避免频繁的系统调用
 * 2. 多线程优化：每个线程都有自己的缓存，减少线程间竞争
 * 3. 内存对齐：支持直接内存的对齐分配，提高访问效率
 * 4. 自动清理：定期清理不常用的缓存内存
 * 5. 统计监控：提供详细的内存使用统计信息
 * 
 * 内存分配策略：
 * - 小于512字节的分配请求使用small缓存
 * - 512字节到32KB的分配请求使用normal缓存
 * - 超过32KB的分配请求直接从chunk中分配
 * 
 * @author Netty项目组
 */
public class PooledByteBufAllocator extends AbstractByteBufAllocator implements ByteBufAllocatorMetricProvider {

    // 日志记录器，用于输出调试和错误信息

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PooledByteBufAllocator.class);

    // 默认堆内存arena数量，通常是CPU核心数的2倍

    private static final int DEFAULT_NUM_HEAP_ARENA;

    // 默认直接内存arena数量，通常是CPU核心数的2倍

    private static final int DEFAULT_NUM_DIRECT_ARENA;

    // 默认页面大小，8192字节（8KB）

    private static final int DEFAULT_PAGE_SIZE;

    // 默认最大阶数，11表示chunk大小为8192 << 11 = 16MB

    private static final int DEFAULT_MAX_ORDER; // 8192 << 11 = 16 MiB per chunk

    // 默认小对象缓存大小，256个

    private static final int DEFAULT_SMALL_CACHE_SIZE;

    // 默认普通对象缓存大小，64个

    private static final int DEFAULT_NORMAL_CACHE_SIZE;

    // 默认最大缓存buffer容量，32KB

    static final int DEFAULT_MAX_CACHED_BUFFER_CAPACITY;

    // 默认缓存清理间隔，8192次分配后进行一次清理

    private static final int DEFAULT_CACHE_TRIM_INTERVAL;

    // 默认缓存清理时间间隔，毫秒为单位

    private static final long DEFAULT_CACHE_TRIM_INTERVAL_MILLIS;

    // 默认是否为所有线程使用缓存，true表示启用

    private static final boolean DEFAULT_USE_CACHE_FOR_ALL_THREADS;

    // 默认直接内存缓存对齐大小

    private static final int DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT;

    // 默认每个chunk最大缓存的ByteBuffer数量

    static final int DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK;

    // 最小页面大小，4096字节（4KB）

    private static final int MIN_PAGE_SIZE = 4096;

    // 最大chunk大小，约1GB

    private static final int MAX_CHUNK_SIZE = (int) (((long) Integer.MAX_VALUE + 1) / 2);

    // 缓存清理任务，定期执行以释放不常用的缓存内存

    private final Runnable trimTask = new Runnable() {
        @Override
        public void run() {
            PooledByteBufAllocator.this.trimCurrentThreadCache();
        }
    };

    // 静态初始化块，设置所有默认参数

    static {
        // 获取直接内存缓存对齐配置

        int defaultAlignment = SystemPropertyUtil.getInt(
                "io.netty.allocator.directMemoryCacheAlignment", 0);

        // 获取页面大小配置，默认8192字节

        int defaultPageSize = SystemPropertyUtil.getInt("io.netty.allocator.pageSize", 8192);

        Throwable pageSizeFallbackCause = null;
        try {
            // 验证页面大小和对齐参数的有效性

            validateAndCalculatePageShifts(defaultPageSize, defaultAlignment);
        } catch (Throwable t) {
            // 如果验证失败，使用默认值

            pageSizeFallbackCause = t;
            defaultPageSize = 8192;
            defaultAlignment = 0;
        }
        DEFAULT_PAGE_SIZE = defaultPageSize;
        DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT = defaultAlignment;

        // 获取最大阶数配置，默认11

        int defaultMaxOrder = SystemPropertyUtil.getInt("io.netty.allocator.maxOrder", 11);

        Throwable maxOrderFallbackCause = null;
        try {
            // 验证chunk大小的有效性

            validateAndCalculateChunkSize(DEFAULT_PAGE_SIZE, defaultMaxOrder);
        } catch (Throwable t) {
            // 如果验证失败，使用默认值

            maxOrderFallbackCause = t;
            defaultMaxOrder = 11;
        }
        DEFAULT_MAX_ORDER = defaultMaxOrder;

        // 确定合理的heap arena和direct arena默认数量
        // 假设每个arena有3个chunk，池不应该消耗超过最大内存的50%

        final Runtime runtime = Runtime.getRuntime();

        /*
         * 我们默认使用2倍的可用处理器数量来减少竞争，因为我们在NIO和EPOLL中也使用2倍的可用处理器数量作为EventLoop的数量。
         * 如果我们选择一个较小的数字，我们会遇到热点问题，因为分配和释放需要在PoolArena上同步。
         *
         * 参见 https://github.com/netty/netty/issues/3888.
         */
        final int defaultMinNumArena = NettyRuntime.availableProcessors() * 2;
        final int defaultChunkSize = DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER;

        // 计算默认堆内存arena数量

        DEFAULT_NUM_HEAP_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numHeapArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                runtime.maxMemory() / defaultChunkSize / 2 / 3)));

        // 计算默认直接内存arena数量

        DEFAULT_NUM_DIRECT_ARENA = Math.max(0,
                SystemPropertyUtil.getInt(
                        "io.netty.allocator.numDirectArenas",
                        (int) Math.min(
                                defaultMinNumArena,
                                PlatformDependent.maxDirectMemory() / defaultChunkSize / 2 / 3)));

        // 缓存大小配置

        DEFAULT_SMALL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.smallCacheSize", 256);
        DEFAULT_NORMAL_CACHE_SIZE = SystemPropertyUtil.getInt("io.netty.allocator.normalCacheSize", 64);

        // 32KB是缓存buffer的默认最大容量，类似于'Scalable memory allocation using jemalloc'中的解释

        DEFAULT_MAX_CACHED_BUFFER_CAPACITY = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedBufferCapacity", 32 * 1024);

        // 当缓存条目不经常使用时，将释放缓存条目的分配阈值数量

        DEFAULT_CACHE_TRIM_INTERVAL = SystemPropertyUtil.getInt(
                "io.netty.allocator.cacheTrimInterval", 8192);

        // 处理已弃用的系统属性

        if (SystemPropertyUtil.contains("io.netty.allocation.cacheTrimIntervalMillis")) {
            logger.warn("-Dio.netty.allocation.cacheTrimIntervalMillis is deprecated," +
                    " use -Dio.netty.allocator.cacheTrimIntervalMillis");

            if (SystemPropertyUtil.contains("io.netty.allocator.cacheTrimIntervalMillis")) {
                // 两个系统属性都指定了，使用非弃用的那个

                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocator.cacheTrimIntervalMillis", 0);
            } else {
                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                        "io.netty.allocator.cacheTrimIntervalMillis", 0);
            }
        } else {
            DEFAULT_CACHE_TRIM_INTERVAL_MILLIS = SystemPropertyUtil.getLong(
                    "io.netty.allocator.cacheTrimIntervalMillis", 0);
        }

        // 是否为所有线程使用缓存

        DEFAULT_USE_CACHE_FOR_ALL_THREADS = SystemPropertyUtil.getBoolean(
                "io.netty.allocator.useCacheForAllThreads", true);

        // 默认使用1023，因为我们使用ArrayDeque作为后备存储，它将分配1024个元素的内部数组
        // 否则我们会分配2048个但只使用1024个，这是浪费的

        DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK = SystemPropertyUtil.getInt(
                "io.netty.allocator.maxCachedByteBuffersPerChunk", 1023);

        // 如果启用了调试日志，输出所有配置参数

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.allocator.numHeapArenas: {}", DEFAULT_NUM_HEAP_ARENA);
            logger.debug("-Dio.netty.allocator.numDirectArenas: {}", DEFAULT_NUM_DIRECT_ARENA);
            if (pageSizeFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE);
            } else {
                logger.debug("-Dio.netty.allocator.pageSize: {}", DEFAULT_PAGE_SIZE, pageSizeFallbackCause);
            }
            if (maxOrderFallbackCause == null) {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER);
            } else {
                logger.debug("-Dio.netty.allocator.maxOrder: {}", DEFAULT_MAX_ORDER, maxOrderFallbackCause);
            }
            logger.debug("-Dio.netty.allocator.chunkSize: {}", DEFAULT_PAGE_SIZE << DEFAULT_MAX_ORDER);
            logger.debug("-Dio.netty.allocator.smallCacheSize: {}", DEFAULT_SMALL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.normalCacheSize: {}", DEFAULT_NORMAL_CACHE_SIZE);
            logger.debug("-Dio.netty.allocator.maxCachedBufferCapacity: {}", DEFAULT_MAX_CACHED_BUFFER_CAPACITY);
            logger.debug("-Dio.netty.allocator.cacheTrimInterval: {}", DEFAULT_CACHE_TRIM_INTERVAL);
            logger.debug("-Dio.netty.allocator.cacheTrimIntervalMillis: {}", DEFAULT_CACHE_TRIM_INTERVAL_MILLIS);
            logger.debug("-Dio.netty.allocator.useCacheForAllThreads: {}", DEFAULT_USE_CACHE_FOR_ALL_THREADS);
            logger.debug("-Dio.netty.allocator.maxCachedByteBuffersPerChunk: {}",
                    DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK);
        }
    }

    // 默认的池化ByteBuf分配器实例，根据平台偏好选择直接内存或堆内存

    public static final PooledByteBufAllocator DEFAULT =
            new PooledByteBufAllocator(PlatformDependent.directBufferPreferred());

    // 堆内存arena数组，用于管理堆内存的分配

    private final PoolArena<byte[]>[] heapArenas;

    // 直接内存arena数组，用于管理直接内存的分配

    private final PoolArena<ByteBuffer>[] directArenas;

    // 小对象缓存大小

    private final int smallCacheSize;

    // 普通对象缓存大小

    private final int normalCacheSize;

    // 堆内存arena的监控指标列表

    private final List<PoolArenaMetric> heapArenaMetrics;

    // 直接内存arena的监控指标列表

    private final List<PoolArenaMetric> directArenaMetrics;

    // 线程本地缓存管理器

    private final PoolThreadLocalCache threadCache;

    // chunk大小，通常为16MB

    private final int chunkSize;

    // 分配器的监控指标

    private final PooledByteBufAllocatorMetric metric;

    /**
     * 创建一个新的池化ByteBuf分配器实例，使用默认配置
     * 默认不偏好直接内存
     */

    public PooledByteBufAllocator() {
        this(false);
    }

    /**
     * 创建一个新的池化ByteBuf分配器实例
     * 
     * @param preferDirect 是否偏好直接内存，true表示优先分配直接内存
     */

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(boolean preferDirect) {
        this(preferDirect, DEFAULT_NUM_HEAP_ARENA, DEFAULT_NUM_DIRECT_ARENA, DEFAULT_PAGE_SIZE, DEFAULT_MAX_ORDER);
    }

    /**
     * 创建一个新的池化ByteBuf分配器实例，指定arena数量和内存参数
     * 
     * @param nHeapArena 堆内存arena数量
     * @param nDirectArena 直接内存arena数量
     * @param pageSize 页面大小，必须是2的幂次方
     * @param maxOrder 最大阶数，决定chunk大小
     */

    @SuppressWarnings("deprecation")
    public PooledByteBufAllocator(int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(false, nHeapArena, nDirectArena, pageSize, maxOrder);
    }

    /**
     * 已弃用的构造函数
     * 
     * @deprecated 使用 {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             0, DEFAULT_SMALL_CACHE_SIZE, DEFAULT_NORMAL_CACHE_SIZE);
    }

    /**
     * 已弃用的构造函数
     * 
     * @deprecated 使用 {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             DEFAULT_USE_CACHE_FOR_ALL_THREADS, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * 已弃用的构造函数
     * 
     * @deprecated 使用 {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean)}
     */

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder, int tinyCacheSize,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads);
    }

    /**
     * 创建一个新的池化ByteBuf分配器实例，指定缓存配置
     * 
     * @param preferDirect 是否偏好直接内存
     * @param nHeapArena 堆内存arena数量
     * @param nDirectArena 直接内存arena数量
     * @param pageSize 页面大小
     * @param maxOrder 最大阶数
     * @param smallCacheSize 小对象缓存大小
     * @param normalCacheSize 普通对象缓存大小
     * @param useCacheForAllThreads 是否为所有线程启用缓存
     */

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena,
                                  int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, DEFAULT_DIRECT_MEMORY_CACHE_ALIGNMENT);
    }

    /**
     * 已弃用的构造函数
     * 
     * @deprecated 使用 {@link PooledByteBufAllocator#PooledByteBufAllocator(boolean, int, int, int, int, int, int, boolean, int)}
     */

    @Deprecated
    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        this(preferDirect, nHeapArena, nDirectArena, pageSize, maxOrder,
             smallCacheSize, normalCacheSize,
             useCacheForAllThreads, directMemoryCacheAlignment);
    }

    /**
     * 创建一个新的池化ByteBuf分配器实例，完整配置版本
     * 
     * @param preferDirect 是否偏好直接内存
     * @param nHeapArena 堆内存arena数量
     * @param nDirectArena 直接内存arena数量
     * @param pageSize 页面大小，必须是2的幂次方且不小于4096
     * @param maxOrder 最大阶数，决定chunk大小（pageSize << maxOrder）
     * @param smallCacheSize 小对象缓存大小
     * @param normalCacheSize 普通对象缓存大小
     * @param useCacheForAllThreads 是否为所有线程启用缓存
     * @param directMemoryCacheAlignment 直接内存缓存对齐大小
     */

    public PooledByteBufAllocator(boolean preferDirect, int nHeapArena, int nDirectArena, int pageSize, int maxOrder,
                                  int smallCacheSize, int normalCacheSize,
                                  boolean useCacheForAllThreads, int directMemoryCacheAlignment) {
        super(preferDirect);

        // 初始化线程本地缓存管理器

        threadCache = new PoolThreadLocalCache(useCacheForAllThreads);
        this.smallCacheSize = smallCacheSize;
        this.normalCacheSize = normalCacheSize;

        // 如果指定了直接内存对齐，需要验证平台支持

        if (directMemoryCacheAlignment != 0) {
            if (!PlatformDependent.hasAlignDirectByteBuffer()) {
                throw new UnsupportedOperationException("Buffer alignment is not supported. " +
                        "Either Unsafe or ByteBuffer.alignSlice() must be available.");
            }

            // 确保页面大小是对齐大小的整数倍，或者调整到下一个整数倍

            pageSize = (int) PlatformDependent.align(pageSize, directMemoryCacheAlignment);
        }

        // 验证并计算chunk大小

        chunkSize = validateAndCalculateChunkSize(pageSize, maxOrder);

        // 验证参数的有效性

        checkPositiveOrZero(nHeapArena, "nHeapArena");
        checkPositiveOrZero(nDirectArena, "nDirectArena");

        checkPositiveOrZero(directMemoryCacheAlignment, "directMemoryCacheAlignment");
        if (directMemoryCacheAlignment > 0 && !isDirectMemoryCacheAlignmentSupported()) {
            throw new IllegalArgumentException("directMemoryCacheAlignment is not supported");
        }

        // 验证对齐大小必须是2的幂次方

        if ((directMemoryCacheAlignment & -directMemoryCacheAlignment) != directMemoryCacheAlignment) {
            throw new IllegalArgumentException("directMemoryCacheAlignment: "
                    + directMemoryCacheAlignment + " (expected: power of two)");
        }

        // 计算页面位移值，用于快速计算

        int pageShifts = validateAndCalculatePageShifts(pageSize, directMemoryCacheAlignment);

        // 初始化堆内存arena

        if (nHeapArena > 0) {
            heapArenas = newArenaArray(nHeapArena);
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(heapArenas.length);
            for (int i = 0; i < heapArenas.length; i ++) {
                PoolArena.HeapArena arena = new PoolArena.HeapArena(this,
                        pageSize, pageShifts, chunkSize,
                        directMemoryCacheAlignment);
                heapArenas[i] = arena;
                metrics.add(arena);
            }
            heapArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            heapArenas = null;
            heapArenaMetrics = Collections.emptyList();
        }

        // 初始化直接内存arena

        if (nDirectArena > 0) {
            directArenas = newArenaArray(nDirectArena);
            List<PoolArenaMetric> metrics = new ArrayList<PoolArenaMetric>(directArenas.length);
            for (int i = 0; i < directArenas.length; i ++) {
                PoolArena.DirectArena arena = new PoolArena.DirectArena(
                        this, pageSize, pageShifts, chunkSize, directMemoryCacheAlignment);
                directArenas[i] = arena;
                metrics.add(arena);
            }
            directArenaMetrics = Collections.unmodifiableList(metrics);
        } else {
            directArenas = null;
            directArenaMetrics = Collections.emptyList();
        }

        // 初始化监控指标

        metric = new PooledByteBufAllocatorMetric(this);
    }

    /**
     * 创建指定大小的arena数组
     * 
     * @param size 数组大小
     * @return 新创建的arena数组
     */

    @SuppressWarnings("unchecked")
    private static <T> PoolArena<T>[] newArenaArray(int size) {
        return new PoolArena[size];
    }

    /**
     * 验证页面大小和对齐参数，并计算页面位移值
     * 
     * @param pageSize 页面大小
     * @param alignment 对齐大小
     * @return 页面位移值，用于快速计算
     */

    private static int validateAndCalculatePageShifts(int pageSize, int alignment) {
        // 页面大小不能小于最小值4096

        if (pageSize < MIN_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: " + MIN_PAGE_SIZE + ')');
        }

        // 页面大小必须是2的幂次方

        if ((pageSize & pageSize - 1) != 0) {
            throw new IllegalArgumentException("pageSize: " + pageSize + " (expected: power of 2)");
        }

        // 对齐大小不能大于页面大小

        if (pageSize < alignment) {
            throw new IllegalArgumentException("Alignment cannot be greater than page size. " +
                    "Alignment: " + alignment + ", page size: " + pageSize + '.');
        }

        // 计算以2为底的对数，此时我们知道pageSize是2的幂次方

        return Integer.SIZE - 1 - Integer.numberOfLeadingZeros(pageSize);
    }

    /**
     * 验证并计算chunk大小
     * 
     * @param pageSize 页面大小
     * @param maxOrder 最大阶数
     * @return 计算出的chunk大小
     */

    private static int validateAndCalculateChunkSize(int pageSize, int maxOrder) {
        // 最大阶数不能超过14

        if (maxOrder > 14) {
            throw new IllegalArgumentException("maxOrder: " + maxOrder + " (expected: 0-14)");
        }

        // 确保结果chunk大小不会溢出

        int chunkSize = pageSize;
        for (int i = maxOrder; i > 0; i --) {
            if (chunkSize > MAX_CHUNK_SIZE / 2) {
                throw new IllegalArgumentException(String.format(
                        "pageSize (%d) << maxOrder (%d) must not exceed %d", pageSize, maxOrder, MAX_CHUNK_SIZE));
            }
            chunkSize <<= 1;
        }
        return chunkSize;
    }

    /**
     * 分配新的堆内存ByteBuf
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 新分配的ByteBuf实例
     */

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        // 获取当前线程的缓存

        PoolThreadCache cache = threadCache.get();
        PoolArena<byte[]> heapArena = cache.heapArena;

        final ByteBuf buf;
        if (heapArena != null) {
            // 从arena中分配

            buf = heapArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            // 如果没有arena，直接创建非池化的buffer

            buf = PlatformDependent.hasUnsafe() ?
                    new UnpooledUnsafeHeapByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
        }

        // 包装为可检测内存泄漏的buffer

        return toLeakAwareBuffer(buf);
    }

    /**
     * 分配新的直接内存ByteBuf
     * 
     * @param initialCapacity 初始容量
     * @param maxCapacity 最大容量
     * @return 新分配的ByteBuf实例
     */

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        // 获取当前线程的缓存

        PoolThreadCache cache = threadCache.get();
        PoolArena<ByteBuffer> directArena = cache.directArena;

        final ByteBuf buf;
        if (directArena != null) {
            // 从arena中分配

            buf = directArena.allocate(cache, initialCapacity, maxCapacity);
        } else {
            // 如果没有arena，直接创建非池化的buffer

            buf = PlatformDependent.hasUnsafe() ?
                    UnsafeByteBufUtil.newUnsafeDirectByteBuf(this, initialCapacity, maxCapacity) :
                    new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
        }

        // 包装为可检测内存泄漏的buffer

        return toLeakAwareBuffer(buf);
    }

    /**
     * 获取默认堆内存arena数量
     * 系统属性：io.netty.allocator.numHeapArenas - 默认为CPU核心数的2倍
     * 
     * @return 默认堆内存arena数量
     */

    public static int defaultNumHeapArena() {
        return DEFAULT_NUM_HEAP_ARENA;
    }

    /**
     * 获取默认直接内存arena数量
     * 系统属性：io.netty.allocator.numDirectArenas - 默认为CPU核心数的2倍
     * 
     * @return 默认直接内存arena数量
     */

    public static int defaultNumDirectArena() {
        return DEFAULT_NUM_DIRECT_ARENA;
    }

    /**
     * 获取默认buffer页面大小
     * 系统属性：io.netty.allocator.pageSize - 默认8192字节
     * 
     * @return 默认页面大小
     */

    public static int defaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * 获取默认最大阶数
     * 系统属性：io.netty.allocator.maxOrder - 默认11
     * 
     * @return 默认最大阶数
     */

    public static int defaultMaxOrder() {
        return DEFAULT_MAX_ORDER;
    }

    /**
     * 获取默认线程缓存行为
     * 系统属性：io.netty.allocator.useCacheForAllThreads - 默认true
     * 
     * @return 是否为所有线程启用缓存
     */

    public static boolean defaultUseCacheForAllThreads() {
        return DEFAULT_USE_CACHE_FOR_ALL_THREADS;
    }

    /**
     * 获取默认偏好直接内存设置
     * 系统属性：io.netty.noPreferDirect - 默认false
     * 
     * @return 是否偏好直接内存
     */

    public static boolean defaultPreferDirect() {
        return PlatformDependent.directBufferPreferred();
    }

    /**
     * 获取默认tiny缓存大小 - 默认0
     * 
     * @deprecated Tiny缓存已经合并到small缓存中
     * @return 默认tiny缓存大小
     */

    @Deprecated
    public static int defaultTinyCacheSize() {
        return 0;
    }

    /**
     * 获取默认small缓存大小
     * 系统属性：io.netty.allocator.smallCacheSize - 默认256
     * 
     * @return 默认small缓存大小
     */

    public static int defaultSmallCacheSize() {
        return DEFAULT_SMALL_CACHE_SIZE;
    }

    /**
     * 获取默认normal缓存大小
     * 系统属性：io.netty.allocator.normalCacheSize - 默认64
     * 
     * @return 默认normal缓存大小
     */

    public static int defaultNormalCacheSize() {
        return DEFAULT_NORMAL_CACHE_SIZE;
    }

    /**
     * 检查是否支持直接内存缓存对齐
     * 
     * @return 如果支持直接内存缓存对齐返回true，否则返回false
     */

    public static boolean isDirectMemoryCacheAlignmentSupported() {
        return PlatformDependent.hasUnsafe();
    }

    /**
     * 检查直接内存buffer是否使用了池化
     * 
     * @return 如果直接内存buffer使用了池化返回true，否则返回false
     */

    @Override
    public boolean isDirectBufferPooled() {
        return directArenas != null;
    }

    /**
     * 检查调用线程是否有ThreadLocal缓存用于分配的buffer
     * 
     * @deprecated 此方法已弃用
     * @return 如果调用线程有ThreadLocal缓存返回true，否则返回false
     */

    @Deprecated
    public boolean hasThreadLocalCache() {
        return threadCache.isSet();
    }

    /**
     * 释放调用线程的所有缓存buffer
     * 
     * @deprecated 此方法已弃用
     */

    @Deprecated
    public void freeThreadLocalCache() {
        threadCache.remove();
    }

    /**
     * 池化线程本地缓存管理器
     * 
     * 这个内部类负责管理每个线程的本地缓存，包括：
     * 1. 为每个线程分配合适的arena
     * 2. 创建和管理线程本地缓存
     * 3. 定期清理不常用的缓存
     */

    final class PoolThreadLocalCache extends FastThreadLocal<PoolThreadCache> {
        // 是否为所有线程使用缓存

        private final boolean useCacheForAllThreads;

        /**
         * 构造函数
         * 
         * @param useCacheForAllThreads 是否为所有线程启用缓存
         */

        PoolThreadLocalCache(boolean useCacheForAllThreads) {
            this.useCacheForAllThreads = useCacheForAllThreads;
        }

        /**
         * 为当前线程初始化缓存
         * 
         * @return 新创建的PoolThreadCache实例
         */

        @Override
        protected synchronized PoolThreadCache initialValue() {
            // 选择使用最少的arena来平衡负载

            final PoolArena<byte[]> heapArena = leastUsedArena(heapArenas);
            final PoolArena<ByteBuffer> directArena = leastUsedArena(directArenas);

            final Thread current = Thread.currentThread();

            // 只有在启用了全线程缓存或者当前线程是FastThreadLocalThread时才使用缓存

            if (useCacheForAllThreads || current instanceof FastThreadLocalThread) {
                final PoolThreadCache cache = new PoolThreadCache(
                        heapArena, directArena, smallCacheSize, normalCacheSize,
                        DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

                // 如果设置了缓存清理时间间隔，启动定期清理任务

                if (DEFAULT_CACHE_TRIM_INTERVAL_MILLIS > 0) {
                    final EventExecutor executor = ThreadExecutorMap.currentExecutor();
                    if (executor != null) {
                        executor.scheduleAtFixedRate(trimTask, DEFAULT_CACHE_TRIM_INTERVAL_MILLIS,
                                DEFAULT_CACHE_TRIM_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    }
                }
                return cache;
            }

            // 不使用缓存，所以缓存大小都设为0

            return new PoolThreadCache(heapArena, directArena, 0, 0, 0, 0);
        }

        /**
         * 当线程本地缓存被移除时调用
         * 
         * @param threadCache 要移除的线程缓存
         */

        @Override
        protected void onRemoval(PoolThreadCache threadCache) {
            threadCache.free(false);
        }

        /**
         * 从arena数组中选择使用最少的arena
         * 
         * @param arenas arena数组
         * @return 使用最少的arena，如果数组为空则返回null
         */

        private <T> PoolArena<T> leastUsedArena(PoolArena<T>[] arenas) {
            if (arenas == null || arenas.length == 0) {
                return null;
            }

            PoolArena<T> minArena = arenas[0];
            for (int i = 1; i < arenas.length; i++) {
                PoolArena<T> arena = arenas[i];
                if (arena.numThreadCaches.get() < minArena.numThreadCaches.get()) {
                    minArena = arena;
                }
            }

            return minArena;
        }
    }

    /**
     * 获取分配器的监控指标
     * 
     * @return PooledByteBufAllocatorMetric实例
     */

    @Override
    public PooledByteBufAllocatorMetric metric() {
        return metric;
    }

    /**
     * 获取堆内存arena数量
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#numHeapArenas()}
     * @return 堆内存arena数量
     */

    @Deprecated
    public int numHeapArenas() {
        return heapArenaMetrics.size();
    }

    /**
     * 获取直接内存arena数量
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#numDirectArenas()}
     * @return 直接内存arena数量
     */

    @Deprecated
    public int numDirectArenas() {
        return directArenaMetrics.size();
    }

    /**
     * 获取所有堆内存PoolArenaMetric的列表
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#heapArenas()}
     * @return 堆内存arena监控指标列表
     */

    @Deprecated
    public List<PoolArenaMetric> heapArenas() {
        return heapArenaMetrics;
    }

    /**
     * 获取所有直接内存PoolArenaMetric的列表
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#directArenas()}
     * @return 直接内存arena监控指标列表
     */

    @Deprecated
    public List<PoolArenaMetric> directArenas() {
        return directArenaMetrics;
    }

    /**
     * 获取此PooledByteBufAllocator使用的线程本地缓存数量
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#numThreadLocalCaches()}
     * @return 线程本地缓存数量
     */

    @Deprecated
    public int numThreadLocalCaches() {
        PoolArena<?>[] arenas = heapArenas != null ? heapArenas : directArenas;
        if (arenas == null) {
            return 0;
        }

        int total = 0;
        for (PoolArena<?> arena : arenas) {
            total += arena.numThreadCaches.get();
        }

        return total;
    }

    /**
     * 获取tiny缓存的大小
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#tinyCacheSize()}
     * @return tiny缓存大小
     */

    @Deprecated
    public int tinyCacheSize() {
        return 0;
    }

    /**
     * 获取small缓存的大小
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#smallCacheSize()}
     * @return small缓存大小
     */

    @Deprecated
    public int smallCacheSize() {
        return smallCacheSize;
    }

    /**
     * 获取normal缓存的大小
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#normalCacheSize()}
     * @return normal缓存大小
     */

    @Deprecated
    public int normalCacheSize() {
        return normalCacheSize;
    }

    /**
     * 获取arena的chunk大小
     * 
     * @deprecated 使用 {@link PooledByteBufAllocatorMetric#chunkSize()}
     * @return chunk大小
     */

    @Deprecated
    public final int chunkSize() {
        return chunkSize;
    }

    /**
     * 获取已使用的堆内存大小
     * 
     * @return 已使用的堆内存字节数
     */

    final long usedHeapMemory() {
        return usedMemory(heapArenas);
    }

    /**
     * 获取已使用的直接内存大小
     * 
     * @return 已使用的直接内存字节数
     */

    final long usedDirectMemory() {
        return usedMemory(directArenas);
    }

    /**
     * 计算指定arena数组的已使用内存
     * 
     * @param arenas arena数组
     * @return 已使用的内存字节数，如果arenas为null则返回-1
     */

    private static long usedMemory(PoolArena<?>[] arenas) {
        if (arenas == null) {
            return -1;
        }
        long used = 0;
        for (PoolArena<?> arena : arenas) {
            used += arena.numActiveBytes();
            if (used < 0) {
                return Long.MAX_VALUE;
            }
        }
        return used;
    }

    /**
     * 获取当前线程的缓存
     * 
     * @return 当前线程的PoolThreadCache实例
     */

    final PoolThreadCache threadCache() {
        PoolThreadCache cache =  threadCache.get();
        assert cache != null;
        return cache;
    }

    /**
     * 清理当前线程的线程本地缓存，这将释放自上次清理操作以来不经常使用的任何缓存内存
     * 
     * @return 如果当前线程存在缓存并且被清理了返回true，否则返回false
     */

    public boolean trimCurrentThreadCache() {
        PoolThreadCache cache = threadCache.getIfExists();
        if (cache != null) {
            cache.trim();
            return true;
        }
        return false;
    }

    /**
     * 返回分配器的状态（包含所有指标）的字符串表示
     * 注意这可能很昂贵，所以不应该太频繁地调用
     * 
     * @return 包含详细统计信息的字符串
     */

    public String dumpStats() {
        int heapArenasLen = heapArenas == null ? 0 : heapArenas.length;
        StringBuilder buf = new StringBuilder(512)
                .append(heapArenasLen)
                .append(" heap arena(s):")
                .append(StringUtil.NEWLINE);
        if (heapArenasLen > 0) {
            for (PoolArena<byte[]> a: heapArenas) {
                buf.append(a);
            }
        }

        int directArenasLen = directArenas == null ? 0 : directArenas.length;

        buf.append(directArenasLen)
           .append(" direct arena(s):")
           .append(StringUtil.NEWLINE);
        if (directArenasLen > 0) {
            for (PoolArena<ByteBuffer> a: directArenas) {
                buf.append(a);
            }
        }

        return buf.toString();
    }
}
