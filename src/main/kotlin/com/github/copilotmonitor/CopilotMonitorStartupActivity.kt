package com.github.copilotmonitor

import com.github.copilotmonitor.notifications.AlertNotificationService
import com.github.copilotmonitor.services.ModelConfigRepository
import com.github.copilotmonitor.services.OtelExportService
import com.github.copilotmonitor.services.SessionLogService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CopilotMonitorStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service<ModelConfigRepository>().init()
                service<SessionLogService>().init()
                service<AlertNotificationService>().init()
                service<OtelExportService>().init()
                thisLogger().info("Copilot Monitor services initialized")
            } catch (e: Exception) {
                thisLogger().warn("Copilot Monitor startup failed (non-critical): ${e.message}")
            }
        }
    }
}
