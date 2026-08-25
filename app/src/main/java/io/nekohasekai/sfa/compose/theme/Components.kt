package io.nekohasekai.sfa.compose.theme

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Состояние круга: от него зависит цвет кольца и свечение. */
enum class DialState { On, Off, Busy, Degraded, Broken }

/**
 * Круг-состояние. Тонкое кольцо с бегущим по нему градиентом и живое свечение
 * внутри: показывает, что сеть жива, не занимая места под цифры.
 */
@Composable
fun KDial(
    state: DialState,
    title: String,
    badge: String?,
    place: String?,
    meta: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = KDim.DialSize,
) {
    val colors = K
    val live = state != DialState.Off
    // Цвет кольца — семантика состояния, а не акцент. домашнихва сама по себе не значит
    // «подключено»: раньше этот смысл нёс мятный акцент, и с переходом на фирменный
    // цвет разница между «работает» и просто кнопкой пропала бы.
    val target = when (state) {
        DialState.On -> colors.Ok
        DialState.Busy -> colors.Accent
        DialState.Degraded -> colors.Warn
        DialState.Broken -> colors.Err
        DialState.Off -> colors.Dim2.copy(alpha = 0.55f)
    }
    val ring by animateColorAsState(target, tween(400), label = "ring")

    val transition = rememberInfiniteTransition(label = "dial")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (state == DialState.On) 16000 else 3600, easing = LinearEasing)),
        label = "angle",
    )
    val breath by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Reverse),
        label = "breath",
    )

    // Нажатие: круг должен нажиматься как круг и отвечать сразу.
    // Было — квадратная область (Box без обрезки), поэтому попадание в угол считалось
    // попаданием в кнопку, а отклика не было видно вовсе: подсветка рисовалась под
    // Canvas. Теперь область обрезана по кругу, а на нажатие круг заметно поджимается.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = tween(90),
        label = "squeeze",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(squeeze)
            .clip(CircleShape)
            .clickable(
                enabled = state != DialState.Busy,
                interactionSource = press,
                indication = ripple(bounded = true),
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            if (live) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ring.copy(alpha = 0.20f * breath), Color.Transparent),
                        center = center,
                        radius = r * 0.94f,
                    ),
                    radius = r,
                )
            }
            val stroke = KDim.DialStroke.toPx()
            if (live) {
                rotate(angle) {
                    drawCircle(
                        // Бегущий блик делаем ступенью нейтральных токенов, а не
                        // вторым акцентом: он каноном запрещён, а на одном цвете
                        // кольцо слилось бы в ровное пятно.
                        brush = Brush.sweepGradient(
                            listOf(colors.Border, ring, colors.Border),
                            center = center,
                        ),
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
                letterSpacing = (-0.2).sp,
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
                    Text(
                        text = place,
                        fontFamily = Montserrat,
                        fontSize = 13.sp,
                        color = colors.Dim,
                    )
                }
            }
            if (!meta.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = meta,
                    fontFamily = RobotoMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.4.sp,
                    color = colors.Dim2,
                )
            }
        }
    }
}

/** Код страны в рамке. Флаги-эмодзи не используем: рисуются не везде. */
@Composable
fun KBadge(text: String, small: Boolean = false, accent: Boolean = false) {
    val colors = K
    Box(
        modifier = Modifier
            .size(width = if (small) 26.dp else 34.dp, height = if (small) 19.dp else 24.dp)
            .clip(RoundedCornerShape(if (small) 5.dp else 7.dp))
            .background(colors.Sunken)
            .border(
                1.dp,
                if (accent) colors.Accent.copy(alpha = 0.45f) else colors.Border,
                RoundedCornerShape(if (small) 5.dp else 7.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = RobotoMono,
            fontSize = if (small) 9.5.sp else 11.sp,
            letterSpacing = 0.4.sp,
            color = if (accent) colors.Accent else colors.Dim,
        )
    }
}

/** Карточка-контейнер: всё содержимое экранов живёт в них. */
@Composable
fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
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

/** Строка карточки: заголовок, подпись, справа значение или шеврон. */
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
        modifier = Modifier
            .fillMaxWidth()
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
                    letterSpacing = 1.2.sp,
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
                Text(
                    text = subtitle,
                    fontFamily = Montserrat,
                    fontSize = 13.sp,
                    color = colors.Dim,
                )
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

/** Разделитель внутри карточки. */
@Composable
fun KDivider() {
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(K.Border))
    Spacer(Modifier.height(14.dp))
}

/** Заголовок группы настроек. */
@Composable
fun KGroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = RobotoMono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        color = K.Dim2,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Тумблер в стиле продукта, без материаловского вида. */
@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = K
    val track by animateColorAsState(
        if (checked) colors.Accent.copy(alpha = 0.28f) else colors.Sunken,
        tween(200),
        label = "track",
    )
    val knob by animateColorAsState(if (checked) colors.Accent else colors.Dim2, tween(200), label = "knob")
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.dp, if (checked) colors.Accent else colors.Border, CircleShape)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(19.dp)
                .clip(CircleShape)
                .background(knob),
        )
    }
}

/** Главная кнопка. */
@Composable
fun KButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ghost: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = K
    val shape = RoundedCornerShape(KDim.RadiusM)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    ghost -> Color.Transparent
                    enabled -> colors.Accent
                    else -> colors.Sunken
                },
            )
            .then(if (ghost) Modifier.border(1.dp, colors.Border, shape) else Modifier)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = Montserrat,
            fontWeight = if (ghost) FontWeight.Normal else FontWeight.SemiBold,
            fontSize = 15.sp,
            color = when {
                ghost -> colors.Dim
                !enabled -> colors.Dim2
                else -> colors.AccentInk
            },
        )
    }
}

/** Вкладка нижней панели. */
data class KTab(val title: String, val icon: ImageVector)

/** Нижняя панель на две вкладки: сеть и настройки. */
@Composable
fun KTabBar(tabs: List<KTab>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = K
    // без отступа под системную полосу подписи вкладок уезжают под жест-бар
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.Surface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.Border))
        Row(
            modifier = Modifier.fillMaxWidth().height(66.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.title,
                        tint = if (active) colors.Accent else colors.Dim2,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = tab.title,
                        fontFamily = Montserrat,
                        fontSize = 11.sp,
                        color = if (active) colors.Accent else colors.Dim2,
                    )
                }
            }
        }
    }
}

/** Подпись под кругом. */
@Composable
fun KHint(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = RobotoMono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        color = K.Dim2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Шапка внутреннего экрана: название и возврат. */
@Composable
fun KScreenHeader(title: String, subtitle: String? = null, onBack: () -> Unit) {
    val colors = K
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = KDim.Pad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Назад",
            tint = colors.Text,
            modifier = Modifier.size(22.dp).clickable { onBack() },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontFamily = Montserrat,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = colors.Text,
            )
            if (subtitle != null) {
                Text(text = subtitle, fontFamily = Montserrat, fontSize = 12.sp, color = colors.Dim)
            }
        }
    }
}

/** Русское склонение: «1 активное», «2 активных», «5 активных». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
