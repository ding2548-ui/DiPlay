package com.shilapi.xcertplay.media

/** Fits the negotiated CarPlay canvas inside the current window without changing its aspect ratio. */
data class CarPlayVideoLayout(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= left + width && y >= top && y <= top + height

    companion object {
        fun fit(canvasWidth: Int, canvasHeight: Int, viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
            if (canvasWidth <= 0 || canvasHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return CarPlayVideoLayout(0f, 0f, viewWidth.coerceAtLeast(0).toFloat(), viewHeight.coerceAtLeast(0).toFloat())
            }
            val scale = minOf(viewWidth.toFloat() / canvasWidth, viewHeight.toFloat() / canvasHeight)
            val width = canvasWidth * scale
            val height = canvasHeight * scale
            return CarPlayVideoLayout((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
        }
    }
}
