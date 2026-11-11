package com.music.spotui.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Compact, rounded outline devices shared by every player surface. */
object PlaybackDeviceIcons {
    private fun outline(name:String,draw:androidx.compose.ui.graphics.vector.PathBuilder.()->Unit):ImageVector =
        ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.8f,
                strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,pathBuilder=draw)
        }.build()
    val Phone=outline("PlaybackPhone") {
        moveTo(7.3f,2.8f);lineTo(16.7f,2.8f);curveTo(17.7f,2.8f,18.2f,3.4f,18.2f,4.3f)
        lineTo(18.2f,19.7f);curveTo(18.2f,20.6f,17.7f,21.2f,16.7f,21.2f)
        lineTo(7.3f,21.2f);curveTo(6.3f,21.2f,5.8f,20.6f,5.8f,19.7f)
        lineTo(5.8f,4.3f);curveTo(5.8f,3.4f,6.3f,2.8f,7.3f,2.8f);close()
        moveTo(10.4f,18.3f);lineTo(13.6f,18.3f)
    }
    val Tablet=outline("PlaybackTablet") {
        moveTo(5.3f,2.6f);lineTo(18.7f,2.6f);curveTo(19.7f,2.6f,20.2f,3.2f,20.2f,4.2f)
        lineTo(20.2f,19.8f);curveTo(20.2f,20.8f,19.7f,21.4f,18.7f,21.4f)
        lineTo(5.3f,21.4f);curveTo(4.3f,21.4f,3.8f,20.8f,3.8f,19.8f)
        lineTo(3.8f,4.2f);curveTo(3.8f,3.2f,4.3f,2.6f,5.3f,2.6f);close()
        moveTo(10.5f,18.5f);lineTo(13.5f,18.5f)
    }
    val Speaker=outline("PlaybackSpeaker") {
        moveTo(7f,2.8f);lineTo(17f,2.8f);curveTo(18f,2.8f,18.5f,3.4f,18.5f,4.4f)
        lineTo(18.5f,19.6f);curveTo(18.5f,20.6f,18f,21.2f,17f,21.2f)
        lineTo(7f,21.2f);curveTo(6f,21.2f,5.5f,20.6f,5.5f,19.6f)
        lineTo(5.5f,4.4f);curveTo(5.5f,3.4f,6f,2.8f,7f,2.8f);close()
        moveTo(15.2f,14.2f);curveTo(15.2f,16f,13.8f,17.4f,12f,17.4f)
        curveTo(10.2f,17.4f,8.8f,16f,8.8f,14.2f)
        curveTo(8.8f,12.4f,10.2f,11f,12f,11f)
        curveTo(13.8f,11f,15.2f,12.4f,15.2f,14.2f);close()
        moveTo(11.5f,6.9f);lineTo(12.5f,6.9f)
    }
    val Headphones=outline("PlaybackHeadphones") {
        moveTo(4f,13.5f);lineTo(4f,11f);curveTo(4f,6.2f,7.1f,3.2f,12f,3.2f)
        curveTo(16.9f,3.2f,20f,6.2f,20f,11f);lineTo(20f,13.5f)
        moveTo(4f,12.2f);lineTo(6.2f,12.2f);lineTo(6.2f,20.2f);lineTo(4f,20.2f)
        curveTo(2.8f,20.2f,2.2f,19.4f,2.2f,18.2f);lineTo(2.2f,14.2f)
        curveTo(2.2f,13f,2.8f,12.2f,4f,12.2f);close()
        moveTo(20f,12.2f);lineTo(17.8f,12.2f);lineTo(17.8f,20.2f);lineTo(20f,20.2f)
        curveTo(21.2f,20.2f,21.8f,19.4f,21.8f,18.2f);lineTo(21.8f,14.2f)
        curveTo(21.8f,13f,21.2f,12.2f,20f,12.2f);close()
    }
}
