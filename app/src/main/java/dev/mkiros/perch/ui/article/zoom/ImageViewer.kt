package dev.mkiros.perch.ui.article.zoom

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.toSize
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import dev.mkiros.perch.R
import dev.mkiros.perch.ui.article.ArticleTestTags
import dev.mkiros.perch.ui.theme.Dimens
import dev.mkiros.perch.ui.theme.ViewerColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The image the viewer is showing — url and alt text, nothing else.
 *
 * A value with a [Saver] because the *target* has to survive process death even though the
 * zoom does not: coming back to an article and finding a figure open, at fit, is right;
 * coming back and finding the article scrolled to the top because the overlay was the thing
 * that got restored is not.
 */
data class ZoomedImage(val url: String, val alt: String?) {

    companion object {
        val Saver: Saver<ZoomedImage?, Any> = Saver(
            save = { it?.let { image -> listOf(image.url, image.alt) } ?: emptyList<String?>() },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val fields = saved as List<String?>
                fields.firstOrNull()?.let { url -> ZoomedImage(url, fields.getOrNull(1)) }
            },
        )
    }
}

/**
 * A figure, full screen, zoomable (U12).
 *
 * An overlay rather than a destination: it is drawn over the article it came from, in the
 * same composition, so the reading position behind it is not something that has to be
 * *restored* on the way out — it was never torn down. That is also why back is handled
 * here. This handler is composed deeper than the root back chain's, so it is reached first
 * and [dev.mkiros.perch.ui.nav.BackStep.CloseImageViewer] is the rung it satisfies.
 *
 * Three behaviours are load-bearing, all from `saket/telephoto`'s hard-won list:
 * zoom is clamped with a rubber band rather than a wall, pan is fenced to the scaled image
 * so it cannot be flung into empty space, and **drag-to-dismiss only exists at fit** —
 * otherwise panning a zoomed image fights the dismiss gesture, which is the single most
 * common way this feature ships broken.
 */
@Composable
fun ImageViewer(
    image: ZoomedImage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    state: ZoomState = remember { ZoomState() },
) {
    BackHandler { onDismiss() }

    val scope = rememberCoroutineScope()
    var animation: Job? by remember { mutableStateOf(null) }

    // The image arrives rather than appears: it fades up from just under its fitted size
    // while the article behind it goes to scrim, which is what makes the overlay read as
    // the same figure enlarged instead of as a different screen.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(ENTRANCE_MS)) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(ArticleTestTags.IMAGE_VIEWER)
            .onSizeChanged { state.measure(it.toSize()) }
            .drawBehind {
                // Proportional to the drag: letting the article show through as the image
                // is pulled away is what tells the reader the gesture is a dismissal
                // before they have committed to it.
                drawRect(ViewerColors.scrim, alpha = SCRIM_ALPHA * entrance.value * (1f - state.dismissProgress))
            }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onDismiss() },
                    onDoubleTap = { at ->
                        animation?.cancel()
                        animation = scope.launch { state.onDoubleTap(at) }
                    },
                )
            }
            .pointerInput(state) {
                detectTransformGestures(
                    onStart = { animation?.cancel() },
                    onGesture = state::onGesture,
                    onEnd = {
                        animation?.cancel()
                        animation = scope.launch {
                            if (state.shouldDismiss()) onDismiss() else state.settle()
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = image.alt,
            contentScale = ContentScale.Fit,
            onState = { loaded ->
                state.onContentAspect((loaded as? AsyncImagePainter.State.Success)?.painter?.aspect())
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val entering = ENTRANCE_SCALE + (1f - ENTRANCE_SCALE) * entrance.value
                    scaleX = state.scale * entering
                    scaleY = state.scale * entering
                    translationX = state.offset.x + state.dismissOffset.x
                    translationY = state.offset.y + state.dismissOffset.y
                    alpha = entrance.value
                }
                .testTag(ArticleTestTags.IMAGE_VIEWER_IMAGE),
        )

        // The viewer's furniture, and only the furniture, lives inside `safeDrawing`
        // (V04). The figure above deliberately fills the screen — an image inset from the
        // status bar is a smaller image, not a safer one — but the app is edge-to-edge and
        // this overlay is a sibling of the article's `Scaffold` (U12), so nothing else is
        // going to keep a control out from under a notch. One box, so every control added
        // here lands in the safe area by default rather than by remembering to.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Tap-anywhere already closes the viewer, but only a reader who tries it knows
            // that; the button is the affordance that says so.
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(contentColor = ViewerColors.onScrim),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Dimens.sm)
                    .testTag(ArticleTestTags.IMAGE_VIEWER_CLOSE),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.image_viewer_close),
                    modifier = Modifier.size(Dimens.icon),
                )
            }
        }
    }
}

private fun Painter.aspect(): Float? {
    val size = intrinsicSize
    return if (size.isEmpty() || !size.width.isFinite() || !size.height.isFinite()) {
        null
    } else {
        size.width / size.height
    }
}

/**
 * Compose's `detectTransformGestures`, plus the two callbacks it does not have.
 *
 * The stock detector reports every frame of a pinch and says nothing about the gesture
 * *ending* — but the end is where the rubber band springs back and where a drag decides
 * whether it was a dismissal, so a viewer built on the stock detector has no way to finish
 * a gesture. The slop handling below is the stock detector's, unchanged: it exists so a tap
 * with a shaky thumb is still a tap.
 */
private suspend fun PointerInputScope.detectTransformGestures(
    onStart: () -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoomSinceDown = 1f
        var panSinceDown = Offset.Zero
        var pastSlop = false
        var started = false
        val slop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()

                if (!pastSlop) {
                    zoomSinceDown *= zoom
                    panSinceDown += pan
                    val spread = event.calculateCentroidSize(useCurrent = false)
                    pastSlop = abs(1 - zoomSinceDown) * spread > slop ||
                        panSinceDown.getDistance() > slop
                }

                if (pastSlop) {
                    if (!started) {
                        started = true
                        onStart()
                    }
                    if (zoom != 1f || pan != Offset.Zero) {
                        onGesture(event.calculateCentroid(useCurrent = false), pan, zoom)
                    }
                    // Claiming the movement is what tells the tap detector alongside this
                    // one that the gesture stopped being a tap.
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })

        if (started) onEnd()
    }
}

/** Dark enough that the article is gone, not so dark that the dismiss drag reveals nothing. */
private const val SCRIM_ALPHA = 0.94f

private const val ENTRANCE_MS = 180
private const val ENTRANCE_SCALE = 0.92f
