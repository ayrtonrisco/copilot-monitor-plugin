package com.github.copilotmonitor.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class TokenUsageStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = TokenUsageStatusBarWidget.ID
    override fun getDisplayName() = "Copilot Token Usage"
    override fun isAvailable(project: Project) = true
    override fun canBeEnabledOn(statusBar: StatusBar) = true
    override fun createWidget(project: Project): StatusBarWidget = TokenUsageStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
}
