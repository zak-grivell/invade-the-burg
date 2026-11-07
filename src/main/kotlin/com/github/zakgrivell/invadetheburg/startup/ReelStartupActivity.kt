package com.github.zakgrivell.invadetheburg.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.github.zakgrivell.invadetheburg.services.ReelService

class ReelStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) project.service<ReelService>().start()
        }, project.disposed)
    }
}
