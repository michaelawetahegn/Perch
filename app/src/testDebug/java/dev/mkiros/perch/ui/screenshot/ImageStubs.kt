package dev.mkiros.perch.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import coil.Coil
import coil.ImageLoader
import coil.map.Mapper
import coil.request.Options
import kotlinx.coroutines.Dispatchers

/**
 * What a stubbed URL becomes: a [width] × [height] bitmap, filled with [colour] when one
 * is given (transparent otherwise) and then handed to [draw]. A size is the whole point —
 * a row's footprint, a viewer's content aspect and a screenshot's slab are all decided by
 * it, and nothing here downloads anything.
 */
class StubImage(
    val width: Int,
    val height: Int,
    val colour: Int? = null,
    val draw: (Canvas.() -> Unit)? = null,
)

/**
 * Points Coil at [image] for the rest of the test: a URL becomes a [StubImage], or null
 * for a request that fails the way an unreachable image does. Coil never leaves the JVM.
 *
 * Undo it in `@After` with `Coil.reset()` — the loader is process-global.
 */
fun stubImages(context: Context, image: (String) -> StubImage?) {
    installImageLoader(
        ImageLoader.Builder(context).components { add(StubImageMapper(context, image)) },
    )
}

/**
 * Builds and installs [builder] with every dispatcher pinned to `Main.immediate`, so a
 * request finishes inside the same `waitForIdle` that let it start — for a test that
 * needs a component other than a stubbed image (an `Interceptor` faking a slow load).
 */
fun installImageLoader(builder: ImageLoader.Builder) {
    Coil.setImageLoader(
        builder
            .dispatcher(Dispatchers.Main.immediate)
            .fetcherDispatcher(Dispatchers.Main.immediate)
            .decoderDispatcher(Dispatchers.Main.immediate)
            .transformationDispatcher(Dispatchers.Main.immediate)
            .build(),
    )
}

private class StubImageMapper(
    private val context: Context,
    private val image: (String) -> StubImage?,
) : Mapper<String, Drawable> {
    override fun map(data: String, options: Options): Drawable? {
        val stub = image(data) ?: return null
        val bitmap = Bitmap.createBitmap(stub.width, stub.height, Bitmap.Config.ARGB_8888)
        stub.colour?.let(bitmap::eraseColor)
        stub.draw?.let { Canvas(bitmap).it() }
        return BitmapDrawable(context.resources, bitmap)
    }
}
