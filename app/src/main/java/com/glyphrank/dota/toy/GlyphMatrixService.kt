/*
 * Adapted from GlyphMatrixService.kt in Nothing's GlyphMatrix-Example-Project
 * (https://github.com/Nothing-Developer-Programme/GlyphMatrix-Example-Project).
 * Copyright (c) 2025 Nothing Technology Limited, MIT License.
 * Changes: added the AOD event, error-tolerant frame pushing, no-op defaults,
 * reconnect handling.
 */
package com.glyphrank.dota.toy

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphToy

/**
 * The system binds this service when the user selects the toy with the Glyph Button
 * and unbinds it when they move on. Glyph Button events arrive through the Messenger.
 */
abstract class GlyphMatrixService(private val tag: String) : Service() {

    private val eventHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != GlyphToy.MSG_GLYPH_TOY) {
                super.handleMessage(msg)
                return
            }
            val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
            Log.d(LOG_TAG, "$tag: event $event")
            when (event) {
                GlyphToy.EVENT_ACTION_DOWN -> onGlyphButtonDown()
                GlyphToy.EVENT_ACTION_UP -> onGlyphButtonUp()
                GlyphToy.EVENT_CHANGE -> onGlyphButtonLongPress()
                GlyphToy.EVENT_AOD -> onAodTick()
            }
        }
    }

    private val serviceMessenger = Messenger(eventHandler)

    var glyphMatrixManager: GlyphMatrixManager? = null
        private set

    /**
     * True between [onMatrixConnected] and [onMatrixDisconnected]. If Nothing's Glyph service
     * restarts, it disconnects and connects again while we stay bound: each connect gets
     * exactly one matching disconnect, and no frames go to a dead connection.
     */
    private var connected = false

    private val gmmCallback = object : GlyphMatrixManager.Callback {
        override fun onServiceConnected(name: ComponentName?) {
            val gmm = glyphMatrixManager ?: return
            Log.d(LOG_TAG, "$tag: connected")
            disconnect() // a reconnect without a disconnect first
            gmm.register(Glyph.DEVICE_23112) // Nothing Phone (3)
            connected = true
            onMatrixConnected(applicationContext, gmm)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(LOG_TAG, "$tag: matrix service disconnected")
            disconnect()
        }
    }

    private fun disconnect() {
        if (!connected) return
        connected = false
        onMatrixDisconnected(applicationContext)
    }

    final override fun onBind(intent: Intent?): IBinder? {
        Log.d(LOG_TAG, "$tag: onBind")
        GlyphMatrixManager.getInstance(applicationContext)?.let { gmm ->
            glyphMatrixManager = gmm
            gmm.init(gmmCallback)
        }
        return serviceMessenger.binder
    }

    final override fun onUnbind(intent: Intent?): Boolean {
        Log.d(LOG_TAG, "$tag: onUnbind")
        disconnect()
        glyphMatrixManager?.let {
            runCatching { it.turnOff() }
            runCatching { it.unInit() }
        }
        glyphMatrixManager = null
        return false
    }

    /** Pushes a 25x25 frame; values 0..4095. Safe to call when not connected. */
    protected fun showFrame(frame: IntArray) {
        val gmm = glyphMatrixManager?.takeIf { connected } ?: return
        try {
            gmm.setMatrixFrame(frame)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$tag: setMatrixFrame failed", e)
        }
    }

    open fun onMatrixConnected(context: Context, glyphMatrixManager: GlyphMatrixManager) {}
    open fun onMatrixDisconnected(context: Context) {}
    open fun onGlyphButtonDown() {}
    open fun onGlyphButtonUp() {}
    open fun onGlyphButtonLongPress() {}

    /** Sent about once a minute while this toy is the always-on (AOD) toy. */
    open fun onAodTick() {}

    private companion object {
        const val LOG_TAG = "GlyphMatrixService"
    }
}
