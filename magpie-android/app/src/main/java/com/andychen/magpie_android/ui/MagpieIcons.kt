package com.andychen.magpie_android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// The main navigation paths follow the phone GUI. See assets/licenses/magpie.txt.
internal object MagpieIcons {
    val Agents = lineIcon("Agents", "M4 6l4 4-4 4M10 14h6")
    val Sessions = lineIcon("Sessions", "M4 5h12v8H9l-4 3v-3H4z")
    val Library = lineIcon("Library", "M5 3.5h9v13H5a1.5 1.5 0 0 1 0-3h9")
    val Providers = lineIcon("Providers", "M10 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM10 10h7M14 10v3M16.5 10v2")
    val Routing = lineIcon("Routing", "M3 10h5l4-5h5M8 10l4 5h5M14.5 3l2.5 2-2.5 2M14.5 13l2.5 2-2.5 2")
    val Gateway = lineIcon("Gateway", "M4.5 4h11A1.5 1.5 0 0 1 17 5.5v2A1.5 1.5 0 0 1 15.5 9h-11A1.5 1.5 0 0 1 3 7.5v-2A1.5 1.5 0 0 1 4.5 4ZM4.5 11h11a1.5 1.5 0 0 1 1.5 1.5v2a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 3 14.5v-2A1.5 1.5 0 0 1 4.5 11ZM6 6.5h.01M6 13.5h.01")
    val Usage = lineIcon("Usage", "M4 16V9M8 16V5M12 16v-5M16 16V7")
    val Masking = lineIcon("Masking", "M10 2.4 16 4.7v4.7c0 3.8-2.6 6.8-6 8.2-3.4-1.4-6-4.4-6-8.2V4.7Z")
    val Plugins = lineIcon("Plugins", "M3.5 3.5h6v2.7a1.5 1.5 0 1 0 3 0V3.5h4v13h-13v-4h2.7a1.5 1.5 0 1 0 0-3H3.5Z")
    val Settings = lineIcon("Settings", "M8.8 3h2.4l.4 2 1.5.9 2-.7 1.2 2.1-1.6 1.3v1.8l1.6 1.3-1.2 2.1-2-.7-1.5.9-.4 2H8.8l-.4-2-1.5-.9-2 .7-1.2-2.1 1.6-1.3V8.6L3.7 7.3l1.2-2.1 2 .7 1.5-.9zM12 9.5a2 2 0 1 0-4 0 2 2 0 0 0 4 0Z")
    val Back = lineIcon("Back", "M12 4 6 10l6 6")
    val Next = lineIcon("Next", "M8 5l5 5-5 5")
    val Down = lineIcon("Down", "M5.5 8l4.5 4.5L14.5 8")
    val Search = lineIcon("Search", "M13.5 8.5a5 5 0 1 1-10 0 5 5 0 0 1 10 0ZM12 12l4.5 4.5")
    val Check = lineIcon("Check", "m4 10 4 4 8-8")
    val More = lineIcon("More", "M4.5 10h.01M10 10h.01M15.5 10h.01", stroke = 3f)
}

private fun lineIcon(name: String, path: String, stroke: Float = 1.6f): ImageVector =
    ImageVector.Builder(name, 20.dp, 20.dp, 20f, 20f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        .build()
