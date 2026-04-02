package ro.eidkit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.eidkit.app.R

@Composable
fun BoxScope.DemoRibbon() {
    val statusBarHeight = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }
    Text(
        text = stringResource(R.string.demo_ribbon),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = statusBarHeight + 6.dp, end = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFCC0000))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
