package com.calculator.scientific

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver enabling Single-App Kiosk OS mode via:
 * `adb shell dpm set-device-owner com.calculator.scientific/.KioskDeviceAdminReceiver`
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
