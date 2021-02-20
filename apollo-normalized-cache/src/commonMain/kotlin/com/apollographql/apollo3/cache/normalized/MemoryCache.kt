package com.apollographql.apollo3.cache.normalized

import com.apollographql.apollo3.cache.ApolloCacheHeaders
import com.apollographql.apollo3.cache.CacheHeaders
import com.apollographql.apollo3.cache.normalized.internal.LruCache
import com.apollographql.apollo3.cache.normalized.internal.Platform
import okio.internal.commonAsUtf8ToByteArray
import kotlin.reflect.KClass

/**
 * Memory (multiplatform) cache implementation based on recently used property (LRU).
 *
 * [maxSizeBytes] - the maximum size of bytes the cache may occupy.
 * [expireAfterMillis] - after what timeout each entry in the cache treated as expired. By default there is no timeout.
 *
 * Expired entries removed from the cache only on cache miss ([loadRecord] operation) and not removed from the cache automatically
 * (there is no any sort of GC that runs in the background).
 */
class MemoryCache(
    private val maxSizeBytes: Int,
    private val expireAfterMillis: Long = -1,
) : NormalizedCache() {
  private val lruCache = LruCache<String, CacheEntry>(maxSize = maxSizeBytes) { key, cacheEntry ->
    key.commonAsUtf8ToByteArray().size + (cacheEntry?.sizeInBytes ?: 0)
  }

  val size: Int
    get() = lruCache.size()

  override fun loadRecord(key: String, cacheHeaders: CacheHeaders): Record? {
    val cacheEntry = lruCache[key]?.also { cacheEntry ->
      if (cacheEntry.isExpired || cacheHeaders.hasHeader(ApolloCacheHeaders.EVICT_AFTER_READ)) {
        lruCache.remove(key)
      }
    }

    return cacheEntry?.takeUnless { it.isExpired }?.record ?: nextCache?.loadRecord(key, cacheHeaders)?.also { nextCachedRecord ->
      lruCache[key] = CacheEntry(
          record = nextCachedRecord,
          expireAfterMillis = expireAfterMillis
      )
    }
  }

  override fun loadRecords(keys: Collection<String>, cacheHeaders: CacheHeaders): Collection<Record> {
    return keys.mapNotNull { key -> loadRecord(key, cacheHeaders) }
  }

  override fun clearAll() {
    lruCache.clear()
    nextCache?.clearAll()
  }

  override fun remove(cacheKey: CacheKey, cascade: Boolean): Boolean {
    val cacheEntry = lruCache.remove(cacheKey.key)

    if (cascade && cacheEntry != null) {
      for (cacheReference in cacheEntry.record.referencedFields()) {
        remove(CacheKey(cacheReference.key), true)
      }
    }

    return cacheEntry != null || nextCache?.remove(cacheKey, cascade) ?: false
  }

  override fun merge(record: Record, cacheHeaders: CacheHeaders): Set<String> {
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.DO_NOT_STORE)) {
      return emptySet()
    }

    val oldRecord = loadRecord(record.key, cacheHeaders)
    val changedKeys = if (oldRecord == null) {
      lruCache[record.key] = CacheEntry(
          record = record,
          expireAfterMillis = expireAfterMillis
      )
      record.fieldKeys()
    } else {
      val (mergedRecord, changedKeys) = oldRecord.mergeWith(record)
      lruCache[record.key] = CacheEntry(
          record = mergedRecord,
          expireAfterMillis = expireAfterMillis
      )
      changedKeys
    }

    return changedKeys + nextCache?.merge(record, cacheHeaders).orEmpty()
  }

  override fun merge(records: Collection<Record>, cacheHeaders: CacheHeaders): Set<String> {
    if (cacheHeaders.hasHeader(ApolloCacheHeaders.DO_NOT_STORE)) {
      return emptySet()
    }
    return records.flatMap { record -> merge(record, cacheHeaders) }.toSet()
  }

  override fun dump(): Map<KClass<*>, Map<String, Record>> {
    return mapOf(
        this::class to lruCache.dump().mapValues { (_, entry) -> entry.record }
    ) + nextCache?.dump().orEmpty()
  }

  internal fun clearCurrentCache() {
    lruCache.clear()
  }

  private class CacheEntry(
      val record: Record,
      val expireAfterMillis: Long
  ) {
    val cachedAtMillis: Long = Platform.currentTimeMillis()

    val sizeInBytes: Int = record.sizeInBytes + 8

    val isExpired: Boolean
      get() {
        return if (expireAfterMillis < 0) {
          false
        } else {
          Platform.currentTimeMillis() - cachedAtMillis >= expireAfterMillis
        }
      }
  }
}

class MemoryCacheFactory(
    private val maxSizeBytes: Int,
    private val expireAfterMillis: Long = -1,
) : NormalizedCacheFactory<MemoryCache>() {

  override fun create(): MemoryCache {
    return MemoryCache(
        maxSizeBytes = maxSizeBytes,
        expireAfterMillis = expireAfterMillis,
    )
  }
}