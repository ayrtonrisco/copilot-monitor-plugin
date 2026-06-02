package com.github.copilotmonitor

import com.github.copilotmonitor.notifications.AlertNotificationService
import com.github.copilotmonitor.services.ModelConfigRepository
import com.github.copilotmonitor.services.OtelExportService
import com.github.copilotmonitor.services.SessionLogService
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CopilotMonitorStartupListener : AppLifecycleListener {

    private val logger = thisLogger()

    override fun appStarted() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service<ModelConfigRepository>().init()
                service<SessionLogService>().init()
                service<AlertNotificationService>().init()
                service<OtelExportService>().init()
                logger.info("Copilot Monitor services initialized")
            } catch (e: Exception) {
                logger.warn("Copilot Monitor startup failed (non-critical): ${e.message}")
            }
        }
    }
}
