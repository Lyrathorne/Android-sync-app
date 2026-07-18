package com.example.devicesync.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardEmojiCatalogTest {
    @Test fun catalogContainsAllDailyCategoriesAndHundredsOfEmoji() {
        assertEquals(13, EmojiCategory.entries.size)
        assertTrue(KeyboardEmojiCatalog.totalCount() >= 700)
        EmojiCategory.entries.filterNot {
            it in setOf(EmojiCategory.RECENT, EmojiCategory.FAVORITES, EmojiCategory.SEARCH)
        }.forEach { category ->
            assertTrue("Empty emoji category: $category", KeyboardEmojiCatalog.items(category, emptyList()).isNotEmpty())
        }
    }

    @Test fun recentCategoryUsesStoredOrderWithoutMixingCatalog() {
        val recent = listOf("🚀", "❤️", "😀")
        assertEquals(recent, KeyboardEmojiCatalog.items(EmojiCategory.RECENT, recent))
    }

    @Test fun favoritesAndQuickSearchAreExposed() {
        val favorites = listOf("❤️", "🚀")
        assertEquals(favorites, KeyboardEmojiCatalog.items(EmojiCategory.FAVORITES, emptyList(), favorites))
        assertTrue(KeyboardEmojiCatalog.searchOptions().contains("Животные"))
        assertTrue(KeyboardEmojiCatalog.search("кот").contains("🐱"))
        assertTrue(KeyboardEmojiCatalog.search("сердце").contains("❤️"))
    }
}
