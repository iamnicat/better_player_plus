// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package uz.shs.better_player_plus

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LongSparseArray
import android.util.Rational
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import uz.shs.better_player_plus.BetterPlayerCache.releaseCache
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.view.TextureRegistry
import java.lang.Exception
import java.util.HashMap
import androidx.core.util.size

/**
 * Android platform implementation of the VideoPlayerPlugin.
 */
@OptIn(UnstableApi::class)
class BetterPlayerPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    private val playerRegistry = BetterPlayerRegistry()
    private val dataSources = LongSparseArray<Map<String, Any?>>()
    private var flutterState: FlutterState? = null
    private var currentNotificationTextureId: Long = -1
    private var currentNotificationDataSource: Map<String, Any?>? = null
    private var activity: Activity? = null
    private var pipHandler: Handler? = null
    private var pipRunnable: Runnable? = null
    override fun onAttachedToEngine(binding: FlutterPluginBinding) {
        val loader = FlutterLoader()
        flutterState = FlutterState(
            binding.applicationContext,
            binding.binaryMessenger, object : KeyForAssetFn {
                override fun get(asset: String?): String {
                    return loader.getLookupKeyForAsset(
                        asset!!
                    )
                }

            }, object : KeyForAssetAndPackageName {
                override fun get(asset: String?, packageName: String?): String {
                    return loader.getLookupKeyForAsset(
                        asset!!, packageName!!
                    )
                }
            },
            binding.textureRegistry
        )
        flutterState?.startListening(this)
        
        // Register platform view factory for HDR playback
        binding.platformViewRegistry.registerViewFactory(
            "better_player_plus/hdr_player_view",
            BetterPlayerPlatformViewFactory(playerRegistry)
        )
    }


    @OptIn(UnstableApi::class)
    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {
        if (flutterState == null) {
            Log.wtf(TAG, "Detached from the engine before registering to it.")
        }
        disposeAllPlayers()
        releaseCache()
        flutterState?.stopListening()
        flutterState = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        // Configure window for wide color gamut (HDR) on Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Use the constant value directly (2 = COLOR_MODE_WIDE_COLOR_GAMUT)
                val colorModeWideColorGamut = 2
                binding.activity.window.colorMode = colorModeWideColorGamut
                Log.d(TAG, "Configured window for wide color gamut (HDR support)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure window for wide color gamut", e)
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivity() {}

    @UnstableApi
    private fun disposeAllPlayers() {
        for (i in 0 until playerRegistry.size) {
            playerRegistry.valueAt(i).dispose()
        }
        playerRegistry.clear()
        dataSources.clear()
    }

    @UnstableApi
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (flutterState == null || flutterState?.textureRegistry == null) {
            result.error("no_activity", "better_player plugin requires a foreground activity", null)
            return
        }
        when (call.method) {
            INIT_METHOD -> disposeAllPlayers()
            CREATE_METHOD -> {
                val handle = flutterState!!.textureRegistry!!.createSurfaceTexture()
                val eventChannel = EventChannel(
                    flutterState?.binaryMessenger, EVENTS_CHANNEL + handle.id()
                )
                var customDefaultLoadControl: CustomDefaultLoadControl? = null
                if (call.hasArgument(MIN_BUFFER_MS) && call.hasArgument(MAX_BUFFER_MS) &&
                    call.hasArgument(BUFFER_FOR_PLAYBACK_MS) &&
                    call.hasArgument(BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                ) {
                    customDefaultLoadControl = CustomDefaultLoadControl(
                        call.argument(MIN_BUFFER_MS),
                        call.argument(MAX_BUFFER_MS),
                        call.argument(BUFFER_FOR_PLAYBACK_MS),
                        call.argument(BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                    )
                }
                val player = BetterPlayer(
                    flutterState?.applicationContext!!, eventChannel, handle,
                    customDefaultLoadControl, result
                )
                playerRegistry.registerPlayer(handle.id(), player)
            }

            PRE_CACHE_METHOD -> preCache(call, result)
            STOP_PRE_CACHE_METHOD -> stopPreCache(call, result)
            CLEAR_CACHE_METHOD -> clearCache(result)
            else -> {
                if (call.argument<Any>(TEXTURE_ID_PARAMETER) == null) {
//                    result.error(
//                        "Unknown textureId",
//                        "No video player associated with texture id",
//                        null
//                    )
                    return
                }
                val textureId = ((call.argument<Any>(TEXTURE_ID_PARAMETER) as Int?) ?: 0).toLong()
                val player = playerRegistry.getPlayer(textureId)
                if (player == null) {
                    result.error(
                        "Unknown textureId",
                        "No video player associated with texture id $textureId",
                        null
                    )
                    return
                }
                onMethodCall(call, result, textureId, player)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
        textureId: Long,
        player: BetterPlayer
    ) {
        when (call.method) {
            SET_DATA_SOURCE_METHOD -> {
                setDataSource(call, result, player)
            }

            SET_LOOPING_METHOD -> {
                player.setLooping(call.argument(LOOPING_PARAMETER)!!)
                result.success(null)
            }

            SET_VOLUME_METHOD -> {
                player.setVolume(call.argument(VOLUME_PARAMETER)!!)
                result.success(null)
            }

            PLAY_METHOD -> {
                setupNotification(player)
                player.play()
                result.success(null)
            }

            PAUSE_METHOD -> {
                player.pause()
                result.success(null)
            }

            SEEK_TO_METHOD -> {
                val location = (call.argument<Any>(LOCATION_PARAMETER) as Number?)!!.toInt()
                player.seekTo(location)
                result.success(null)
            }

            POSITION_METHOD -> {
                result.success(player.position)
                player.sendBufferingUpdate(false)
            }

            ABSOLUTE_POSITION_METHOD -> result.success(player.absolutePosition)
            SET_SPEED_METHOD -> {
                player.setSpeed(call.argument(SPEED_PARAMETER)!!)
                result.success(null)
            }

            SET_TRACK_PARAMETERS_METHOD -> {
                player.setTrackParameters(
                    call.argument(WIDTH_PARAMETER)!!,
                    call.argument(HEIGHT_PARAMETER)!!,
                    call.argument(BITRATE_PARAMETER)!!
                )
                result.success(null)
            }

            ENABLE_PICTURE_IN_PICTURE_METHOD -> {
                enablePictureInPicture(player)
                result.success(null)
            }

            DISABLE_PICTURE_IN_PICTURE_METHOD -> {
                disablePictureInPicture(player)
                result.success(null)
            }

            IS_PICTURE_IN_PICTURE_SUPPORTED_METHOD -> result.success(
                isPictureInPictureSupported()
            )

            SET_AUDIO_TRACK_METHOD -> {
                val name = call.argument<String?>(NAME_PARAMETER)
                val index = call.argument<Int?>(INDEX_PARAMETER)
                if (name != null && index != null) {
                    player.setAudioTrack(name, index)
                }
                result.success(null)
            }

            SET_MIX_WITH_OTHERS_METHOD -> {
                val mixWitOthers = call.argument<Boolean?>(
                    MIX_WITH_OTHERS_PARAMETER
                )
                if (mixWitOthers != null) {
                    player.setMixWithOthers(mixWitOthers)
                }
            }

            IS_HDR_SUPPORTED_METHOD -> {
                result.success(isHdrSupported())
            }

            GET_SUPPORTED_HDR_FORMATS_METHOD -> {
                result.success(getSupportedHdrFormats())
            }

            IS_WIDE_COLOR_GAMUT_SUPPORTED_METHOD -> {
                result.success(isWideColorGamutSupported())
            }

            GET_VIDEO_METADATA_METHOD -> {
                result.success(player.getVideoMetadata())
            }

            IS_HDR_VIDEO_METHOD -> {
                val videoPath = call.argument<String>(VIDEO_PATH_PARAMETER)
                val isHdr = isHdrVideo(videoPath ?: "")
                result.success(isHdr)
            }

            DISPOSE_METHOD -> {
                dispose(player, textureId)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    @OptIn(UnstableApi::class)
    private fun setDataSource(
        call: MethodCall,
        result: MethodChannel.Result,
        player: BetterPlayer
    ) {
        val dataSource = call.argument<Map<String, Any?>>(DATA_SOURCE_PARAMETER)!!
        dataSources.put(getTextureId(player)!!, dataSource)
        val key = getParameter(dataSource, KEY_PARAMETER, "")
        val headers: Map<String, String> = getParameter(dataSource, HEADERS_PARAMETER, HashMap())
        val overriddenDuration: Number = getParameter(dataSource, OVERRIDDEN_DURATION_PARAMETER, 0)
        if (dataSource[ASSET_PARAMETER] != null) {
            val asset = getParameter(dataSource, ASSET_PARAMETER, "")
            val assetLookupKey: String = if (dataSource[PACKAGE_PARAMETER] != null) {
                val packageParameter = getParameter(
                    dataSource,
                    PACKAGE_PARAMETER,
                    ""
                )
                flutterState!!.keyForAssetAndPackageName[asset, packageParameter]
            } else {
                flutterState!!.keyForAsset[asset]
            }
            player.setDataSource(
                flutterState?.applicationContext!!,
                key,
                "asset:///$assetLookupKey",
                null,
                result,
                headers,
                false,
                0L,
                0L,
                overriddenDuration.toLong(),
                null,
                null, null, null
            )
        } else {
            val useCache = getParameter(dataSource, USE_CACHE_PARAMETER, false)
            val maxCacheSizeNumber: Number = getParameter(dataSource, MAX_CACHE_SIZE_PARAMETER, 0)
            val maxCacheFileSizeNumber: Number =
                getParameter(dataSource, MAX_CACHE_FILE_SIZE_PARAMETER, 0)
            val maxCacheSize = maxCacheSizeNumber.toLong()
            val maxCacheFileSize = maxCacheFileSizeNumber.toLong()
            val uri = getParameter(dataSource, URI_PARAMETER, "")
            val cacheKey = getParameter<String?>(dataSource, CACHE_KEY_PARAMETER, null)
            val formatHint = getParameter<String?>(dataSource, FORMAT_HINT_PARAMETER, null)
            val licenseUrl = getParameter<String?>(dataSource, LICENSE_URL_PARAMETER, null)
            val clearKey = getParameter<String?>(dataSource, DRM_CLEARKEY_PARAMETER, null)
            val drmHeaders: Map<String, String> =
                getParameter(dataSource, DRM_HEADERS_PARAMETER, HashMap())
            player.setDataSource(
                flutterState!!.applicationContext,
                key,
                uri,
                formatHint,
                result,
                headers,
                useCache,
                maxCacheSize,
                maxCacheFileSize,
                overriddenDuration.toLong(),
                licenseUrl,
                drmHeaders,
                cacheKey,
                clearKey
            )
        }
    }

    /**
     * Start pre cache of video.
     *
     * @param call   - invoked method data
     * @param result - result which should be updated
     */
    @OptIn(UnstableApi::class)
    private fun preCache(call: MethodCall, result: MethodChannel.Result) {
        val dataSource = call.argument<Map<String, Any?>>(DATA_SOURCE_PARAMETER)
        if (dataSource != null) {
            val maxCacheSizeNumber: Number =
                getParameter(dataSource, MAX_CACHE_SIZE_PARAMETER, 100 * 1024 * 1024)
            val maxCacheFileSizeNumber: Number =
                getParameter(dataSource, MAX_CACHE_FILE_SIZE_PARAMETER, 10 * 1024 * 1024)
            val maxCacheSize = maxCacheSizeNumber.toLong()
            val maxCacheFileSize = maxCacheFileSizeNumber.toLong()
            val preCacheSizeNumber: Number =
                getParameter(dataSource, PRE_CACHE_SIZE_PARAMETER, 3 * 1024 * 1024)
            val preCacheSize = preCacheSizeNumber.toLong()
            val uri = getParameter(dataSource, URI_PARAMETER, "")
            val cacheKey = getParameter<String?>(dataSource, CACHE_KEY_PARAMETER, null)
            val headers: Map<String, String> =
                getParameter(dataSource, HEADERS_PARAMETER, HashMap())
            BetterPlayer.preCache(
                flutterState?.applicationContext,
                uri,
                preCacheSize,
                maxCacheSize,
                maxCacheFileSize,
                headers,
                cacheKey,
                result
            )
        }
    }

    /**
     * Stop pre cache video process (if exists).
     *
     * @param call   - invoked method data
     * @param result - result which should be updated
     */
    @UnstableApi
    private fun stopPreCache(call: MethodCall, result: MethodChannel.Result) {
        val url = call.argument<String>(URL_PARAMETER)
        BetterPlayer.stopPreCache(flutterState?.applicationContext, url, result)
    }

    @UnstableApi
    private fun clearCache(result: MethodChannel.Result) {
        BetterPlayer.clearCache(flutterState?.applicationContext, result)
    }

    @OptIn(UnstableApi::class)
    private fun getTextureId(betterPlayer: BetterPlayer): Long? {
        for (index in 0 until playerRegistry.size) {
            if (betterPlayer === playerRegistry.valueAt(index)) {
                return playerRegistry.keyAt(index)
            }
        }
        return null
    }

    @OptIn(UnstableApi::class)
    private fun setupNotification(betterPlayer: BetterPlayer) {
        try {
            val textureId = getTextureId(betterPlayer)
            if (textureId != null) {
                val dataSource = dataSources[textureId]
                //Don't setup notification for the same source.
                if (textureId == currentNotificationTextureId && currentNotificationDataSource != null && dataSource != null && currentNotificationDataSource === dataSource) {
                    return
                }
                currentNotificationDataSource = dataSource
                currentNotificationTextureId = textureId
                removeOtherNotificationListeners()
                val showNotification = getParameter(dataSource, SHOW_NOTIFICATION_PARAMETER, false)
                if (showNotification) {
                    val title = getParameter(dataSource, TITLE_PARAMETER, "")
                    val author = getParameter(dataSource, AUTHOR_PARAMETER, "")
                    val imageUrl = getParameter(dataSource, IMAGE_URL_PARAMETER, "")
                    val notificationChannelName =
                        getParameter<String?>(dataSource, NOTIFICATION_CHANNEL_NAME_PARAMETER, null)
                    val activityName =
                        getParameter(dataSource, ACTIVITY_NAME_PARAMETER, "MainActivity")
                    betterPlayer.setupPlayerNotification(
                        flutterState?.applicationContext!!,
                        title, author, imageUrl, notificationChannelName, activityName
                    )
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "SetupNotification failed", exception)
        }
    }

    @OptIn(UnstableApi::class)
    private fun removeOtherNotificationListeners() {
        for (index in 0 until playerRegistry.size) {
            playerRegistry.valueAt(index).disposeRemoteNotifications()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getParameter(parameters: Map<String, Any?>?, key: String, defaultValue: T): T {
        if (parameters?.containsKey(key) == true) {
            val value = parameters[key]
            if (value != null) {
                return value as T
            }
        }
        return defaultValue
    }


    private fun isPictureInPictureSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && activity!!.packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun enablePictureInPicture(player: BetterPlayer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.setupMediaSession(flutterState!!.applicationContext)
            activity!!.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(
                Rational(16, 9)
            ).build())
            startPictureInPictureListenerTimer(player)
            player.onPictureInPictureStatusChanged(true)
        }
    }

    private fun disablePictureInPicture(player: BetterPlayer) {
        stopPipHandler()
        activity!!.moveTaskToBack(false)
        player.onPictureInPictureStatusChanged(false)
        player.disposeMediaSession()
    }

    private fun startPictureInPictureListenerTimer(player: BetterPlayer) {
        pipHandler = Handler(Looper.getMainLooper())
        pipRunnable = Runnable {
            if (activity!!.isInPictureInPictureMode) {
                pipHandler!!.postDelayed(pipRunnable!!, 100)
            } else {
                player.onPictureInPictureStatusChanged(false)
                player.disposeMediaSession()
                stopPipHandler()
            }
        }
        pipHandler!!.post(pipRunnable!!)
    }

    private fun dispose(player: BetterPlayer, textureId: Long) {
        player.dispose()
        playerRegistry.unregisterPlayer(textureId)
        dataSources.remove(textureId)
        stopPipHandler()
    }

    private fun stopPipHandler() {
        if (pipHandler != null) {
            pipHandler!!.removeCallbacksAndMessages(null)
            pipHandler = null
        }
        pipRunnable = null
    }

    private interface KeyForAssetFn {
        operator fun get(asset: String?): String
    }

    private interface KeyForAssetAndPackageName {
        operator fun get(asset: String?, packageName: String?): String
    }

    private class FlutterState(
        val applicationContext: Context,
        val binaryMessenger: BinaryMessenger,
        val keyForAsset: KeyForAssetFn,
        val keyForAssetAndPackageName: KeyForAssetAndPackageName,
        val textureRegistry: TextureRegistry?
    ) {
        private val methodChannel: MethodChannel = MethodChannel(binaryMessenger, CHANNEL)

        fun startListening(methodCallHandler: BetterPlayerPlugin?) {
            methodChannel.setMethodCallHandler(methodCallHandler)
        }

        fun stopListening() {
            methodChannel.setMethodCallHandler(null)
        }

    }

    companion object {
        private const val TAG = "BetterPlayerPlugin"
        private const val CHANNEL = "better_player_channel"
        private const val EVENTS_CHANNEL = "better_player_channel/videoEvents"
        private const val DATA_SOURCE_PARAMETER = "dataSource"
        private const val KEY_PARAMETER = "key"
        private const val HEADERS_PARAMETER = "headers"
        private const val USE_CACHE_PARAMETER = "useCache"
        private const val ASSET_PARAMETER = "asset"
        private const val PACKAGE_PARAMETER = "package"
        private const val URI_PARAMETER = "uri"
        private const val FORMAT_HINT_PARAMETER = "formatHint"
        private const val TEXTURE_ID_PARAMETER = "textureId"
        private const val LOOPING_PARAMETER = "looping"
        private const val VOLUME_PARAMETER = "volume"
        private const val LOCATION_PARAMETER = "location"
        private const val SPEED_PARAMETER = "speed"
        private const val WIDTH_PARAMETER = "width"
        private const val HEIGHT_PARAMETER = "height"
        private const val BITRATE_PARAMETER = "bitrate"
        private const val SHOW_NOTIFICATION_PARAMETER = "showNotification"
        private const val TITLE_PARAMETER = "title"
        private const val AUTHOR_PARAMETER = "author"
        private const val IMAGE_URL_PARAMETER = "imageUrl"
        private const val NOTIFICATION_CHANNEL_NAME_PARAMETER = "notificationChannelName"
        private const val OVERRIDDEN_DURATION_PARAMETER = "overriddenDuration"
        private const val NAME_PARAMETER = "name"
        private const val INDEX_PARAMETER = "index"
        private const val LICENSE_URL_PARAMETER = "licenseUrl"
        private const val DRM_HEADERS_PARAMETER = "drmHeaders"
        private const val DRM_CLEARKEY_PARAMETER = "clearKey"
        private const val MIX_WITH_OTHERS_PARAMETER = "mixWithOthers"
        const val URL_PARAMETER = "url"
        const val PRE_CACHE_SIZE_PARAMETER = "preCacheSize"
        const val MAX_CACHE_SIZE_PARAMETER = "maxCacheSize"
        const val MAX_CACHE_FILE_SIZE_PARAMETER = "maxCacheFileSize"
        const val HEADER_PARAMETER = "header_"
        const val FILE_PATH_PARAMETER = "filePath"
        const val ACTIVITY_NAME_PARAMETER = "activityName"
        const val MIN_BUFFER_MS = "minBufferMs"
        const val MAX_BUFFER_MS = "maxBufferMs"
        const val BUFFER_FOR_PLAYBACK_MS = "bufferForPlaybackMs"
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = "bufferForPlaybackAfterRebufferMs"
        const val CACHE_KEY_PARAMETER = "cacheKey"
        private const val INIT_METHOD = "init"
        private const val CREATE_METHOD = "create"
        private const val SET_DATA_SOURCE_METHOD = "setDataSource"
        private const val SET_LOOPING_METHOD = "setLooping"
        private const val SET_VOLUME_METHOD = "setVolume"
        private const val PLAY_METHOD = "play"
        private const val PAUSE_METHOD = "pause"
        private const val SEEK_TO_METHOD = "seekTo"
        private const val POSITION_METHOD = "position"
        private const val ABSOLUTE_POSITION_METHOD = "absolutePosition"
        private const val SET_SPEED_METHOD = "setSpeed"
        private const val SET_TRACK_PARAMETERS_METHOD = "setTrackParameters"
        private const val SET_AUDIO_TRACK_METHOD = "setAudioTrack"
        private const val ENABLE_PICTURE_IN_PICTURE_METHOD = "enablePictureInPicture"
        private const val DISABLE_PICTURE_IN_PICTURE_METHOD = "disablePictureInPicture"
        private const val IS_PICTURE_IN_PICTURE_SUPPORTED_METHOD = "isPictureInPictureSupported"
        private const val SET_MIX_WITH_OTHERS_METHOD = "setMixWithOthers"
        private const val CLEAR_CACHE_METHOD = "clearCache"
        private const val DISPOSE_METHOD = "dispose"
        private const val PRE_CACHE_METHOD = "preCache"
        private const val STOP_PRE_CACHE_METHOD = "stopPreCache"
        private const val IS_HDR_SUPPORTED_METHOD = "isHdrSupported"
        private const val GET_SUPPORTED_HDR_FORMATS_METHOD = "getSupportedHdrFormats"
        private const val IS_WIDE_COLOR_GAMUT_SUPPORTED_METHOD = "isWideColorGamutSupported"
        private const val GET_VIDEO_METADATA_METHOD = "getVideoMetadata"
        private const val IS_HDR_VIDEO_METHOD = "isHdrVideo"
        private const val VIDEO_PATH_PARAMETER = "videoPath"
    }

    private fun isHdrSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.let { act ->
                val display = act.windowManager.defaultDisplay
                val hdrCapabilities = display.hdrCapabilities
                val hasDisplayHdrSupport = hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
                
                // Also check if device has HDR codec support
                val hasCodecHdrSupport = checkCodecHdrSupport()
                
                // Device supports HDR if both display and codec support it
                hasDisplayHdrSupport && hasCodecHdrSupport
            } ?: false
        } else {
            false
        }
    }
    
    /**
     * Check if device has codec support for HDR formats.
     * This verifies that the device can actually decode HDR content.
     */
    private fun checkCodecHdrSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }
        
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfos = codecList.codecInfos
            
            for (codecInfo in codecInfos) {
                if (codecInfo.isEncoder) continue
                
                val supportedTypes = codecInfo.supportedTypes
                for (mimeType in supportedTypes) {
                    // Check for HDR-capable codecs
                    if (mimeType.startsWith("video/hevc") || 
                        mimeType.startsWith("video/avc") ||
                        mimeType.startsWith("video/vp9")) {
                        
                        try {
                            val codecCapabilities = codecInfo.getCapabilitiesForType(mimeType)
                            val profileLevels = codecCapabilities.profileLevels
                            
                            // Check for HDR profiles
                            for (profileLevel in profileLevels) {
                                val profile = profileLevel.profile
                                // HEVC Main 10 Profile (HDR10)
                                if (mimeType.startsWith("video/hevc") && 
                                    (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                     profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                     profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)) {
                                    return true
                                }
                                // AVC High 10 Profile (for Dolby Vision)
                                if (mimeType.startsWith("video/avc") &&
                                    profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) {
                                    return true
                                }
                                // VP9 Profile 2 (HDR)
                                if (mimeType.startsWith("video/vp9") &&
                                    (profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR ||
                                     profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus)) {
                                    return true
                                }
                            }
                        } catch (e: Exception) {
                            // Continue checking other codecs
                            continue
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking codec HDR support", e)
        }
        
        return false
    }

    private fun getSupportedHdrFormats(): List<String> {
        val formats = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activity?.let { act ->
                val display = act.windowManager.defaultDisplay
                val hdrCapabilities = display.hdrCapabilities
                hdrCapabilities?.supportedHdrTypes?.forEach { hdrType ->
                    when (hdrType) {
                        Display.HdrCapabilities.HDR_TYPE_HDR10 -> formats.add("HDR10")
                        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> formats.add("HDR10+")
                        Display.HdrCapabilities.HDR_TYPE_HLG -> formats.add("HLG")
                        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> formats.add("Dolby Vision")
                    }
                }
                
                // Also check codec support for additional HDR formats
                val codecHdrFormats = getCodecSupportedHdrFormats()
                formats.addAll(codecHdrFormats)
            }
        }
        return formats.toList()
    }
    
    /**
     * Get HDR formats supported by device codecs.
     * This provides additional HDR format detection beyond display capabilities.
     */
    private fun getCodecSupportedHdrFormats(): List<String> {
        val formats = mutableSetOf<String>()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return formats.toList()
        }
        
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val codecInfos = codecList.codecInfos
            
            for (codecInfo in codecInfos) {
                if (codecInfo.isEncoder) continue
                
                val supportedTypes = codecInfo.supportedTypes
                for (mimeType in supportedTypes) {
                    try {
                        val codecCapabilities = codecInfo.getCapabilitiesForType(mimeType)
                        val profileLevels = codecCapabilities.profileLevels
                        
                        for (profileLevel in profileLevels) {
                            val profile = profileLevel.profile
                            
                            // HEVC HDR profiles
                            if (mimeType.startsWith("video/hevc")) {
                                when (profile) {
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> {
                                        formats.add("HDR10")
                                    }
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> {
                                        formats.add("HDR10+")
                                    }
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> {
                                        // Main 10 profile can support HDR10
                                        formats.add("HDR10")
                                    }
                                }
                            }
                            
                            // VP9 HDR profiles
                            if (mimeType.startsWith("video/vp9")) {
                                when (profile) {
                                    MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR -> {
                                        formats.add("HLG")
                                    }
                                    MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus -> {
                                        formats.add("HDR10+")
                                    }
                                }
                            }
                            
                            // AVC High 10 (used for Dolby Vision)
                            if (mimeType.startsWith("video/avc") &&
                                profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) {
                                // Check if Dolby Vision is supported via display
                                // Codec alone doesn't indicate Dolby Vision, but High 10 is required
                            }
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting codec HDR formats", e)
        }
        
        return formats.toList()
    }

    private fun isWideColorGamutSupported(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.let { act ->
                val display = act.windowManager.defaultDisplay
                display.isWideColorGamut
            } ?: false
        } else {
            false
        }
    }

    /**
     * Detects if a video file has HDR metadata.
     * Uses MediaMetadataRetriever to check color space and transfer characteristics.
     * 
     * @param videoPath The path or URI to the video file
     * @return true if the video has HDR metadata, false otherwise
     */
    private fun isHdrVideo(videoPath: String): Boolean {
        if (videoPath.isEmpty()) {
            return false
        }
        
        val retriever = android.media.MediaMetadataRetriever()
        try {
            // Handle different URI types
            when {
                videoPath.startsWith("http://") || videoPath.startsWith("https://") -> {
                    // Network URL - MediaMetadataRetriever can handle this on some Android versions
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.setDataSource(videoPath, HashMap())
                    } else {
                        // For older versions, we can't easily check network URLs
                        // Return false and let runtime detection handle it
                        return false
                    }
                }
                videoPath.startsWith("file://") -> {
                    retriever.setDataSource(videoPath.removePrefix("file://"))
                }
                videoPath.startsWith("asset://") -> {
                    // Asset files - can't use MediaMetadataRetriever directly
                    // Return false and let runtime detection handle it
                    return false
                }
                else -> {
                    // Try as file path
                    try {
                        retriever.setDataSource(videoPath)
                    } catch (e: Exception) {
                        // If it fails, try as URI
                        retriever.setDataSource(flutterState?.applicationContext, android.net.Uri.parse(videoPath))
                    }
                }
            }
            
            // Check color space (BT2020 typically indicates HDR)
            // Use numeric constant (24 = METADATA_KEY_COLOR_SPACE) or try alternative approach
            val colorSpace = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Try using the constant value directly
                    retriever.extractMetadata(24) // METADATA_KEY_COLOR_SPACE
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
            
            // Check for HDR indicators in color space
            if (colorSpace != null) {
                val colorSpaceLower = colorSpace.lowercase()
                if (colorSpaceLower.contains("bt2020") || 
                    colorSpaceLower.contains("hdr") ||
                    colorSpaceLower.contains("rec2020")) {
                    return true
                }
            }
            
            // On Android 29+, we can check codec information
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Try to extract codec info using numeric constant (20 = METADATA_KEY_VIDEO_CODEC_MIME_TYPE)
                    val videoCodec = try {
                        retriever.extractMetadata(20) // METADATA_KEY_VIDEO_CODEC_MIME_TYPE
                    } catch (e: Exception) {
                        // Try alternative: METADATA_KEY_MIMETYPE (5)
                        try {
                            retriever.extractMetadata(5)
                        } catch (e2: Exception) {
                            null
                        }
                    }
                    
                    // Check codec for HDR indicators
                    if (videoCodec != null) {
                        val codecLower = videoCodec.lowercase()
                        // HEVC Main 10 Profile, VP9 Profile 2/3, etc. can indicate HDR
                        if (codecLower.contains("hevc") || codecLower.contains("h265")) {
                            // HEVC with Main 10 profile often indicates HDR
                            // We'll let runtime detection confirm
                        }
                    }
                } catch (e: Exception) {
                    // Continue with other checks
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting HDR video: ${e.message}", e)
            return false
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e)
            }
        }
    }
}