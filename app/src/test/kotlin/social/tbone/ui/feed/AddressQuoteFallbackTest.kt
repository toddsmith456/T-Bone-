package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressQuoteFallbackTest {
    @Test fun depthLimitWithoutResolvedTargetOffersCopyNotAnImpossibleOpen() {
        assertEquals("nested quote hidden · tap to copy", addressQuoteFallbackLabel(2, null, emptySet(), true, false))
    }
    @Test fun resolvedCycleCanOfferOpen() {
        assertEquals("open quoted note", addressQuoteFallbackLabel(1, "id", setOf("id"), false, true))
    }
    @Test fun loadingAndUnavailableOfferCopy() {
        assertEquals("loading quoted note… · tap to copy", addressQuoteFallbackLabel(0, null, emptySet(), false, false))
        assertEquals("addressed note unavailable · tap to copy", addressQuoteFallbackLabel(0, null, emptySet(), true, false))
    }
}
