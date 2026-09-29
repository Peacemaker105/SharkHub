package com.chris.sharkhub.ui.dash

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures the dashboard uses besides the rendered truck layers: a small plate of the truck on its
 * highway for the Vehicle card, and the page backdrops. Backdrops are decoded heavily downsampled
 * on purpose — drawn back up to the screen they come out soft, which is the blur we want and the
 * head unit (API 30) has no RenderEffect to do it live.
 */
fun loadSceneCard(ctx: Context): ImageBitmap? = decodeAsset(ctx, "car/v1_scene.jpg", 2)

fun loadBackdrop(ctx: Context, kind: HomeBackdrop): ImageBitmap? = when (kind) {
    HomeBackdrop.NONE, HomeBackdrop.WAVES -> null
    HomeBackdrop.HIGHWAY -> decodeAsset(ctx, "car/v1_bg.png", 8)
    HomeBackdrop.TRUCK -> decodeAsset(ctx, "car/v1_scene.jpg", 4)
}

private fun decodeAsset(ctx: Context, name: String, sample: Int): ImageBitmap? = runCatching {
    ctx.assets.open(name).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}.getOrNull()?.asImageBitmap()

/** Loads off the main thread; null until it's there (and forever if the asset is missing). */
@Composable
fun rememberHomeBitmap(key: Any?, load: (Context) -> ImageBitmap?): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(null, key) { value = withContext(Dispatchers.IO) { load(ctx) } }.value
}
