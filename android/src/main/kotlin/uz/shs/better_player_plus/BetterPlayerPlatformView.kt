package uz.shs.better_player_plus

import android.content.Context
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.util.UnstableApi
import io.flutter.plugin.platform.PlatformView

/**
 * Platform view that wraps a SurfaceView for HDR video playback.
 * 
 * Unlike TextureView (used by Flutter's Texture widget), SurfaceView supports
 * HDR rendering through the Android hardware compositor, enabling proper HDR
 * output on HDR-capable displays.
 */
@UnstableApi
class BetterPlayerPlatformView(
    context: Context,
    private val textureId: Long,
    private val playerRegistry: BetterPlayerRegistry
) : PlatformView {

    private val surfaceView: SurfaceView = SurfaceView(context)
    
    init {
        // Essential for SurfaceView to be visible in Flutter
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Attach the SurfaceView to the player if it exists
        playerRegistry.getPlayer(textureId)?.setHdrSurface(surfaceView)
    }

    override fun getView(): View = surfaceView

    override fun dispose() {
        // Detach the surface from the player
        playerRegistry.getPlayer(textureId)?.clearHdrSurface()
    }
    
    /**
     * Returns the SurfaceView for external attachment.
     */
    fun getSurfaceView(): SurfaceView = surfaceView
}
