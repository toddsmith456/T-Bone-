package social.tbone.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cryptographically verified NIP-XX Daily Claimable Token receipt.
 *
 * Only persisted AFTER the underlying `kind: 8701` Offer and `kind: 8702` Claim
 * pass full Schnorr signature, NIP-13 PoW, 24-hour claim window, and sender
 * cooldown checks. Because verified `(offerId, claimId)` pairs are immutable,
 * caching them avoids re-fetching and re-verifying historical steaks on every
 * profile view or leaderboard refresh.
 */
@Entity(
    tableName = "verified_tokens",
    indices = [
        Index(value = ["slug", "recipientPubkey", "claimedAt"]),
        Index(value = ["slug", "senderPubkey", "offeredAt"]),
    ],
)
data class VerifiedTokenEntity(
    @PrimaryKey val offerId: String,
    val claimId: String,
    val slug: String,
    val recipientPubkey: String,
    val senderPubkey: String,
    val offeredAt: Long,
    val claimedAt: Long,
    val relayHint: String?,
    val verifiedAt: Long,
)

/**
 * Latest verified summary of a pubkey's `kind: 38701` Profile Token Ledger.
 *
 * Powers instant cold-start profile counts and Follows/Global leaderboards
 * without blindly trusting unverified counts from relays.
 */
@Entity(
    tableName = "token_ledgers",
    primaryKeys = ["pubkey", "slug"],
    indices = [
        Index(value = ["slug", "verifiedCount", "latestClaimedAt"]),
    ],
)
data class TokenLedgerStateEntity(
    val pubkey: String,
    val slug: String,
    val ledgerEventId: String,
    val ledgerCreatedAt: Long,
    val verifiedCount: Int,
    val latestClaimedAt: Long,
    val updatedAt: Long,
)

@Dao
interface TokenCacheDao {

    @Query(
        "SELECT * FROM verified_tokens " +
            "WHERE slug = :slug AND recipientPubkey = :recipientPubkey " +
            "ORDER BY claimedAt DESC, offerId ASC"
    )
    suspend fun getVerifiedForRecipient(slug: String, recipientPubkey: String): List<VerifiedTokenEntity>

    @Query(
        "SELECT * FROM verified_tokens " +
            "WHERE slug = :slug AND offerId IN (:offerIds)"
    )
    suspend fun getVerifiedByOfferIds(slug: String, offerIds: List<String>): List<VerifiedTokenEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVerifiedTokens(items: List<VerifiedTokenEntity>)

    @Query(
        "DELETE FROM verified_tokens " +
            "WHERE slug = :slug AND recipientPubkey = :recipientPubkey AND offerId NOT IN (:keepOfferIds)"
    )
    suspend fun pruneRecipientTokensNotIn(
        slug: String,
        recipientPubkey: String,
        keepOfferIds: List<String>,
    )

    @Query("DELETE FROM verified_tokens WHERE slug = :slug AND recipientPubkey = :recipientPubkey")
    suspend fun clearRecipientTokens(slug: String, recipientPubkey: String)

    @Query("SELECT * FROM token_ledgers WHERE pubkey = :pubkey AND slug = :slug LIMIT 1")
    suspend fun getLedgerState(pubkey: String, slug: String): TokenLedgerStateEntity?

    @Query(
        "SELECT * FROM token_ledgers " +
            "WHERE slug = :slug AND verifiedCount > 0 " +
            "ORDER BY verifiedCount DESC, latestClaimedAt DESC, pubkey ASC " +
            "LIMIT :limit"
    )
    suspend fun getLeaderboardRows(slug: String, limit: Int): List<TokenLedgerStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedgerState(state: TokenLedgerStateEntity)

    @Query("DELETE FROM verified_tokens")
    suspend fun clearAllVerifiedTokens()

    @Query("DELETE FROM token_ledgers")
    suspend fun clearAllLedgerStates()
}
