package com.trackfix.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

data class PhotoMetricLine(
    val label: String,
    val value: String
)

/**
 * Describes the small mark drawn after the "ТРЕНИРОВКА" title.
 * Only one of [icon] and [text] is normally set.
 */
data class PhotoActivityOverlay(
    val icon: Bitmap? = null,
    val text: String? = null
)

/**
 * Draws the selected training metrics over a photo.
 *
 * The result keeps the aspect ratio of the source photo. There is no panel or
 * frame: only the text is drawn, with a soft shadow so it stays readable on
 * any background. The metrics are arranged in one or two columns depending on
 * their count and anchored to the bottom margin.
 */
object PhotoCardRenderer {

    fun render(
        photo: Bitmap,
        metrics: List<PhotoMetricLine>,
        title: String,
        subtitle: String?,
        activity: PhotoActivityOverlay? = null,
        logo: Bitmap? = null
    ): Bitmap {
        val width = photo.width
        val height = photo.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(photo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        val base = min(width, height).toFloat()
        val columns = if (metrics.size <= 1) 1 else 2
        val rows = ceil(metrics.size.toDouble() / columns).toInt().coerceAtLeast(1)
        val margin = width * 0.055f
        val contentLeft = margin
        val contentRight = width - margin
        val contentBottom = height - margin

        var titleSize = base * 0.028f
        var labelSize = base * 0.019f
        var valueSize = if (columns == 1) base * 0.052f else base * 0.038f
        var cellGapY = base * 0.018f
        var headerGap = base * 0.018f

        var titlePaint = textPaint(titleSize, Color.WHITE, Typeface.BOLD)
        var subtitlePaint = textPaint(
            base * 0.021f,
            Color.argb(210, 235, 245, 240),
            Typeface.NORMAL
        )
        var labelPaint = textPaint(
            labelSize,
            Color.argb(190, 235, 245, 240),
            Typeface.NORMAL
        )
        var valuePaint = textPaint(valueSize, Color.WHITE, Typeface.BOLD)

        fun headerHeight(): Float {
            val titleHeight = titlePaint.fontMetrics.descent - titlePaint.fontMetrics.ascent
            val subtitleHeight =
                subtitlePaint.fontMetrics.descent - subtitlePaint.fontMetrics.ascent
            return maxOf(titleHeight, subtitleHeight)
        }

        fun cellHeight(): Float {
            val labelHeight = labelPaint.fontMetrics.descent - labelPaint.fontMetrics.ascent
            val valueHeight = valuePaint.fontMetrics.descent - valuePaint.fontMetrics.ascent
            return labelHeight + valueHeight + base * 0.006f
        }

        fun contentHeight(): Float {
            return headerHeight() + headerGap +
                rows * cellHeight() + (rows - 1) * cellGapY
        }

        var attempts = 0
        while (contentHeight() > height * 0.72f && attempts < 12) {
            val scale = 0.88f
            titleSize *= scale
            labelSize *= scale
            valueSize *= scale
            cellGapY *= scale
            headerGap *= scale
            titlePaint = textPaint(titleSize, Color.WHITE, Typeface.BOLD)
            subtitlePaint = textPaint(
                subtitlePaint.textSize * scale,
                Color.argb(210, 235, 245, 240),
                Typeface.NORMAL
            )
            labelPaint = textPaint(
                labelSize,
                Color.argb(190, 235, 245, 240),
                Typeface.NORMAL
            )
            valuePaint = textPaint(valueSize, Color.WHITE, Typeface.BOLD)
            attempts++
        }

        val headerTop = contentBottom - contentHeight()
        val titleBaseline = headerTop - titlePaint.fontMetrics.ascent
        val titleWidth = titlePaint.measureText(title)
        canvas.drawText(title, contentLeft, titleBaseline, titlePaint)

        var subtitleWidth = 0f
        if (!subtitle.isNullOrBlank()) {
            subtitleWidth = subtitlePaint.measureText(subtitle)
            subtitlePaint.textAlign = Paint.Align.RIGHT
            val subtitleBaseline = headerTop - subtitlePaint.fontMetrics.ascent
            canvas.drawText(subtitle, contentRight, subtitleBaseline, subtitlePaint)
            subtitlePaint.textAlign = Paint.Align.LEFT
        }

        val suffixLeft = contentLeft + titleWidth + base * 0.012f
        val suffixRight = contentRight - subtitleWidth -
            (if (subtitleWidth > 0f) base * 0.03f else 0f)
        val suffixAvailable = suffixRight - suffixLeft
        if ((activity != null || logo != null) && suffixAvailable > base * 0.01f) {
            val lineHeight =
                titlePaint.fontMetrics.descent - titlePaint.fontMetrics.ascent
            val centerY = titleBaseline +
                (titlePaint.fontMetrics.ascent + titlePaint.fontMetrics.descent) / 2f
            val gap = base * 0.012f
            val activityIcon = activity?.icon?.takeIf { !it.isRecycled }
            val activityText = activity?.text?.trim()
            val logoBitmap = logo?.takeIf { !it.isRecycled }

            var iconHeight = lineHeight * 0.92f
            var iconWidth = if (activityIcon != null) {
                iconHeight * activityIcon.width.toFloat() / activityIcon.height.toFloat()
            } else {
                0f
            }
            var logoHeight = if (logoBitmap != null) lineHeight * 0.92f else 0f
            var logoWidth = if (logoBitmap != null) {
                logoHeight * logoBitmap.width.toFloat() / logoBitmap.height.toFloat()
            } else {
                0f
            }

            var activityPaint: Paint? = null
            var textWidth = 0f
            if (activityIcon == null && !activityText.isNullOrBlank()) {
                activityPaint = textPaint(
                    titleSize * 0.82f,
                    Color.argb(235, 255, 255, 255),
                    Typeface.BOLD
                )
                textWidth = activityPaint.measureText(activityText)
            }

            val activityWidth = if (activityIcon != null) iconWidth else textWidth
            var totalWidth = activityWidth + logoWidth
            if (activityWidth > 0f && logoWidth > 0f) totalWidth += gap
            if (totalWidth > suffixAvailable) {
                val scale = suffixAvailable / totalWidth
                iconHeight *= scale
                iconWidth *= scale
                logoHeight *= scale
                logoWidth *= scale
                if (activityPaint != null && activityText != null) {
                    activityPaint.textSize *= scale
                    textWidth = activityPaint.measureText(activityText)
                }
            }

            val bitmapPaint = Paint(
                Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
            )
            var cursor = suffixLeft
            if (activityIcon != null) {
                val iconRect = RectF(
                    cursor,
                    centerY - iconHeight / 2f,
                    cursor + iconWidth,
                    centerY + iconHeight / 2f
                )
                canvas.drawBitmap(activityIcon, null, iconRect, bitmapPaint)
                cursor += iconWidth
            } else if (activityPaint != null && !activityText.isNullOrBlank()) {
                val activityLinePaint =
                    fittedPaint(activityPaint, activityText, suffixAvailable)
                canvas.drawText(activityText, cursor, titleBaseline, activityLinePaint)
                cursor += activityLinePaint.measureText(activityText)
            }
            if (logoBitmap != null) {
                if (cursor > suffixLeft) cursor += gap
                val logoRect = RectF(
                    cursor,
                    centerY - logoHeight / 2f,
                    cursor + logoWidth,
                    centerY + logoHeight / 2f
                )
                canvas.drawBitmap(logoBitmap, null, logoRect, bitmapPaint)
            }
        }

        val gridTop = headerTop + headerHeight() + headerGap
        val availableWidth = contentRight - contentLeft
        val columnGap = if (columns == 2) width * 0.035f else 0f
        val columnWidth = (availableWidth - columnGap * (columns - 1)) / columns
        val cellHeightValue = cellHeight()

        for ((index, metric) in metrics.withIndex()) {
            val column = index % columns
            val row = index / columns
            val x = contentLeft + column * (columnWidth + columnGap)
            val top = gridTop + row * (cellHeightValue + cellGapY)

            val label = metric.label.uppercase(Locale.forLanguageTag("ru-RU"))
            val labelLinePaint = fittedPaint(labelPaint, label, columnWidth)
            val labelBaseline = top - labelLinePaint.fontMetrics.ascent
            canvas.drawText(label, x, labelBaseline, labelLinePaint)

            val valueTop = top +
                (labelLinePaint.fontMetrics.descent - labelLinePaint.fontMetrics.ascent) +
                base * 0.002f
            val valueLinePaint = fittedPaint(valuePaint, metric.value, columnWidth)
            val valueBaseline = valueTop - valueLinePaint.fontMetrics.ascent
            canvas.drawText(metric.value, x, valueBaseline, valueLinePaint)
        }

        return output
    }

    private fun textPaint(size: Float, color: Int, style: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create("sans-serif", style)
            // A soft shadow instead of a panel keeps the text readable on
            // both light and dark photos without covering the image.
            setShadowLayer(
                maxOf(1f, size * 0.16f),
                0f,
                size * 0.06f,
                Color.argb(190, 0, 0, 0)
            )
        }
    }

    private fun fittedPaint(source: Paint, text: String, maxWidth: Float): Paint {
        val paint = Paint(source)
        var size = paint.textSize
        while (size > 8f && paint.measureText(text) > maxWidth) {
            size *= 0.94f
            paint.textSize = size
        }
        return paint
    }
}
