package xyz.a202132.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import xyz.a202132.app.R
import xyz.a202132.app.data.model.Node

@Composable
fun NodeIcon(
    node: Node?,
    size: Dp,
    flagFontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val flag = node?.getFlagEmojiOrNull()
    if (flag != null) {
        Text(
            text = flag,
            fontSize = flagFontSize,
            modifier = modifier
        )
    } else {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    }
}
