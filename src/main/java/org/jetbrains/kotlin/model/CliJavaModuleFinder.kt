package org.jetbrains.kotlin.model

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.PsiJavaModule
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModule
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleFinder
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleInfo

internal class CliJavaModuleFinder(private val jrtFileSystem: VirtualFileSystem?) : JavaModuleFinder {
    private val userModules = linkedMapOf<String, JavaModule>()

    fun addUserModule(module: JavaModule) {
        userModules.putIfAbsent(module.name, module)
    }

    val allObservableModules: Sequence<JavaModule>
        get() = systemModules + userModules.values

    val systemModules: Sequence<JavaModule.Explicit>
        get() = jrtFileSystem?.findFileByPath("/modules")?.children.orEmpty().asSequence().mapNotNull(this::findSystemModule)

    override fun findModule(name: String): JavaModule? =
            jrtFileSystem?.findFileByPath("/modules/$name")?.let(this::findSystemModule)
                    ?: userModules[name]

    private fun findSystemModule(moduleRoot: VirtualFile): JavaModule.Explicit? {
        val file = moduleRoot.findChild(PsiJavaModule.MODULE_INFO_CLS_FILE) ?: return null
        val moduleInfo = JavaModuleInfo.read(file) ?: return null
        return JavaModule.Explicit(moduleInfo, moduleRoot, file, isBinary = true)
    }
}
