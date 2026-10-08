package com.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private var selectedSessionToken: MediaSession.Token? = null
    private val reconciliationHandler = Handler(Looper.getMainLooper())
    private val reconciliationRunnable = object : Runnable {
        override fun run() {
            if (!LyricsDemandController.isActive) return
            updateSessions()
            reconciliationHandler.postDelayed(this, SESSION_RECONCILIATION_INTERVAL_MS)
        }
    }

    companion object {
        private const val SESSION_RECONCILIATION_INTERVAL_MS = 2_000L
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestSession(controllers)
        }

    private val lyricsDemandListener: (Boolean) -> Unit = { active ->
        if (active) {
            // Demand can become active without a media-session ordering change
            // (opening the phone UI or connecting Android Auto), so immediately
            // forward the currently selected/playing session.
            startSessionReconciliation()
            updateSessions()
        } else {
            reconciliationHandler.removeCallbacks(reconciliationRunnable)
            // Detach MediaTracker from the controller so later metadata changes do
            // not trigger provider work while the app is unused. The notification
            // listener itself keeps selecting sessions in the background.
            MediaTracker.getInstance(this).onMediaSessionChanged(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        LyricsDemandController.addListener(lyricsDemandListener)
        if (LyricsDemandController.isActive) startSessionReconciliation()
    }

    override fun onDestroy() {
        reconciliationHandler.removeCallbacks(reconciliationRunnable)
        LyricsDemandController.removeListener(lyricsDemandListener)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun startSessionReconciliation() {
        reconciliationHandler.removeCallbacks(reconciliationRunnable)
        reconciliationHandler.postDelayed(reconciliationRunnable, SESSION_RECONCILIATION_INTERVAL_MS)
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        val filtered = controllers?.filter { it.packageName != packageName }
        if (filtered.isNullOrEmpty()) {
            selectedSessionToken = null
            if (LyricsDemandController.isActive) {
                MediaTracker.getInstance(this).onMediaSessionChanged(null)
            }
            return
        }

        val current = selectedSessionToken?.let { token ->
            filtered.firstOrNull { controller -> controller.sessionToken == token }
        }
        val best = current?.takeIf { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: filtered.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: filtered.first()

        selectedSessionToken = best.sessionToken
        if (LyricsDemandController.isActive) {
            MediaTracker.getInstance(this).onMediaSessionChanged(best)
        }
    }
}
