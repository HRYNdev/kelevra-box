package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.graphics.Color

/*
 * УСТАРЕВШАЯ ПАЛИТРА. Новый код сюда не смотрит.
 *
 * Это остатки первой темы приложения и палитры апстрима sing-box. Цвет теперь живёт
 * в одном месте — `design-tokens/src/main/kotlin/dev/hryn/kelevra/KTokens.kt`, откуда
 * его берут и телефон, и десктоп. Здесь константы переведены на те же токены, чтобы
 * случайное обращение не вернуло чужой цвет, и помечены устаревшими.
 *
 * Главная беда этих значений даже не оттенок, а то, что они не знают о теме: один
 * `Color` на светлую и на тёмную. Поэтому замена — не другой хекс, а роль из `K`.
 *
 * Файл оставлен на месте намеренно и не удалён.
 */

@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val KelevraAccent: Color = KDark.Accent

@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val KelevraAccentDark: Color = KLight.Accent

@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val KelevraAccentSoft: Color = KDark.Accent

@Deprecated("Поверхность темы", ReplaceWith("K.Surface"))
val KelevraSurface: Color = KDark.Surface

@Deprecated("Фон темы", ReplaceWith("K.Bg"))
val KelevraBackground: Color = KDark.Bg

@Deprecated("Поверхность темы", ReplaceWith("K.Surface"))
val KelevraCard: Color = KDark.Surface

// Цвета апстрима sing-box. Своего акцента у продукта два не бывает.
@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val SingBoxPrimary: Color = KDark.Accent

@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val SingBoxPrimaryDark: Color = KLight.Accent

@Deprecated("Акцент системы", ReplaceWith("K.Accent"))
val SingBoxPrimaryLight: Color = KDark.Accent

// Состояния службы: теперь это семантика темы, а не отдельные краски.
@Deprecated("Семантика «норма»", ReplaceWith("K.Ok"))
val ServiceRunning: Color = KDark.Ok

@Deprecated("Семантика «выключено»", ReplaceWith("K.Dim2"))
val ServiceStopped: Color = KDark.Dim2

@Deprecated("Семантика «ошибка»", ReplaceWith("K.Err"))
val ServiceError: Color = KDark.Err

@Deprecated("Семантика «норма»", ReplaceWith("K.Ok"))
val SuccessGreen: Color = KDark.Ok

@Deprecated("Семантика «внимание»", ReplaceWith("K.Warn"))
val WarningOrange: Color = KDark.Warn

@Deprecated("Семантика «ошибка»", ReplaceWith("K.Err"))
val ErrorRed: Color = KDark.Err

@Deprecated("Второго акцента в системе нет", ReplaceWith("K.Accent"))
val InfoBlue: Color = KDark.Accent

@Deprecated("Затравка Material You не используется", ReplaceWith("K.Accent"))
val SeedColor: Color = KLight.Accent

// Палитра терминала ANSI. Это не оформление продукта, а расшифровка кодов 31-37 в
// журнале ядра: смысл цвета задаёт сама строка лога. Дубль лежит в res/values/colors.xml,
// откуда его берёт ColorUtils.
val LogRed = Color(0xFFFF2158)
val LogGreen = Color(0xFF2ECC71)
val LogYellow = Color(0xFFE5E500)
val LogBlue = Color(0xFF3498DB)
val LogPurple = Color(0xFFE500E5)
val LogRedLight = Color(0xFFE91E63)
val LogBlueLight = Color(0xFF00A6B2)
val LogWhite = Color(0xFFECECEC)
