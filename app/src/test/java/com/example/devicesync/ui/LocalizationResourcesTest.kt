package com.example.devicesync.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    @Test
    fun englishResourcesMatchDefaultResourceKeys() {
        val defaults = keys(File("src/main/res/values/strings.xml"))
        val english = keys(File("src/main/res/values-en/strings.xml"))

        assertTrue("Default strings must not be empty", defaults.isNotEmpty())
        assertEquals(defaults, english)
    }

    private fun keys(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return (0 until document.getElementsByTagName("string").length)
            .map { document.getElementsByTagName("string").item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
    }
}
