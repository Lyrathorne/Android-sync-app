package com.example.devicesync.core.catalog

import android.util.LruCache

class BoundedThumbnailCache(maxBytes: Int = 8 * 1024 * 1024) {
    private val cache = object : LruCache<String, CatalogThumbnail>(maxBytes) {
        override fun sizeOf(key: String, value: CatalogThumbnail): Int = value.bytes.size
    }

    @Synchronized fun get(key: String): CatalogThumbnail? = cache.get(key)
    @Synchronized fun put(key: String, value: CatalogThumbnail) { cache.put(key, value) }
    @Synchronized fun clear() { cache.evictAll() }
}
