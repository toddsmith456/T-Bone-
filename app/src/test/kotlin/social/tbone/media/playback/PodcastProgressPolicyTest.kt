package social.tbone.media.playback

import org.junit.Assert.*
import org.junit.Test

class PodcastProgressPolicyTest {
    @Test fun resumesOnlyMeaningfulUnfinishedEpisodes() {
        assertEquals(60000L, podcastResumePosition(PodcastProgress(60000, 300000)))
        assertEquals(0L, podcastResumePosition(PodcastProgress(3000, 300000)))
        assertEquals(0L, podcastResumePosition(PodcastProgress(299000, 300000)))
        assertEquals(0L, podcastResumePosition(PodcastProgress(60000, 300000, played = true)))
        assertEquals(60000L, podcastResumePosition(PodcastProgress(60000, 0)))
    }
    @Test fun speedsAreFiniteAndBounded() {
        assertEquals(1f, podcastSpeed(Float.NaN), 0f)
        assertEquals(1f, podcastSpeed(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0.5f, podcastSpeed(-1f), 0f)
        assertEquals(3f, podcastSpeed(9f), 0f)
        assertEquals(1.5f, podcastSpeed(1.5f), 0f)
    }
}
