package social.tbone.ui.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import social.tbone.nostr.Event
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Bitmap + layout metadata used by both PNG and video-card sharing. */
data class RenderedShareCard(
    val file: File,
    val width: Int,
    val height: Int,
    /** Output-frame coordinates for the one video window, if the card contains a video. */
    val videoSlot: RectF?,
)

/** Branded share-card renderer. Images are fitted (never cropped) and scale with note length. */
object NoteShareCardGenerator {
    private const val WIDTH = 1080
    private const val MAX_HEIGHT = 8_000
    private const val MAX_CARD_MEDIA = 6
    private const val OUTER_MARGIN = 54f
    private const val CARD_TOP = 52f
    private const val CARD_RADIUS = 38f
    private const val CARD_PADDING = 50f
    private const val MAX_IMAGE_RESPONSE_BYTES = 18L * 1024L * 1024L

    fun firstVideoUrl(event: Event): String? = firstVideo(event)?.url

    internal fun firstVideo(event: Event): ShareCardMedia? =
        ShareCardMediaParser.parse(event).firstOrNull { it.kind == ShareMediaKind.VIDEO }

    /** Static PNG entry point. */
    fun render(
        context: Context,
        event: Event,
        authorName: String? = null,
        authorPictureUrl: String? = null,
    ): File = renderCard(context, event, authorName, authorPictureUrl).file

    fun renderCard(
        context: Context,
        event: Event,
        authorName: String? = null,
        authorPictureUrl: String? = null,
        animatedVideoUrl: String? = null,
    ): RenderedShareCard {
        val media = ShareCardMediaParser.parse(event)
        val body = ShareCardMediaParser.stripMediaUrls(event.content, media).ifBlank { "(empty note)" }
        val cardLeft = OUTER_MARGIN
        val cardRight = WIDTH - OUTER_MARGIN
        val innerLeft = cardLeft + CARD_PADDING
        val innerRight = cardRight - CARD_PADDING
        val innerWidth = innerRight - innerLeft

        val bodyPaint = textPaint(Color.rgb(235, 242, 241), 40f, "sans-serif")
        val bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, innerWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(10f, 1.12f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()

        val headerHeight = 82f
        val authorHeight = 108f
        val bodyTop = CARD_TOP + CARD_PADDING + headerHeight + authorHeight + 24f
        // Images/videos get a distinct, roomy section below the note body.
        // Keep the card memory-bounded even for notes with many attachments.
        val visualMedia = media.filter { it.kind == ShareMediaKind.IMAGE || it.kind == ShareMediaKind.VIDEO }
        val displayedMedia = visualMedia.take(MAX_CARD_MEDIA)
        val imageBitmaps: Map<String, Bitmap?> = displayedMedia
            .filter { it.kind == ShareMediaKind.IMAGE }
            .associate { it.url to downloadBitmap(it.url, 720) }
        val profileBitmap = authorPictureUrl?.takeIf { it.isNotBlank() }?.let { downloadBitmap(it, 128) }
        val galleryTiles = mutableListOf<GalleryTile>()

        var nextTop = bodyTop + bodyLayout.height + 36f
        val mediaHeaderTop = if (displayedMedia.isNotEmpty()) nextTop + 4f else null
        if (mediaHeaderTop != null) nextTop = mediaHeaderTop + 50f

        if (displayedMedia.size == 1) {
            val item = displayedMedia.first()
            val image = imageBitmaps[item.url]
            val aspect = if (image != null) image.width.toFloat() / image.height.coerceAtLeast(1) else 16f / 9f
            val maxHeight = 700f
            val tileWidth = min(innerWidth, maxHeight * aspect)
            val tileHeight = tileWidth / aspect
            val left = innerLeft + (innerWidth - tileWidth) / 2f
            galleryTiles += GalleryTile(item, image, RectF(left, nextTop, left + tileWidth, nextTop + tileHeight))
            nextTop += tileHeight + 14f
        } else if (displayedMedia.size > 1) {
            val gap = 18f
            val tileWidth = (innerWidth - gap) / 2f
            val tileHeight = 292f
            val rowWidth = tileWidth * 2 + gap
            val startX = innerLeft + (innerWidth - rowWidth) / 2f
            displayedMedia.chunked(2).forEachIndexed { rowIndex, rowItems ->
                rowItems.forEachIndexed { columnIndex, item ->
                    val left = startX + columnIndex * (tileWidth + gap)
                    val top = nextTop + rowIndex * (tileHeight + 18f)
                    galleryTiles += GalleryTile(
                        item,
                        imageBitmaps[item.url],
                        RectF(left, top, left + tileWidth, top + tileHeight),
                    )
                }
            }
            val rows = (displayedMedia.size + 1) / 2
            nextTop += rows * tileHeight + (rows - 1).coerceAtLeast(0) * 18f + 14f
        }

        val hiddenMediaCount = (visualMedia.size - displayedMedia.size).coerceAtLeast(0)
        val hiddenMediaTop = nextTop
        if (hiddenMediaCount > 0) nextTop += 30f
        val audioCount = media.count { it.kind == ShareMediaKind.AUDIO }
        val audioTop = if (audioCount > 0) nextTop + 12f else nextTop
        val audioBottom = if (audioCount > 0) audioTop + 40f else audioTop
        val footerTop = max(bodyTop + bodyLayout.height + 44f, audioBottom + 20f)
        val cardBottom = footerTop + 104f
        val imageHeight = max(640, (cardBottom + CARD_TOP + 30f).toInt())
        require(imageHeight <= MAX_HEIGHT) { "This note has too many images or too much text for one share card" }

        val videoSlot = galleryTiles
            .firstOrNull { it.media.kind == ShareMediaKind.VIDEO && it.media.url == animatedVideoUrl }
            ?.bounds
            ?.let { insetRect(it, 8f) }

        val bitmap = Bitmap.createBitmap(WIDTH, imageHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            drawBackground(canvas, imageHeight)
            val card = RectF(cardLeft, CARD_TOP, cardRight, cardBottom)
            canvas.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(15, 24, 27)
                style = Paint.Style.FILL
            })
            canvas.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(44, 69, 70)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })

            val accent = Color.rgb(112, 226, 185)
            val muted = Color.rgb(151, 173, 173)
            val rule = Color.rgb(43, 62, 64)
            val headerY = CARD_TOP + CARD_PADDING

            // Compact T-Bone identity; the old “A thought worth sharing” line is gone.
            val markRect = RectF(innerLeft, headerY, innerLeft + 46f, headerY + 46f)
            canvas.drawRoundRect(markRect, 13f, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            canvas.drawText("T", markRect.centerX(), markRect.centerY() + 10f,
                textPaint(Color.rgb(9, 24, 25), 29f, "sans-serif-bold").apply { textAlign = Paint.Align.CENTER })
            canvas.drawText("T-BONE", innerLeft + 62f, headerY + 31f, textPaint(Color.rgb(228, 240, 237), 24f, "sans-serif-bold"))
            canvas.drawText("NOSTR  ·  NOTE", innerRight, headerY + 30f,
                textPaint(muted, 17f, "sans-serif-medium").apply { textAlign = Paint.Align.RIGHT })
            canvas.drawLine(innerLeft, headerY + 72f, innerRight, headerY + 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = rule
                strokeWidth = 2f
            })

            val authorTop = headerY + 94f
            val avatarCx = innerLeft + 35f
            val avatarCy = authorTop + 34f
            drawAuthorAvatar(canvas, profileBitmap, avatarCx, avatarCy, 34f, accent)
            val name = authorName?.trim()?.takeIf { it.isNotEmpty() } ?: "Nostr user"
            val initials = name.split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }
                .joinToString("").uppercase(Locale.ROOT).ifBlank { event.pubkey.take(2).uppercase(Locale.ROOT) }
            if (profileBitmap == null) {
                canvas.drawText(initials, avatarCx, avatarCy + 8f, textPaint(accent, 22f, "sans-serif-bold").apply {
                    textAlign = Paint.Align.CENTER
                })
            }
            val namePaint = textPaint(Color.rgb(231, 241, 238), 27f, "sans-serif-medium")
            val visibleName = TextUtils.ellipsize(name, namePaint, innerWidth - 104f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(visibleName, innerLeft + 88f, authorTop + 31f, namePaint)
            canvas.drawText(
                "@${event.pubkey.take(8)}…${event.pubkey.takeLast(6)}",
                innerLeft + 88f,
                authorTop + 62f,
                textPaint(muted, 19f, "sans-serif"),
            )

            canvas.drawRoundRect(
                RectF(innerLeft, bodyTop - 5f, innerLeft + 5f, bodyTop + bodyLayout.height + 5f),
                3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent },
            )
            canvas.save()
            canvas.translate(innerLeft + 22f, bodyTop)
            bodyLayout.draw(canvas)
            canvas.restore()

            // A labeled media zone and larger bottom gallery keep attachments
            // visually separate from the note body. Only the selected video
            // window changes in the video rendition.
            mediaHeaderTop?.let { top ->
                canvas.drawText(
                    "MEDIA",
                    innerLeft,
                    top + 26f,
                    textPaint(accent, 18f, "sans-serif-bold"),
                )
                canvas.drawText(
                    "${visualMedia.size} ATTACHMENT${if (visualMedia.size == 1) "" else "S"}",
                    innerRight,
                    top + 26f,
                    textPaint(muted, 15f, "sans-serif-medium").apply { textAlign = Paint.Align.RIGHT },
                )
                canvas.drawLine(innerLeft, top + 38f, innerRight, top + 38f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = rule
                    strokeWidth = 2f
                })
            }
            galleryTiles.forEach { drawGalleryTile(canvas, it, animatedVideoUrl) }
            if (hiddenMediaCount > 0) {
                canvas.drawText(
                    "+$hiddenMediaCount more media attachment${if (hiddenMediaCount == 1) "" else "s"}",
                    innerLeft,
                    hiddenMediaTop + 20f,
                    textPaint(muted, 15f, "sans-serif-medium"),
                )
            }
            if (audioCount > 0) {
                val audioRect = RectF(innerLeft, audioTop, innerRight, audioBottom)
                canvas.drawRoundRect(audioRect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 40, 43) })
                canvas.drawText(
                    "♫  $audioCount audio attachment${if (audioCount == 1) "" else "s"}",
                    audioRect.left + 14f,
                    audioRect.centerY() + 7f,
                    textPaint(accent, 18f, "sans-serif-medium"),
                )
            }

            canvas.drawLine(innerLeft, footerTop, innerRight, footerTop, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = rule
                strokeWidth = 2f
            })
            val date = Instant.ofEpochSecond(event.createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy  ·  h:mm a", Locale.US))
            canvas.drawText(date, innerLeft, footerTop + 39f, textPaint(muted, 19f, "sans-serif"))
            canvas.drawText("SHARED WITH T-BONE", innerRight, footerTop + 39f,
                textPaint(accent, 17f, "sans-serif-medium").apply { textAlign = Paint.Align.RIGHT })
            canvas.drawText("EVENT  ${event.id.take(16)}…", innerLeft, footerTop + 72f,
                textPaint(Color.rgb(105, 127, 129), 15f, "sans-serif"))
            canvas.drawRoundRect(
                RectF(OUTER_MARGIN, imageHeight - 24f, OUTER_MARGIN + 72f, imageHeight - 17f),
                4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent },
            )

            val directory = File(context.cacheDir, "share_cards").apply { mkdirs() }
            val out = File(directory, "note-${event.id.take(20)}.png")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return RenderedShareCard(out, WIDTH, imageHeight, videoSlot)
        } finally {
            bitmap.recycle()
            imageBitmaps.values.filterNotNull().forEach { it.recycle() }
            profileBitmap?.recycle()
        }
    }

    private data class GalleryTile(val media: ShareCardMedia, val bitmap: Bitmap?, val bounds: RectF)

    private fun drawGalleryTile(canvas: Canvas, tile: GalleryTile, animatedVideoUrl: String?) {
        val bounds = tile.bounds
        val radius = 18f
        canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 19, 22) })
        val content = insetRect(bounds, 7f)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
        when (tile.media.kind) {
            ShareMediaKind.IMAGE -> {
                val image = tile.bitmap
                if (image != null && !image.isRecycled) {
                    canvas.drawBitmap(image, null, fitRect(image, content), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                } else {
                    canvas.drawRoundRect(content, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 31, 34) })
                    canvas.drawText("BLOSSOM IMAGE", content.left + 12f, content.centerY() + 6f,
                        textPaint(Color.rgb(151, 173, 173), 16f, "sans-serif-medium"))
                }
            }
            ShareMediaKind.VIDEO -> {
                canvas.drawRoundRect(content, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 15, 17) })
                val cx = content.centerX()
                val cy = content.centerY()
                canvas.drawCircle(cx, cy - 10f, 25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 112, 226, 185) })
                val play = Path().apply {
                    moveTo(cx - 6f, cy - 24f)
                    lineTo(cx + 13f, cy - 10f)
                    lineTo(cx - 6f, cy + 4f)
                    close()
                }
                canvas.drawPath(play, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 24, 25) })
                canvas.drawText(
                    if (tile.media.url == animatedVideoUrl) "VIDEO · PLAYABLE" else "VIDEO ATTACHMENT",
                    cx,
                    cy + 38f,
                    textPaint(Color.rgb(228, 240, 237), 15f, "sans-serif-medium").apply { textAlign = Paint.Align.CENTER },
                )
            }
            ShareMediaKind.AUDIO -> Unit
        }
        canvas.restore()
        canvas.drawRoundRect(bounds, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(44, 69, 70)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        })
    }

    private fun drawAuthorAvatar(canvas: Canvas, bitmap: Bitmap?, cx: Float, cy: Float, radius: Float, accent: Int) {
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 59, 56) })
        if (bitmap != null && !bitmap.isRecycled) {
            val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            val source = if (aspect > 1f) {
                val cropWidth = bitmap.height.toFloat()
                Rect((bitmap.width - cropWidth).toInt() / 2, 0, ((bitmap.width + cropWidth) / 2f).toInt(), bitmap.height)
            } else {
                val cropHeight = bitmap.width.toFloat()
                Rect(0, (bitmap.height - cropHeight).toInt() / 2, bitmap.width, ((bitmap.height + cropHeight) / 2f).toInt())
            }
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(cx, cy, radius - 1f, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, source, RectF(cx - radius, cy - radius, cx + radius, cy + radius), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
        }
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 2f
        })
    }

    private fun fitRect(bitmap: Bitmap, container: RectF): RectF {
        val scale = min(container.width() / bitmap.width.coerceAtLeast(1), container.height() / bitmap.height.coerceAtLeast(1))
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        return RectF(
            container.centerX() - width / 2f,
            container.centerY() - height / 2f,
            container.centerX() + width / 2f,
            container.centerY() + height / 2f,
        )
    }

    private fun insetRect(rect: RectF, amount: Float): RectF =
        RectF(rect.left + amount, rect.top + amount, rect.right - amount, rect.bottom - amount)

    private fun drawBackground(canvas: Canvas, height: Int) {
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), height.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), height.toFloat(),
                intArrayOf(Color.rgb(7, 16, 20), Color.rgb(13, 28, 32), Color.rgb(7, 13, 19)),
                null,
                Shader.TileMode.CLAMP,
            )
        })
        canvas.drawCircle(WIDTH - 40f, 40f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(12, 112, 226, 185) })
        canvas.drawCircle(20f, height - 20f, 240f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(9, 112, 226, 185) })
    }

    private fun downloadBitmap(url: String, maxDimension: Int): Bitmap? = runCatching {
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        ImageClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_IMAGE_RESPONSE_BYTES) return null
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMAGE_RESPONSE_BYTES) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDimension * 2 || bounds.outHeight / sample > maxDimension * 2) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }.getOrNull()

    private fun textPaint(color: Int, sizePx: Float, family: String): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = Typeface.create(family, Typeface.NORMAL)
        }
}
