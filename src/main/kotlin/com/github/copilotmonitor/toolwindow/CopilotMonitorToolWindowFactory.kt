package com.github.copilotmonitor.toolwindow

import com.github.copilotmonitor.MetricsFacade
import com.github.copilotmonitor.services.ContextWindowService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class CopilotMonitorToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CopilotMonitorPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)

        // Start context window polling for this project
        project.service<ContextWindowService>().startPolling()
    }

    override fun shouldBeAvailable(project: Project) = true
}
