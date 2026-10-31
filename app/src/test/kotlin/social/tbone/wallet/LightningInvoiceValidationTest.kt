package social.tbone.wallet

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LightningInvoiceValidationTest {
    // BOLT-11 official test vector, 2500u = 250,000,000 msats (expired by design).
    private val official = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    // Generated with the independent Python bolt11 library; fixed public test key only.
    private val zapInvoice = "lnbc210n1pj48ugqpp5qyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqssp5qgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqyqszqgpqhp58lath4r2fcqsgvd73hct89n89jpsdu9gfp98cql4ac4uvpfy53tqxqrrsskqqd58g5t754kgzjq99uv26p7ax3y5rtrx84cvaxp8w5ty92f4dn7qyky3c3cpnkaw0he3jm5c4xk4870q2nyvk7yxvrdc98x2hf2rqq7peg2x"
    private val description = """{"kind":9734,"test":"fixture"}"""
    private val now = Instant.ofEpochSecond(1700000300)

    @Test fun officialInvoiceChecksumAndAmount() {
        assertEquals(250_000_000L, LightningInvoice.amountMsats(official))
        assertNull(LightningInvoice.amountMsats(official.dropLast(1) + "q"))
    }
    @Test fun acceptsSignedBoundMainnetZapInvoice() {
        LightningInvoice.validateZap(zapInvoice, 21_000L, description, now)
    }
    @Test fun rejectsExpiredWrongAmountAndWrongDescription() {
        assertTrue(runCatching { LightningInvoice.validateZap(zapInvoice, 21_000L, description, Instant.ofEpochSecond(1700003600)) }.isFailure)
        assertTrue(runCatching { LightningInvoice.validateZap(zapInvoice, 22_000L, description, now) }.isFailure)
        assertTrue(runCatching { LightningInvoice.validateZap(zapInvoice, 21_000L, description + " ", now) }.isFailure)
        assertTrue(runCatching { LightningInvoice.validateZap("lnbc210n1qpzry9wv7z6t", 21_000L, description, now) }.isFailure)
    }
    @Test fun rejectsOrdinaryNonZapInvoice() {
        assertTrue(runCatching { LightningInvoice.validateZap(official, 250_000_000L, description, Instant.ofEpochSecond(1496314659)) }.isFailure)
    }
}
