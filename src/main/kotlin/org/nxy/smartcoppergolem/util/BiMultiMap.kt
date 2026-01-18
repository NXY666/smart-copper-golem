package org.nxy.smartcoppergolem.util

/**
 * 双向多对多映射
 * 
 * 特性：
 * - 一个 key 可以对应多个 values
 * - 一个 value 可以对应多个 keys
 * - 支持通过 key 查询所有对应的 values
 * - 支持通过 value 查询所有对应的 keys
 * 
 * @param K key 的类型
 * @param V value 的类型
 */
class BiMultiMap<K, V> {
    // key -> values 映射（一个 key 可能对应多个 value）
    private val keyToValues: MutableMap<K, MutableSet<V>> = mutableMapOf()

    // value -> keys 映射（一个 value 可能对应多个 key）
    private val valueToKeys: MutableMap<V, MutableSet<K>> = mutableMapOf()

    /**
     * 添加 key-value 映射关系
     * 如果这个 key-value 对已经存在，不会重复添加
     * 
     * @param key 键
     * @param value 值
     */
    fun put(key: K, value: V) {
        // 建立双向映射
        keyToValues.getOrPut(key) { mutableSetOf() }.add(value)
        valueToKeys.getOrPut(value) { mutableSetOf() }.add(key)
    }

    /**
     * 通过 key 获取所有对应的 values
     * 
     * @param key 键
     * @return 对应的所有值的集合，如果不存在返回空集合
     */
    fun getValuesByKey(key: K): Set<V> {
        return keyToValues[key]?.toSet() ?: emptySet()
    }

    /**
     * 通过 value 获取所有对应的 keys
     * 
     * @param value 值
     * @return 对应的所有键的集合，如果不存在返回空集合
     */
    fun getKeysByValue(value: V): Set<K> {
        return valueToKeys[value]?.toSet() ?: emptySet()
    }

    /**
     * 检查是否包含指定的 key
     * 
     * @param key 键
     * @return 是否包含该 key
     */
    fun containsKey(key: K): Boolean {
        return keyToValues.containsKey(key)
    }

    /**
     * 检查是否包含指定的 value
     * 
     * @param value 值
     * @return 是否包含该 value
     */
    fun containsValue(value: V): Boolean {
        return valueToKeys.containsKey(value)
    }

    /**
     * 检查是否包含指定的 key-value 对
     * 
     * @param key 键
     * @param value 值
     * @return 是否包含该映射关系
     */
    fun contains(key: K, value: V): Boolean {
        return keyToValues[key]?.contains(value) == true
    }

    /**
     * 移除指定的 key-value 对
     * 
     * @param key 键
     * @param value 值
     * @return 是否成功移除（如果映射不存在返回false）
     */
    fun remove(key: K, value: V): Boolean {
        val removed = keyToValues[key]?.remove(value) ?: false

        if (removed) {
            // 如果 key 不再有任何 value，移除这个 key
            if (keyToValues[key]?.isEmpty() == true) {
                keyToValues.remove(key)
            }

            // 同时移除反向映射
            valueToKeys[value]?.remove(key)
            if (valueToKeys[value]?.isEmpty() == true) {
                valueToKeys.remove(value)
            }
        }

        return removed
    }

    /**
     * 通过 key 移除所有相关的映射
     * 
     * @param key 键
     * @return 被移除的所有 values
     */
    fun removeByKey(key: K): Set<V> {
        val values = keyToValues.remove(key) ?: return emptySet()

        // 同时移除所有反向映射
        for (value in values) {
            valueToKeys[value]?.remove(key)
            if (valueToKeys[value]?.isEmpty() == true) {
                valueToKeys.remove(value)
            }
        }

        return values.toSet()
    }

    /**
     * 通过 value 移除所有相关的映射
     * 
     * @param value 值
     * @return 被移除的所有 keys
     */
    fun removeByValue(value: V): Set<K> {
        val keys = valueToKeys.remove(value) ?: return emptySet()

        // 同时移除所有正向映射
        for (key in keys) {
            keyToValues[key]?.remove(value)
            if (keyToValues[key]?.isEmpty() == true) {
                keyToValues.remove(key)
            }
        }

        return keys.toSet()
    }

    /**
     * 清空所有映射
     */
    fun clear() {
        keyToValues.clear()
        valueToKeys.clear()
    }

    /**
     * 获取所有的 keys
     */
    fun keys(): Set<K> {
        return keyToValues.keys.toSet()
    }

    /**
     * 获取所有的 values
     */
    fun values(): Set<V> {
        return valueToKeys.keys.toSet()
    }

    /**
     * 获取映射的大小（所有 key-value 对的总数）
     */
    fun size(): Int {
        return keyToValues.values.sumOf { it.size }
    }

    /**
     * 检查映射是否为空
     */
    fun isEmpty(): Boolean {
        return keyToValues.isEmpty()
    }

    /**
     * 对每个 key-value 对执行操作
     */
    fun forEach(action: (K, V) -> Unit) {
        keyToValues.forEach { (key, values) ->
            values.forEach { value ->
                action(key, value)
            }
        }
    }

    override fun toString(): String {
        return "BiMultiMap(keyToValues=$keyToValues, valueToKeys=$valueToKeys)"
    }
}
