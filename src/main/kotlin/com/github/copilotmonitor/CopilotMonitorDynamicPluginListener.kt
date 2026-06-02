package com.github.copilotmonitor

import com.github.copilotmonitor.notifications.AlertNotificationService
import com.github.copilotmonitor.services.ModelConfigRepository
import com.github.copilotmonitor.services.OtelExportService
import com.github.copilotmonitor.services.SessionLogService
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CopilotMonitorDynamicPluginListener : DynamicPluginListener {

    private val logger = thisLogger()

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != "com.github.copilotmonitor") return
        logger.info("[CopilotMonitor] Plugin dynamically loaded — initializing services")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service<ModelConfigRepository>().init()
                service<SessionLogService>().init()
                service<AlertNotificationService>().init()
                service<OtelExportService>().init()
                logger.info("[CopilotMonitor] Dynamic init complete")
            } catch (e: Exception) {
                logger.warn("[CopilotMonitor] Dynamic init failed: ${e.message}")
            }
        }
    }
}
