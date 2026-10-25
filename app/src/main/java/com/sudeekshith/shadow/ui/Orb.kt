package com.sudeekshith.shadow.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.sudeekshith.shadow.Status
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shadow's animated "reactor" orb: violet rings that spin, and a core that pulses with your voice / Shadow's speech. */
@Composable
fun ShadowOrb(status: Status, level: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orb")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(if (status == Status.Thinking) 1600 else 9000, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (status == Status.Speaking) 420 else 1800), RepeatMode.Reverse), label = "breathe")
    val voiceLevel by animateFloatAsState(level, tween(120), label = "level")

    val energy = when (status) {
        Status.Idle -> 0.25f + 0.10f * breathe
        Status.Listening -> 0.55f + 0.45f * voiceLevel
        Status.Thinking -> 0.5f + 0.2f * breathe
        Status.Speaking -> 0.6f + 0.4f * breathe
    }

    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f

        // outer haze
        drawCircle(Brush.radialGradient(listOf(ShadowColors.Violet.copy(alpha = 0.28f * energy + 0.08f), Color.Transparent), c, r), r, c)

        // tick ring
        rotate(-spin * 0.5f, c) {
            val ticks = 72
            for (i in 0 until ticks) {
                val a = Math.toRadians(i * 360.0 / ticks)
                val long = i % 6 == 0
                val r1 = r * 0.90f; val r2 = r * (if (long) 0.83f else 0.87f)
                drawLine(ShadowColors.Glow.copy(alpha = if (long) 0.7f else 0.3f),
                    Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (long) 3f else 1.5f)
            }
        }
        // segmented arcs
        val ringR = r * 0.72f
        rotate(spin, c) {
            for (i in 0 until 6) {
                drawArc(ShadowColors.Violet.copy(alpha = 0.85f), i * 60f + 6f, 44f, false,
                    topLeft = Offset(c.x - ringR, c.y - ringR), size = Size(ringR * 2, ringR * 2),
                    style = Stroke(width = r * 0.06f, cap = StrokeCap.Butt))
            }
        }
        val ring2 = r * 0.58f
        rotate(-spin * 1.6f, c) {
            for (i in 0 until 3) {
                drawArc(ShadowColors.Magenta.copy(alpha = 0.75f), i * 120f, 70f, false,
                    topLeft = Offset(c.x - ring2, c.y - ring2), size = Size(ring2 * 2, ring2 * 2),
                    style = Stroke(width = r * 0.025f, cap = StrokeCap.Round))
            }
        }
        // inner triangle (reactor style)
        val tri = r * 0.40f
        rotate(spin * 0.3f, c) {
            for (k in 0 until 3) {
                val a1 = Math.toRadians(-90.0 + k * 120); val a2 = Math.toRadians(-90.0 + (k + 1) * 120)
                drawLine(ShadowColors.Glow.copy(alpha = 0.55f),
                    Offset(c.x + tri * cos(a1).toFloat(), c.y + tri * sin(a1).toFloat()),
                    Offset(c.x + tri * cos(a2).toFloat(), c.y + tri * sin(a2).toFloat()), strokeWidth = r * 0.02f)
            }
        }
        // glowing core
        val coreR = r * (0.22f + 0.13f * energy)
        drawCircle(Brush.radialGradient(listOf(ShadowColors.Glow.copy(alpha = 0.9f), ShadowColors.Violet.copy(alpha = 0.35f), Color.Transparent), c, coreR * 2.2f), coreR * 2.2f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, ShadowColors.Core, ShadowColors.Glow), c, coreR), coreR, c)
        drawCircle(ShadowColors.Violet, coreR * 1.05f, c, style = Stroke(width = r * 0.015f))
    }
}
