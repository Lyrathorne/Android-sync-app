package com.example.devicesync.core.catalog

import android.content.Context
import android.net.Uri

class CatalogAccessStore(context: Context) {
    private val preferences = context.getSharedPreferences("media_catalog_access", Context.MODE_PRIVATE)

    fun selectedTreeUris(): Set<String> = preferences.getStringSet(KEY_TREES, emptySet()).orEmpty()
    fun isCatalogEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)
    fun setCatalogEnabled(enabled: Boolean) { preferences.edit().putBoolean(KEY_ENABLED, enabled).apply() }
    fun isDocumentsAccessEnabled(): Boolean =
        preferences.getBoolean(KEY_DOCUMENTS_ENABLED, selectedTreeUris().isNotEmpty())
    fun setDocumentsAccessEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DOCUMENTS_ENABLED, enabled).apply()
    }

    fun grantTree(uri: Uri) {
        preferences.edit()
            .putStringSet(KEY_TREES, selectedTreeUris() + uri.toString())
            .putBoolean(KEY_ENABLED, true)
            .putBoolean(KEY_DOCUMENTS_ENABLED, true)
            .apply()
    }

    fun revokeTree(uri: Uri) {
        preferences.edit().putStringSet(KEY_TREES, selectedTreeUris() - uri.toString()).apply()
    }

    fun revokeAll() {
        preferences.edit().remove(KEY_TREES)
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_DOCUMENTS_ENABLED, false)
            .apply()
    }

    private companion object {
        const val KEY_TREES = "selected_tree_uris"
        const val KEY_ENABLED = "catalog_enabled"
        const val KEY_DOCUMENTS_ENABLED = "documents_access_enabled"
    }
}
