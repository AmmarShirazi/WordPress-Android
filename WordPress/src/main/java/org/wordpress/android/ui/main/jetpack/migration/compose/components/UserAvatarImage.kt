package org.wordpress.android.ui.main.jetpack.migration.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.wordpress.android.R.dimen
import org.wordpress.android.R.drawable
import org.wordpress.android.R.string
import org.wordpress.android.ui.compose.unit.Margin.MediumLarge

@Composable
fun BoxScope.UserAvatarImage(
    avatarUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(avatarUrl)
            .crossfade(true)
            .build(),
        placeholder = painterResource(drawable.bg_rectangle_placeholder_globe_32dp),
        error = painterResource(drawable.bg_rectangle_placeholder_globe_32dp),
        contentDescription = stringResource(string.jp_migration_avatar_content_description),
        modifier = modifier
            .align(Alignment.TopEnd)
            .padding(top = MediumLarge.value, end = 30.dp)
            .size(dimensionResource(dimen.jp_migration_user_avatar_size))
            .clip(CircleShape)
            .background(MaterialTheme.colors.surface)
            .clickable { onClick() }
    )
}
