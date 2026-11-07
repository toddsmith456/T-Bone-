package social.tbone.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMetadataTest {
    @Test fun sourceGpsExifIsAbsentFromReencodedImage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("metadata-image", ".jpg", context.cacheDir)
        var output: File? = null
        try {
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(source.absolutePath).apply { setLatLong(41.8, -87.6); saveAttributes() }
            assertNotNull(ExifInterface(source.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            val prepared = checkNotNull(MediaProcessor.prepareImageMedia(context, Uri.fromFile(source)))
            output = prepared.file
            assertNull(ExifInterface(prepared.file.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(ExifInterface(prepared.file.absolutePath).getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        } finally { source.delete(); output?.delete() }
    }

    @Test fun uncompressedAudioAndVideoKeepOriginalBytesIncludingMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = File.createTempFile("metadata-audio", ".mp3", context.cacheDir)
        val video = File.createTempFile("metadata-video", ".mp4", context.cacheDir)
        val metadata = "ID3 or container location metadata: private".toByteArray()
        audio.writeBytes(metadata); video.writeBytes(metadata)
        var videoCopy: File? = null
        try {
            val audioOutput = checkNotNull(MediaProcessor.prepareAudioMedia(context, Uri.fromFile(audio), compress = false))
            val videoOutput = checkNotNull(MediaProcessor.prepareVideoMedia(context, Uri.fromFile(video), compress = false))
            videoCopy = videoOutput.file
            assertArrayEquals(metadata, audioOutput.file.readBytes())
            assertArrayEquals(metadata, videoOutput.file.readBytes())
        } finally { audio.delete(); video.delete(); videoCopy?.delete() }
    }
}
