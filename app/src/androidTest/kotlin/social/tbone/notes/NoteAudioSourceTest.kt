package social.tbone.notes

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import social.tbone.toolbox.VoiceRecordingGate

@RunWith(AndroidJUnit4::class)
class NoteAudioSourceTest {
    @Test fun readRangesAndEof() {
        val source = NoteAudioSource(byteArrayOf(1, 2, 3, 4))
        val buffer = ByteArray(5)
        assertEquals(2, source.readAt(2, buffer, 1, 4))
        assertArrayEquals(byteArrayOf(0, 3, 4, 0, 0), buffer)
        assertEquals(-1, source.readAt(4, buffer, 0, 1))
        assertEquals(4L, source.getSize())
        source.close()
    }

    @Test fun wipeReleasesActualInMemoryPlayback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writes = NoteWriteCoordinator(scope)
        val gate = VoiceRecordingGate(writes, scope)
        val token = Any()
        val epoch = writes.currentWipeEpoch()
        val samples = 8000 * 3
        val wav = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()); wav.putInt(36 + samples * 2)
        wav.put("WAVEfmt ".toByteArray()); wav.putInt(16); wav.putShort(1); wav.putShort(1)
        wav.putInt(8000); wav.putInt(16000); wav.putShort(2); wav.putShort(16)
        wav.put("data".toByteArray()); wav.putInt(samples * 2)
        var released = false
        try {
            assertTrue(gate.startPlayback(token, epoch) { register ->
                var player: MediaPlayer? = null
                register { player?.release(); released = true }
                val active = MediaPlayer()
                player = active
                active.setDataSource(NoteAudioSource(wav.array()))
                active.prepare()
                active.start()
            })
            assertTrue(writes.requestWipe(NoteWriteCoordinator.WipeKind.TOOLBOX))
            gate.stopForWipe()
            assertTrue(released)
            assertFalse(gate.startPlayback(Any(), epoch) { fail("stale playback started") })
        } finally {
            gate.stopForWipe()
            scope.cancel()
        }
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
