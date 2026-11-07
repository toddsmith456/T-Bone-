package social.tbone.notes

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class NoteAudioSourceTest {
    @Test fun readRangesAndEof() {
        val source = NoteAudioSource(byteArrayOf(1, 2, 3, 4))
        val buffer = ByteArray(5)
        assertEquals(2, source.readAt(2, buffer, 1, 4))
        assertArrayEquals(byteArrayOf(0, 3, 4, 0, 0), buffer)
        assertEquals(-1, source.readAt(4, buffer, 0, 1))
        assertEquals(4L, source.size)
        source.close()
    }

    @Test fun mediaPlayerCanPreparePcmWavFromMemory() {
        val samples = 8000
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()); wav.putInt(36 + samples * 2)
        wav.put("WAVEfmt ".toByteArray()); wav.putInt(16); wav.putShort(1); wav.putShort(1)
        wav.putInt(8000); wav.putInt(16000); wav.putShort(2); wav.putShort(16)
        wav.put("data".toByteArray()); wav.putInt(samples * 2)
        val player = MediaPlayer()
        try {
            player.setDataSource(NoteAudioSource(wav.array()))
            player.prepare()
            assertTrue(player.duration in 900..1100)
        } finally { player.release() }
    }
}
