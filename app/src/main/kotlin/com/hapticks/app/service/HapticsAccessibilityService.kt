package com.hapticks.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.hapticks.app.HapticksApp
import com.hapticks.app.data.HapticsSettings
import com.hapticks.app.haptics.HapticEngine
import com.hapticks.app.service.accessibility.isAccessibilityEventFromOwnApplication
import com.hapticks.app.service.accessibility.interacted.InteractableViewHaptics
import com.hapticks.app.service.accessibility.scrolled.ScrollAbsoluteEdgeVibration
import com.hapticks.app.service.accessibility.scrolled.ScrollContentVibration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class HapticsAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null

    @Volatile
    private var current: HapticsSettings = HapticsSettings.Default

    private lateinit var engine: HapticEngine

    private var chargingReceiver: BroadcastReceiver? = null
    private var brightnessObserver: ContentObserver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as HapticksApp
        engine = app.hapticEngine

        applyEventMask(HapticsSettings.Default)

        settingsJob = app.preferences.settings
            .distinctUntilChanged()
            .onEach { snapshot ->
                current = snapshot
                applyEventMask(snapshot)
                updateChargingReceiver(snapshot)
                updateBrightnessObserver(snapshot)
            }
            .launchIn(scope)
    }

    private fun updateChargingReceiver(settings: HapticsSettings) {
        if (settings.chargingVibEnabled) {
            if (chargingReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val s = current
                        if (!s.chargingVibEnabled) return
                        when (intent?.action) {
                            Intent.ACTION_POWER_CONNECTED -> {
                                if (s.chargingVibOnConnect) {
                                    engine.play(s.chargingVibPattern, s.chargingVibIntensity)
                                }
                            }
                            Intent.ACTION_POWER_DISCONNECTED -> {
                                if (s.chargingVibOnDisconnect) {
                                    engine.play(s.chargingVibPattern, s.chargingVibIntensity)
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                registerReceiver(receiver, filter)
                chargingReceiver = receiver
            }
        } else {
            chargingReceiver?.let {
                try { unregisterReceiver(it) } catch (_: Exception) {}
                chargingReceiver = null
            }
        }
    }

    private fun updateBrightnessObserver(settings: HapticsSettings) {
        if (settings.brightnessHapticEnabled) {
            if (brightnessObserver == null) {
                val handler = Handler(Looper.getMainLooper())
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        val s = current
                        if (s.brightnessHapticEnabled) {
                            engine.play(s.brightnessHapticPattern, s.brightnessHapticIntensity, throttleMs = 80L)
                        }
                    }
                }
                contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    observer,
                )
                brightnessObserver = observer
            }
        } else {
            brightnessObserver?.let {
                try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
                brightnessObserver = null
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val s = current
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (s.volumeHapticEnabled && event.action == KeyEvent.ACTION_DOWN) {
                    engine.play(s.volumeHapticPattern, s.volumeHapticIntensity, throttleMs = 50L)
                }
            }
            KeyEvent.KEYCODE_POWER -> {
                if (s.powerHapticEnabled && event.action == KeyEvent.ACTION_DOWN) {
                    engine.play(s.powerHapticPattern, s.powerHapticIntensity, throttleMs = 100L)
                }
            }
        }
        // Return false so we don't consume the event
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val type = ev.eventType
        val pkg = ev.packageName?.toString()

        val fromOwnApp = isAccessibilityEventFromOwnApplication(ev)
        if (fromOwnApp && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (current.tapEnabled &&
                    !current.tapExcludedPackages.contains(pkg) &&
                    InteractableViewHaptics.hasToggleLikeContentChange(ev)
                ) {
                    InteractableViewHaptics.handle(engine, current, ev)
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!current.tapExcludedPackages.contains(pkg)) {
                    InteractableViewHaptics.handle(engine, current, ev)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                var consumedByEdge = false

                if (current.a11yScrollBoundEdge && !current.edgeExcludedPackages.contains(pkg)) {
                    if (ScrollAbsoluteEdgeVibration.onViewScrolled(ev) ==
                        ScrollAbsoluteEdgeVibration.Result.PlayEdgeHaptic
                    ) {
                        engine.play(
                            current.edgePattern,
                            current.edgeIntensity,
                            throttleMs = EDGE_THROTTLE_MS,
                        )
                        consumedByEdge = true
                    }
                }

                if (current.scrollEnabled &&
                    !consumedByEdge &&
                    !current.scrollExcludedPackages.contains(pkg)
                ) {
                    when (val scroll = ScrollContentVibration.onViewScrolled(ev, current)) {
                        is ScrollContentVibration.Decision.Play -> {
                            val count = scroll.count
                            if (count <= 1) {
                                engine.play(current.scrollPattern, scroll.intensity, throttleMs = 0L)
                            } else {
                                scope.launch {
                                    repeat(count) { i ->
                                        if (i > 0) delay(42L)
                                        engine.play(current.scrollPattern, scroll.intensity, throttleMs = 0L)
                                    }
                                }
                            }
                        }
                        ScrollContentVibration.Decision.None -> Unit
                    }
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        settingsJob?.cancel()
        scope.cancel()
        chargingReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        brightnessObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    private fun applyEventMask(settings: HapticsSettings) {
        val info = serviceInfo ?: return
        var mask = InteractableViewHaptics.eventTypeMask(settings)
        if (settings.scrollEnabled || settings.a11yScrollBoundEdge) {
            mask = mask or AccessibilityEvent.TYPE_VIEW_SCROLLED
        }
        if (mask == 0) mask = AccessibilityEvent.TYPE_VIEW_CLICKED
        if (info.eventTypes == mask) return
        info.eventTypes = mask
        serviceInfo = info
    }

    private companion object {
        const val EDGE_THROTTLE_MS = 200L
    }
}
