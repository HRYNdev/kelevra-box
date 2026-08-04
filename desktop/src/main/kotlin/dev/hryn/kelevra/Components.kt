package dev.hryn.kelevra

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DialState { On, Off, Busy, Broken }

/** Круг-состояние: тот же, что на телефоне. */
@Composable
fun KDial(
    state: DialState,
    title: String,
    badge: String?,
    place: String?,
    meta: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = KDim.DialSize,
) {
    val colors = K
    val live = state != DialState.Off
    val target = when (state) {
        DialState.On -> colors.Accent
        DialState.Busy -> colors.Accent2
        DialState.Broken -> colors.Warn
        DialState.Off -> colors.Dim2.copy(alpha = 0.55f)
    }
    val ring by animateColorAsState(target, tween(400))

    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (state == DialState.On) 16000 else 3600, easing = LinearEasing),
        ),
    )
    val breath by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Reverse),
    )

    Box(
        modifier = modifier.size(size).clickable(enabled = state != DialState.Busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val stroke = KDim.DialStroke.toPx()
            if (live) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ring.copy(alpha = 0.20f * breath), Color.Transparent),
                        center = center,
                        radius = r * 0.94f,
                    ),
                    radius = r,
                )
                rotate(angle) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(colors.Accent2, ring, colors.Accent2), center),
                        radius = r - stroke,
                        style = Stroke(width = stroke),
                    )
                }
            } else {
                drawCircle(color = ring, radius = r - stroke, style = Stroke(width = stroke))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (size > 190.dp) 19.sp else 16.sp,
                color = colors.Text,
                textAlign = TextAlign.Center,
            )
            if (!place.isNullOrBlank()) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!badge.isNullOrBlank()) {
                        KBadge(badge, small = true)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(text = place, fontFamily = Montserrat, fontSize = 13.sp, color = colors.Dim)
                }
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(text = meta, fontFamily = RobotoMono, fontSize = 11.sp, color = colors.Dim2)
            }
        }
    }
}

@Composable
fun KBadge(text: String, small: Boolean = false, accent: Boolean = false) {
    val colors = K
    val shape = RoundedCornerShape(if (small) 5.dp else 7.dp)
    Box(
        modifier = Modifier
            .size(width = if (small) 26.dp else 34.dp, height = if (small) 19.dp else 24.dp)
            .clip(shape)
            .background(colors.Surface2)
            .border(1.dp, if (accent) colors.Accent.copy(alpha = 0.45f) else colors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = RobotoMono,
            fontSize = if (small) 9.5.sp else 11.sp,
            color = if (accent) colors.Accent else colors.Dim,
        )
    }
}

@Composable
fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = K
    val shape = RoundedCornerShape(KDim.RadiusM)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.Surface)
            .border(BorderStroke(1.dp, colors.Border), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun KRowItem(
    title: String,
    subtitle: String? = null,
    label: String? = null,
    badge: String? = null,
    chevron: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = K
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badge != null) {
            KBadge(badge)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    text = label.uppercase(),
                    fontFamily = RobotoMono,
                    fontSize = 10.sp,
                    color = colors.Dim2,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = title,
                fontFamily = Montserrat,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colors.Text,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(text = subtitle, fontFamily = Montserrat, fontSize = 13.sp, color = colors.Dim)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (chevron) {
            Text(text = "›", fontSize = 20.sp, color = colors.Dim2)
        }
    }
}

@Composable
fun KGroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = RobotoMono,
        fontSize = 10.sp,
        color = K.Dim2,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun KHint(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = RobotoMono,
        fontSize = 10.sp,
        color = K.Dim2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
