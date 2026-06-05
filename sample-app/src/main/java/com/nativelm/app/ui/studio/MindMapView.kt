/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sagar.aicore.studio.MindNode
import kotlin.math.min
import kotlin.math.roundToInt

private val NODE_W = 150.dp
private val NODE_H = 56.dp
private val H_GAP = 56.dp
private val V_GAP = 16.dp
private const val MIN_SCALE = 0.3f
private const val MAX_SCALE = 3f

/**
 * A pan/zoom node-graph render of a [root] mind map. The tree is laid out left→right
 * (root on the left, branches fanning right) with a tidy-tree y-assignment, drawn as
 * rounded node chips joined by curved edges. The whole graph lives in a clipped,
 * fixed-height box and is transformed with the same pinch/pan gesture handling as the
 * PDF viewer. Tapping a node calls [onAsk] with its label (seed a grounded chat question).
 */
@Composable
fun MindMapView(root: MindNode, onAsk: (String) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val nodeWpx = with(density) { NODE_W.toPx() }
    val nodeHpx = with(density) { NODE_H.toPx() }
    val colPx = with(density) { (NODE_W + H_GAP).toPx() }
    val rowPx = with(density) { (NODE_H + V_GAP).toPx() }

    val layout = remember(root) { layoutMindMap(root, nodeWpx, nodeHpx, colPx, rowPx) }
    val contentW = (layout.nodes.maxOfOrNull { it.x } ?: 0f) + nodeWpx
    val contentH = (layout.nodes.maxOfOrNull { it.y } ?: 0f) + nodeHpx
    val edgeColor = MaterialTheme.colorScheme.outlineVariant

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        // Fit the whole graph in view on first layout, centered; never enlarge past 1.5×.
        val init = remember(root, vw, vh) {
            val s = min(vw / contentW, vh / contentH).coerceIn(MIN_SCALE, 1.5f)
            s to Offset((vw - contentW * s) / 2f, (vh - contentH * s) / 2f)
        }
        var scale by remember(root) { mutableFloatStateOf(init.first) }
        var offset by remember(root) { mutableStateOf(init.second) }

        // The gesture detector lives on the full viewport box (not the clipped,
        // content-sized inner box) so the whole 460dp area captures the pinch/pan and
        // consumes it before the parent verticalScroll can steal the touch. The inner
        // box carries the graphicsLayer transform.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(root) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        // Keep the content point under the centroid fixed while zooming.
                        offset = centroid - (centroid - offset) * (newScale / scale) + pan
                        scale = newScale
                    }
                },
        ) {
          Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Canvas(
                Modifier.size(
                    with(density) { contentW.toDp() },
                    with(density) { contentH.toDp() },
                ),
            ) {
                val stroke = 2.dp.toPx()
                layout.edges.forEach { e ->
                    val midX = (e.x1 + e.x2) / 2f
                    val path = Path().apply {
                        moveTo(e.x1, e.y1)
                        cubicTo(midX, e.y1, midX, e.y2, e.x2, e.y2)
                    }
                    drawPath(path, edgeColor, style = Stroke(width = stroke))
                }
            }
            layout.nodes.forEach { n ->
                MindNodeChip(
                    text = n.text,
                    depth = n.depth,
                    onClick = { onAsk(n.text) },
                    modifier = Modifier
                        .offset { IntOffset(n.x.roundToInt(), n.y.roundToInt()) }
                        .size(NODE_W, NODE_H),
                )
            }
          }
        }
    }
}

@Composable
private fun MindNodeChip(text: String, depth: Int, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val bg = when (depth) {
        0 -> scheme.primary
        1 -> scheme.primaryContainer
        else -> scheme.surfaceVariant
    }
    val fg = when (depth) {
        0 -> scheme.onPrimary
        1 -> scheme.onPrimaryContainer
        else -> scheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), onClick = onClick, modifier = modifier) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A node placed at pixel ([x],[y]) (top-left) at tree [depth]. */
private data class MmNode(val text: String, val x: Float, val y: Float, val depth: Int)

/** A curved edge between two node anchor points, in pixels. */
private data class MmEdge(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

private class MmLayout(val nodes: List<MmNode>, val edges: List<MmEdge>)

/**
 * Left→right tidy-tree layout. x is fixed by depth; leaves take successive rows and
 * each parent centers on the span of its children. Edges run from a parent's right
 * edge to each child's left edge (both at vertical mid-height).
 */
private fun layoutMindMap(
    root: MindNode,
    nodeWpx: Float,
    nodeHpx: Float,
    colPx: Float,
    rowPx: Float,
): MmLayout {
    val nodes = ArrayList<MmNode>()
    val edges = ArrayList<MmEdge>()
    var nextLeaf = 0

    fun place(node: MindNode, depth: Int): Float {
        val x = depth * colPx
        val childYs = node.children.map { place(it, depth + 1) }
        val y = if (childYs.isEmpty()) {
            (nextLeaf++ * rowPx)
        } else {
            (childYs.first() + childYs.last()) / 2f
        }
        nodes.add(MmNode(node.text, x, y, depth))
        node.children.forEachIndexed { i, _ ->
            edges.add(MmEdge(x + nodeWpx, y + nodeHpx / 2f, (depth + 1) * colPx, childYs[i] + nodeHpx / 2f))
        }
        return y
    }

    place(root, 0)
    return MmLayout(nodes, edges)
}
