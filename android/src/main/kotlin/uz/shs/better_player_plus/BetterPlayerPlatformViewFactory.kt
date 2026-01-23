package uz.shs.better_player_plus

import android.content.Context
import androidx.media3.common.util.UnstableApi
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Factory for creating BetterPlayerPlatformView instances.
 * 
 * This factory is registered with Flutter to create platform views
 * on demand when AndroidView widget is used with the view type
 * 'better_player_plus/hdr_player_view'.
 */
@UnstableApi
class BetterPlayerPlatformViewFactory(
    private val playerRegistry: BetterPlayerRegistry
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val creationParams = args as? Map<*, *>
        val textureId = (creationParams?.get("textureId") as? Number)?.toLong() ?: -1L
        
        return BetterPlayerPlatformView(context, textureId, playerRegistry)
    }
}
