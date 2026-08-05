package com.xopp.android.ui

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * An [ImageVector] flattened into a plain [Path], normalised into a 1×1 box centred on the origin
 * (so it spans -0.5..0.5 on both axes). That makes one icon usable by both painters in this app:
 * the Compose configuration diagram draws it with `withTransform`, and the low-latency canvas
 * renderer draws `asAndroidPath()` under a `translate`/`scale` — neither needs a Compose painter,
 * so the ring's icons cost nothing per frame beyond a path fill.
 *
 * Group transforms are ignored: the Material icon set this app draws from has none.
 */
private val outlines = ConcurrentHashMap<ImageVector, Path>()

/** The cached unit outline of this icon. Never mutate the result — it is shared. */
fun ImageVector.unitOutline(): Path = outlines.getOrPut(this) {
    val path = Path()
    appendTo(root, path)
    val scale = 1f / max(viewportWidth, viewportHeight)
    val matrix = Matrix().apply {
        translate(-viewportWidth * scale / 2f, -viewportHeight * scale / 2f)
        scale(scale, scale)
    }
    path.transform(matrix)
    path
}

private fun appendTo(node: VectorNode, target: Path) {
    when (node) {
        is VectorPath -> target.addPath(node.pathData.toPath())
        is VectorGroup -> node.forEach { appendTo(it, target) }
    }
}
