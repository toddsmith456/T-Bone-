package social.tbone.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SteakMigrationTest {

    @Test
    fun migration20To21CreatesVerifiedTokensAndTokenLedgersMatchingRoomSchema() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            null
        } as SupportSQLiteDatabase

        AppDatabase.MIGRATION_20_21.migrate(database)

        assertEquals(5, statements.size)

        val verifiedTokens = statements[0]
        assertTrue(verifiedTokens.contains("CREATE TABLE IF NOT EXISTS `verified_tokens`"))
        assertTrue(verifiedTokens.contains("`offerId` TEXT NOT NULL"))
        assertTrue(verifiedTokens.contains("`claimId` TEXT NOT NULL"))
        assertTrue(verifiedTokens.contains("`slug` TEXT NOT NULL"))
        assertTrue(verifiedTokens.contains("`recipientPubkey` TEXT NOT NULL"))
        assertTrue(verifiedTokens.contains("`senderPubkey` TEXT NOT NULL"))
        assertTrue(verifiedTokens.contains("`offeredAt` INTEGER NOT NULL"))
        assertTrue(verifiedTokens.contains("`claimedAt` INTEGER NOT NULL"))
        assertTrue(verifiedTokens.contains("`relayHint` TEXT"))
        assertTrue(verifiedTokens.contains("`verifiedAt` INTEGER NOT NULL"))
        assertTrue(verifiedTokens.contains("PRIMARY KEY(`offerId`)"))

        assertTrue(statements[1].contains("CREATE INDEX IF NOT EXISTS `index_verified_tokens_slug_recipientPubkey_claimedAt`"))
        assertTrue(statements[2].contains("CREATE INDEX IF NOT EXISTS `index_verified_tokens_slug_senderPubkey_offeredAt`"))

        val ledgers = statements[3]
        assertTrue(ledgers.contains("CREATE TABLE IF NOT EXISTS `token_ledgers`"))
        assertTrue(ledgers.contains("`pubkey` TEXT NOT NULL"))
        assertTrue(ledgers.contains("`slug` TEXT NOT NULL"))
        assertTrue(ledgers.contains("`ledgerEventId` TEXT NOT NULL"))
        assertTrue(ledgers.contains("`ledgerCreatedAt` INTEGER NOT NULL"))
        assertTrue(ledgers.contains("`verifiedCount` INTEGER NOT NULL"))
        assertTrue(ledgers.contains("`latestClaimedAt` INTEGER NOT NULL"))
        assertTrue(ledgers.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(ledgers.contains("PRIMARY KEY(`pubkey`, `slug`)"))

        assertTrue(statements[4].contains("CREATE INDEX IF NOT EXISTS `index_token_ledgers_slug_verifiedCount_latestClaimedAt`"))
    }
}
