/*
 * Copyright 2020 The Netty Project
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
 * 内部原始类型映射实现，专门为 PoolChunk 中的运行可用性映射用例进行了优化。
 * 
 * 这是一个高性能的 long 到 long 的哈希映射，使用开放寻址法和线性探测来解决哈希冲突。
 * 
 * 主要特点：
 * 1. 专门针对 Netty 内存池的使用场景进行优化
 * 2. 使用开放寻址法，避免了链表的内存开销
 * 3. 对 key 为 0 的情况进行特殊处理
 * 4. 支持动态扩容以保持良好的性能
 */
final class LongLongHashMap {

    /**
     * 掩码模板，用于确保数组长度为偶数（最低位为0）
     * 
     * 因为我们使用成对存储（key, value），所以数组长度必须是偶数
     */
    private static final int MASK_TEMPLATE = ~1;

    /**
     * 位掩码，用于将哈希值映射到数组索引范围内
     * 
     * 通过 hash & mask 操作快速计算数组索引
     */
    private int mask;

    /**
     * 存储键值对的数组
     * 
     * 数组中相邻的两个元素组成一个键值对：
     * - array[i] 存储 key
     * - array[i+1] 存储 value
     */
    private long[] array;

    /**
     * 最大探测距离
     * 
     * 当发生哈希冲突时，最多向前探测多少个位置
     * 这个值基于数组长度的对数计算，平衡了性能和成功率
     */
    private int maxProbe;

    /**
     * 当 key 为 0 时对应的值
     * 
     * 由于我们使用 0 作为数组中的空标记，所以需要单独存储 key=0 的情况
     */
    private long zeroVal;

    /**
     * 表示空值的常量
     * 
     * 当查找不到对应的 key 时返回此值
     */
    private final long emptyVal;

    /**
     * 构造函数
     * 
     * @param emptyVal 空值标记，当查找不到对应key时返回此值
     */
    LongLongHashMap(long emptyVal) {
        this.emptyVal = emptyVal;

        // 初始化时，key=0 对应的值也是空值
        zeroVal = emptyVal;

        // 初始数组大小为32，必须是2的幂次方以便使用位运算优化
        int initialSize = 32;
        array = new long[initialSize];
        mask = initialSize - 1;

        // 计算掩码和最大探测距离
        computeMaskAndProbe();
    }

    /**
     * 插入或更新键值对
     * 
     * @param key 要插入的键
     * @param value 要插入的值
     * @return 如果key已存在，返回旧值；否则返回emptyVal
     */
    public long put(long key, long value) {
        // 特殊处理 key = 0 的情况
        if (key == 0) {
            long prev = zeroVal;
            zeroVal = value;
            return prev;
        }

        // 无限循环，直到成功插入或需要扩容
        for (;;) {
            // 计算key的哈希索引
            int index = index(key);

            // 线性探测，寻找合适的插入位置
            for (int i = 0; i < maxProbe; i++) {
                long existing = array[index];

                // 找到相同的key或空位置
                if (existing == key || existing == 0) {
                    // 获取旧值：如果是空位置则返回emptyVal，否则返回实际存储的值
                    long prev = existing == 0 ? emptyVal : array[index + 1];

                    // 存储新的键值对
                    array[index] = key;
                    array[index + 1] = value;

                    // 清理可能存在的重复条目
                    // 这是为了处理在扩容重新哈希过程中可能产生的重复项
                    for (; i < maxProbe; i++) {
                        index = index + 2 & mask;
                        if (array[index] == key) {
                            array[index] = 0;
                            prev = array[index + 1];
                            break;
                        }
                    }
                    return prev;
                }

                // 移动到下一个位置（跳过2个元素，因为是成对存储）
                index = index + 2 & mask;
            }

            // 如果探测完所有位置都没找到合适位置，则需要扩容
            expand();
        }
    }

    /**
     * 删除指定键的条目
     * 
     * @param key 要删除的键
     */
    public void remove(long key) {
        // 特殊处理 key = 0 的情况
        if (key == 0) {
            zeroVal = emptyVal;
            return;
        }

        // 计算key的哈希索引
        int index = index(key);

        // 线性探测寻找要删除的key
        for (int i = 0; i < maxProbe; i++) {
            long existing = array[index];

            // 找到匹配的key
            if (existing == key) {
                // 将key位置设为0表示删除（值位置不需要清理）
                array[index] = 0;
                break;
            }

            // 移动到下一个位置
            index = index + 2 & mask;
        }
    }

    /**
     * 获取指定键对应的值
     * 
     * @param key 要查找的键
     * @return 对应的值，如果不存在则返回emptyVal
     */
    public long get(long key) {
        // 特殊处理 key = 0 的情况
        if (key == 0) {
            return zeroVal;
        }

        // 计算key的哈希索引
        int index = index(key);

        // 线性探测寻找key
        for (int i = 0; i < maxProbe; i++) {
            long existing = array[index];

            // 找到匹配的key
            if (existing == key) {
                return array[index + 1];
            }

            // 移动到下一个位置
            index = index + 2 & mask;
        }

        // 没找到，返回空值
        return emptyVal;
    }

    /**
     * 计算key的哈希索引
     * 
     * 使用MurmurHash64算法的变种来计算哈希值，然后通过位掩码映射到数组索引
     * 
     * @param key 要计算哈希的键
     * @return 数组索引（总是偶数，因为键值对成对存储）
     */
    private int index(long key) {
        // MurmurHash64 算法实现
        // 这是一个高质量的哈希函数，能够很好地分散哈希值
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;

        // 使用位掩码确保索引在有效范围内，并且是偶数
        return (int) key & mask;
    }

    /**
     * 扩容数组并重新哈希所有元素
     * 
     * 当哈希表装载因子过高时调用此方法，将数组大小翻倍
     */
    private void expand() {
        // 保存旧数组
        long[] prev = array;

        // 创建新数组，大小翻倍
        array = new long[prev.length * 2];

        // 重新计算掩码和最大探测距离
        computeMaskAndProbe();

        // 将旧数组中的所有键值对重新插入到新数组中
        for (int i = 0; i < prev.length; i += 2) {
            long key = prev[i];

            // 只处理非空的键值对
            if (key != 0) {
                long val = prev[i + 1];
                put(key, val);
            }
        }
    }

    /**
     * 根据当前数组长度计算掩码和最大探测距离
     * 
     * 掩码用于快速计算数组索引
     * 最大探测距离基于数组长度的对数，平衡性能和成功率
     */
    private void computeMaskAndProbe() {
        int length = array.length;

        // 计算掩码：长度减1，并确保最低位为0（保证索引为偶数）
        mask = length - 1 & MASK_TEMPLATE;

        // 最大探测距离设为数组长度的对数
        // 这是一个经验值，在大多数情况下能够找到合适的位置
        maxProbe = (int) Math.log(length);
    }
}
