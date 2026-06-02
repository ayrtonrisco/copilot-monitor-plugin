package com.github.copilotmonitor

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger

class CopilotMonitorPlugin {
    companion object {
        private val logger = thisLogger()

        fun init() {
            logger.info("Copilot Monitor plugin initialized")
        }
    }
}
