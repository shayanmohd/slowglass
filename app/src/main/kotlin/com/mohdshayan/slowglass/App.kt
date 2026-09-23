package com.mohdshayan.slowglass

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import com.mohdshayan.slowglass.capture.CaptureService
import com.mohdshayan.slowglass.di.ServiceLocator

class App : Application(), CameraXConfig.Provider {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        CaptureService.createChannel(this)
    }

    /** Slowglass only uses the back camera; limiting CameraX to it skips probing the others at start. */
    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
            .build()
}
