package com.ptrain.android.resourcechecker

import com.android.build.gradle.AppExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class ResourceCheckerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.afterEvaluate {
            val isAndroid = project.plugins.hasPlugin("com.android.application")
            if (!isAndroid) {
                return@afterEvaluate
            }
            val android = project.extensions.findByName("android")
            (android as AppExtension).applicationVariants.forEach {
                val variantName = it.name.capitalize()
                if (!variantName.toLowerCase().contains("debug")) {
                    MergeDuplicatedResourceTask().run(project, it)
                }
            }
        }
    }
}