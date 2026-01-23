package uz.shs.better_player_plus

import android.util.LongSparseArray
import androidx.media3.common.util.UnstableApi

/**
 * Registry for managing BetterPlayer instances.
 * 
 * This allows the platform view factory to access player instances
 * by their texture ID to attach SurfaceView for HDR playback.
 */
@UnstableApi
class BetterPlayerRegistry {
    private val players = LongSparseArray<BetterPlayer>()
    
    fun registerPlayer(textureId: Long, player: BetterPlayer) {
        players.put(textureId, player)
    }
    
    fun unregisterPlayer(textureId: Long) {
        players.remove(textureId)
    }
    
    fun getPlayer(textureId: Long): BetterPlayer? {
        return players.get(textureId)
    }
    
    fun clear() {
        players.clear()
    }
    
    val size: Int
        get() = players.size()
    
    fun valueAt(index: Int): BetterPlayer = players.valueAt(index)
    
    fun keyAt(index: Int): Long = players.keyAt(index)
}
