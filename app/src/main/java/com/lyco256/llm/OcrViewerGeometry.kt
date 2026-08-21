package com.lyco256.llm

import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPolygon
import kotlin.math.abs

internal data class OcrImageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class OcrViewerTransform(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal object OcrViewerGeometry {
    fun fitImageRect(
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
    ): OcrImageRect? {
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
            viewportWidth <= 0f || viewportHeight <= 0f
        ) return null
        val scale = minOf(viewportWidth / sourceWidth, viewportHeight / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        return OcrImageRect(
            left = (viewportWidth - width) / 2f,
            top = (viewportHeight - height) / 2f,
            right = (viewportWidth + width) / 2f,
            bottom = (viewportHeight + height) / 2f,
        )
    }

    fun transformPoint(
        point: OcrPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: OcrViewerTransform,
    ): OcrPoint {
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        return OcrPoint(
            x = centerX + (point.x - centerX) * transform.scale + transform.offsetX,
            y = centerY + (point.y - centerY) * transform.scale + transform.offsetY,
        )
    }

    fun transformRect(
        rect: OcrImageRect,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: OcrViewerTransform,
    ): OcrImageRect {
        val topLeft = transformPoint(OcrPoint(rect.left, rect.top), viewportWidth, viewportHeight, transform)
        val bottomRight = transformPoint(OcrPoint(rect.right, rect.bottom), viewportWidth, viewportHeight, transform)
        return OcrImageRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }

    fun mapPolygonToViewport(
        polygon: OcrPolygon,
        sourceWidth: Int,
        sourceHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: OcrViewerTransform = OcrViewerTransform(),
    ): List<OcrPoint>? {
        val imageRect = fitImageRect(sourceWidth, sourceHeight, viewportWidth, viewportHeight) ?: return null
        val clipped = clipPolygonToSource(polygon, sourceWidth.toFloat(), sourceHeight.toFloat()) ?: return null
        return clipped.map { point ->
            val inFitRect = OcrPoint(
                x = imageRect.left + point.x / sourceWidth * imageRect.width,
                y = imageRect.top + point.y / sourceHeight * imageRect.height,
            )
            transformPoint(inFitRect, viewportWidth, viewportHeight, transform)
        }
    }

    fun isValidPolygon(polygon: OcrPolygon, sourceWidth: Int, sourceHeight: Int): Boolean =
        clipPolygonToSource(polygon, sourceWidth.toFloat(), sourceHeight.toFloat()) != null

    fun pageSwipeDirection(
        pageCount: Int,
        gestureStartScale: Float,
        gestureHadZoom: Boolean,
        gestureDeltaX: Float,
        viewportWidth: Float,
    ): Int? {
        if (pageCount <= 1 || gestureStartScale > 1.001f || gestureHadZoom ||
            !gestureDeltaX.isFinite() || !viewportWidth.isFinite() || viewportWidth <= 0f ||
            abs(gestureDeltaX) <= viewportWidth * 0.18f
        ) return null
        return if (gestureDeltaX < 0f) 1 else -1
    }

    private fun clipPolygonToSource(polygon: OcrPolygon, width: Float, height: Float): List<OcrPoint>? {
        if (width <= 0f || height <= 0f || polygon.points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        var clipped = polygon.points
        clipped = clipAgainst(clipped, { it.x >= 0f }) { a, b -> intersection(a, b, vertical = true, bound = 0f) }
        clipped = clipAgainst(clipped, { it.x <= width }) { a, b -> intersection(a, b, vertical = true, bound = width) }
        clipped = clipAgainst(clipped, { it.y >= 0f }) { a, b -> intersection(a, b, vertical = false, bound = 0f) }
        clipped = clipAgainst(clipped, { it.y <= height }) { a, b -> intersection(a, b, vertical = false, bound = height) }
        if (clipped.size < 3 || polygonArea(clipped) <= 0.5f) return null
        return clipped
    }

    private fun clipAgainst(
        points: List<OcrPoint>,
        inside: (OcrPoint) -> Boolean,
        intersect: (OcrPoint, OcrPoint) -> OcrPoint,
    ): List<OcrPoint> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<OcrPoint>(points.size + 2)
        var previous = points.last()
        var previousInside = inside(previous)
        points.forEach { current ->
            val currentInside = inside(current)
            if (currentInside != previousInside) result += intersect(previous, current)
            if (currentInside) result += current
            previous = current
            previousInside = currentInside
        }
        return result
    }

    private fun intersection(a: OcrPoint, b: OcrPoint, vertical: Boolean, bound: Float): OcrPoint {
        val denominator = if (vertical) b.x - a.x else b.y - a.y
        if (abs(denominator) < 0.00001f) return if (vertical) OcrPoint(bound, a.y) else OcrPoint(a.x, bound)
        val ratio = ((bound - if (vertical) a.x else a.y) / denominator).coerceIn(0f, 1f)
        return OcrPoint(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio)
    }

    private fun polygonArea(points: List<OcrPoint>): Float = abs(
        points.indices.sumOf { index ->
            val next = points[(index + 1) % points.size]
            (points[index].x * next.y - next.x * points[index].y).toDouble()
        }.toFloat() / 2f,
    )
}
