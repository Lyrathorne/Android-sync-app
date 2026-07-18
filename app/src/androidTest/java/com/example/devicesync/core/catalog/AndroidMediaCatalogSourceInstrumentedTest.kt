package com.example.devicesync.core.catalog

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.devicesync.core.protocol.CatalogQueryPayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaCatalogSourceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var insertedUri: android.net.Uri? = null

    @Before
    fun grantReadPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(permission)
        CatalogAccessStore(context).setCatalogEnabled(true)
    }

    @After
    fun cleanup() {
        insertedUri?.let { context.contentResolver.delete(it, null, null) }
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        CatalogAccessStore(context).revokeAll()
    }

    @Test
    fun insertedTestImage_isPagedAndThumbnailIsBounded() = runBlocking {
        val uniqueName = "devicesync-catalog-${System.nanoTime()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, uniqueName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DeviceSyncTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        insertedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val uri = checkNotNull(insertedUri)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }

        val source = AndroidMediaCatalogSource(context)
        val page = source.query(CatalogQueryPayload("instrumented", 10, categories = listOf("image"), search = uniqueName))
        val item = page.items.single { it.displayName == uniqueName }
        assertFalse(item.itemId.contains("content://"))
        assertEquals("image", item.category)

        val thumbnail = source.thumbnail(item.itemId, item.revision, 64, 64, "jpeg", 80)
        assertTrue(thumbnail.width <= 64)
        assertTrue(thumbnail.height <= 64)
        assertTrue(thumbnail.bytes.size <= 256 * 1024)
    }
}
