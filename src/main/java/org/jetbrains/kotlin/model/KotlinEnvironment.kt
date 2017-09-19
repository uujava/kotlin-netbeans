/*******************************************************************************
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.model

import com.intellij.codeInsight.ContainerProvider
import com.intellij.codeInsight.ExternalAnnotationsManager
import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.codeInsight.NullableNotNullManager
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.core.CoreJavaFileManager
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.formatting.KotlinLanguageCodeStyleSettingsProvider
import com.intellij.formatting.KotlinSettingsProvider
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.MetaLanguage
import com.intellij.lang.java.JavaParserDefinition
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.TransactionGuardImpl
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.extensions.ExtensionsArea
import com.intellij.openapi.fileTypes.FileTypeExtensionPoint
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.PersistentFSConstants
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.FileContextProvider
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.augment.TypeAnnotationModifier
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.JavaClassSupersImpl
import com.intellij.psi.impl.PsiElementFinderImpl
import com.intellij.psi.impl.PsiTreeChangePreprocessor
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.meta.MetaDataContributor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.BinaryFileStubBuilders
import com.intellij.psi.util.JavaClassSupers
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.asJava.LightClassGenerationSupport
import org.jetbrains.kotlin.asJava.classes.KtLightClassForFacade
import org.jetbrains.kotlin.asJava.finder.JavaElementFinder
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.CliModuleVisibilityManagerImpl
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.ERROR
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.STRONG_WARNING
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.script.CliScriptDependenciesProvider
import org.jetbrains.kotlin.cli.common.script.CliScriptReportSink
import org.jetbrains.kotlin.cli.jvm.JvmRuntimeVersionsConsistencyChecker
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.cli.jvm.config.*
import org.jetbrains.kotlin.cli.jvm.index.*
import org.jetbrains.kotlin.cli.jvm.javac.JavacWrapperRegistrar
import org.jetbrains.kotlin.cli.jvm.modules.CliJavaModuleResolver
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.codegen.extensions.ClassBuilderInterceptorExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import org.jetbrains.kotlin.extensions.DeclarationAttributeAltererExtension
import org.jetbrains.kotlin.extensions.PreprocessedVirtualFileFactoryExtension
import org.jetbrains.kotlin.extensions.StorageComponentContainerContributor
import org.jetbrains.kotlin.filesystem.KotlinLightClassManager
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.js.resolve.diagnostics.DefaultErrorMessagesJs
import org.jetbrains.kotlin.load.kotlin.KotlinBinaryClassCache
import org.jetbrains.kotlin.load.kotlin.MetadataFinderFactory
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager
import org.jetbrains.kotlin.load.kotlin.VirtualFileFinderFactory
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BuiltInsReferenceResolver
import org.jetbrains.kotlin.resolve.CodeAnalyzerInitializer
import org.jetbrains.kotlin.resolve.KotlinCacheServiceImpl
import org.jetbrains.kotlin.resolve.KotlinSourceIndex
import org.jetbrains.kotlin.resolve.diagnostics.DiagnosticSuppressor
import org.jetbrains.kotlin.resolve.diagnostics.SuppressStringProvider
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.resolve.jvm.diagnostics.DefaultErrorMessagesJvm
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension
import org.jetbrains.kotlin.resolve.jvm.extensions.PackageFragmentProviderExtension
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver
import org.jetbrains.kotlin.resolve.lang.kotlin.NetBeansVirtualFileFinderFactory
import org.jetbrains.kotlin.resolve.lazy.declarations.CliDeclarationProviderFactoryService
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactoryService
import org.jetbrains.kotlin.script.KotlinScriptDefinitionProvider
import org.jetbrains.kotlin.script.ScriptDependenciesProvider
import org.jetbrains.kotlin.script.ScriptReportSink
import org.jetbrains.kotlin.utils.KotlinImportInserterHelper
import org.jetbrains.kotlin.utils.ProjectUtils
import java.io.File
import java.net.URLDecoder
import org.netbeans.api.java.classpath.ClassPath.Entry as ClassPathEntry
import org.netbeans.api.project.Project as NBProject

//copied from kotlin eclipse plugin to avoid RuntimeException: Could not find installation home path. 
//Please make sure bin/idea.properties is present in the installation directory
private fun setIdeaIoUseFallback() {
    if (SystemInfo.isWindows) {
        val properties = System.getProperties()

        properties.setProperty("idea.io.use.nio2", java.lang.Boolean.TRUE.toString())

        if (!(SystemInfo.isJavaVersionAtLeast("1.7") && "1.7.0-ea" != SystemInfo.JAVA_VERSION)) {
            properties.setProperty("idea.io.use.fallback", java.lang.Boolean.TRUE.toString())
        }
    }
}

class KotlinEnvironment private constructor(kotlinProject: NBProject, disposable: Disposable) {

    val configuration = CompilerConfiguration()
    val applicationEnvironment: JavaCoreApplicationEnvironment = createApplicationEnvironment(disposable, configuration)
    val configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
    private val projectEnvironment: JavaCoreProjectEnvironment = object : KotlinCoreProjectEnvironment(disposable, applicationEnvironment) {
        override fun preregisterServices() {
            registerProjectExtensionPoints(Extensions.getArea(project))
        }

        override fun registerJavaPsiFacade() {
            with(project) {
                registerService(CoreJavaFileManager::class.java, ServiceManager.getService(this, JavaFileManager::class.java) as CoreJavaFileManager)

                val cliLightClassGenerationSupport = CliLightClassGenerationSupport(this)
                registerService(LightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
                registerService(CliLightClassGenerationSupport::class.java, cliLightClassGenerationSupport)
                registerService(CodeAnalyzerInitializer::class.java, cliLightClassGenerationSupport)

                registerService(ExternalAnnotationsManager::class.java, MockExternalAnnotationsManager())
                registerService(InferredAnnotationsManager::class.java, MockInferredAnnotationsManager())

                val area = Extensions.getArea(this)

                area.getExtensionPoint(PsiElementFinder.EP_NAME).registerExtension(JavaElementFinder(this, cliLightClassGenerationSupport))
                area.getExtensionPoint(PsiElementFinder.EP_NAME).registerExtension(
                        PsiElementFinderImpl(this, ServiceManager.getService(this, JavaFileManager::class.java)))
            }

            super.registerJavaPsiFacade()
        }

    }

    private val sourceFiles = mutableListOf<KtFile>()
    val rootsIndex: JvmDependenciesDynamicCompoundIndex
    val packagePartProviders = mutableListOf<JvmPackagePartProvider>()

    private val classpathRootsResolver: ClasspathRootsResolver
    private val initialRoots: List<JavaRoot>

    init {
        val startTime = System.nanoTime()
        // ******************
        setIdeaIoUseFallback()
        configuration.put<String>(CommonConfigurationKeys.MODULE_NAME, project.name)
        PersistentFSConstants.setMaxIntellisenseFileSize(FileUtilRt.LARGE_FOR_CONTENT_LOADING)

        val project = projectEnvironment.project

        ExpressionCodegenExtension.registerExtensionPoint(project)
        SyntheticResolveExtension.registerExtensionPoint(project)
        ClassBuilderInterceptorExtension.registerExtensionPoint(project)
        AnalysisHandlerExtension.registerExtensionPoint(project)
        PackageFragmentProviderExtension.registerExtensionPoint(project)
        StorageComponentContainerContributor.registerExtensionPoint(project)
        DeclarationAttributeAltererExtension.registerExtensionPoint(project)
        PreprocessedVirtualFileFactoryExtension.registerExtensionPoint(project)

        for (registrar in configuration.getList(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS)) {
            registrar.registerProjectComponents(project, configuration)
        }

        project.registerService(DeclarationProviderFactoryService::class.java, CliDeclarationProviderFactoryService(sourceFiles))
        project.registerService(ModuleVisibilityManager::class.java, CliModuleVisibilityManagerImpl(configFiles == EnvironmentConfigFiles.JVM_CONFIG_FILES))
        project.registerService(NullableNotNullManager::class.java, KotlinNullableNotNullManager(kotlinProject))
        project.registerService(BuiltInsReferenceResolver::class.java, BuiltInsReferenceResolver(project))
        registerProjectServicesForCLI(projectEnvironment)
        val messageCollector: MessageCollector = object : MessageCollector {
            var hasErrors = false
            override fun clear() {
                hasErrors = false
            }

            override fun hasErrors() = hasErrors

            override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
                with(KotlinLogger.INSTANCE) {
                    if (severity.isError) {
                        hasErrors = true
                        logWarning("from kt: $severity $message $location")
                    } else {
                        logInfo("from kt: $severity $message $location")
                    }
                }
            }

        }
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
        registerProjectServices(projectEnvironment, kotlinProject, messageCollector)

        sourceFiles += CompileEnvironmentUtil.getKtFiles(project, getSourceRootsCheckingForDuplicates(), this.configuration, { message ->
            report(ERROR, message)
        })
        sourceFiles.sortBy { it.virtualFile.path }

        KotlinScriptDefinitionProvider.getInstance(project)?.let { scriptDefinitionProvider ->
            scriptDefinitionProvider.setScriptDefinitions(
                    configuration.getList(JVMConfigurationKeys.SCRIPT_DEFINITIONS))

            ScriptDependenciesProvider.getInstance(project).let { importsProvider ->
                configuration.addJvmClasspathRoots(
                        sourceFiles.mapNotNull(importsProvider::getScriptDependencies)
                                .flatMap { it.classpath }
                                .distinctBy { it.absolutePath })
            }
        }

        classpathRootsResolver = ClasspathRootsResolver(
                PsiManager.getInstance(project), messageCollector,
                configuration.getList(JVMConfigurationKeys.ADDITIONAL_JAVA_MODULES),
                this::contentRootToVirtualFile
        )

        configureContentRoots(kotlinProject)

        val (initialRoots, javaModules) =
                classpathRootsResolver.convertClasspathRoots(configuration.getList(JVMConfigurationKeys.CONTENT_ROOTS))
        this.initialRoots = initialRoots

        if (!configuration.getBoolean(JVMConfigurationKeys.SKIP_RUNTIME_VERSION_CHECK) && messageCollector != null) {
            JvmRuntimeVersionsConsistencyChecker.checkCompilerClasspathConsistency(
                    messageCollector,
                    configuration,
                    initialRoots.mapNotNull { (file, type) -> if (type == JavaRoot.RootType.BINARY) file else null }
            )
        }

        val (roots, singleJavaFileRoots) =
                initialRoots.partition { (file) -> file.isDirectory || file.extension != JavaFileType.DEFAULT_EXTENSION }

        // REPL and kapt2 update classpath dynamically
        rootsIndex = JvmDependenciesDynamicCompoundIndex().apply {
            addIndex(JvmDependenciesIndexImpl(roots))
            updateClasspathFromRootsIndex(this)
        }

        (ServiceManager.getService(project, CoreJavaFileManager::class.java) as KotlinCliJavaFileManagerImpl).initialize(
                rootsIndex,
                SingleJavaFileRootsIndex(singleJavaFileRoots),
                configuration.getBoolean(JVMConfigurationKeys.USE_FAST_CLASS_FILES_READING)
        )

        project.registerService(
                JavaModuleResolver::class.java,
                CliJavaModuleResolver(classpathRootsResolver.javaModuleGraph, javaModules,
                        classpathRootsResolver.javaModuleFinder.systemModules.toList())
        )

        val finderFactory = NetBeansVirtualFileFinderFactory(kotlinProject)
        project.registerService(MetadataFinderFactory::class.java, finderFactory)
        project.registerService(VirtualFileFinderFactory::class.java, finderFactory)

        project.putUserData(APPEND_JAVA_SOURCE_ROOTS_HANDLER_KEY, fun(roots: List<File>) {
            updateClasspath(roots.map { JavaSourceRoot(it, null) })
        })
        KotlinLogger.INSTANCE.logInfo("KotlinEnvironment init: ${(System.nanoTime() - startTime)} ns")
    }

    fun createPackagePartProvider(scope: GlobalSearchScope): JvmPackagePartProvider {
        return JvmPackagePartProvider(configuration.languageVersionSettings, scope).apply {
            addRoots(initialRoots)
            packagePartProviders += this
        }
    }

    private val VirtualFile.javaFiles: List<VirtualFile>
        get() = mutableListOf<VirtualFile>().apply {
            VfsUtilCore.processFilesRecursively(this@javaFiles) { file ->
                if (file.fileType == JavaFileType.INSTANCE) {
                    add(file)
                }
                true
            }
        }

    private val allJavaFiles: List<File>
        get() = configuration.javaSourceRoots
                .mapNotNull(this::findLocalFile)
                .flatMap { it.javaFiles }
                .map { File(it.canonicalPath) }

    fun registerJavac(
            javaFiles: List<File> = allJavaFiles,
            kotlinFiles: List<KtFile> = sourceFiles,
            arguments: Array<String>? = null
    ): Boolean {
        return JavacWrapperRegistrar.registerJavac(projectEnvironment.project, configuration, javaFiles, kotlinFiles, arguments)
    }

    val project: Project
        get() = projectEnvironment.project

    internal fun countLinesOfCode(sourceFiles: List<KtFile>): Int =
            sourceFiles.sumBy { sourceFile ->
                val text = sourceFile.text
                StringUtil.getLineBreakCount(text) + (if (StringUtil.endsWithLineBreak(text)) 0 else 1)
            }

    private fun updateClasspathFromRootsIndex(index: JvmDependenciesIndex) {
        index.indexedRoots.forEach {
            projectEnvironment.addSourcesToClasspath(it.file)
        }
    }

    fun updateClasspath(contentRoots: List<ContentRoot>): List<File>? {
        // TODO: add new Java modules to CliJavaModuleResolver
        val newRoots = classpathRootsResolver.convertClasspathRoots(contentRoots).roots

        for (packagePartProvider in packagePartProviders) {
            packagePartProvider.addRoots(newRoots)
        }

        return rootsIndex.addNewIndexForRoots(newRoots)?.let { newIndex ->
            updateClasspathFromRootsIndex(newIndex)
            newIndex.indexedRoots.mapNotNull { (file) ->
                VfsUtilCore.virtualToIoFile(VfsUtilCore.getVirtualFileForJar(file) ?: file)
            }.toList()
        }.orEmpty()
    }

    private fun contentRootToVirtualFile(root: JvmContentRoot): VirtualFile? {
        return when (root) {
            is JvmClasspathRoot -> if (root.file.isFile) findJarRoot(root.file) else findLocalFile(root)
            is JvmModulePathRoot -> if (root.file.isFile) findJarRoot(root.file) else findLocalFile(root)
            is JavaSourceRoot -> findLocalFile(root)
            else -> throw IllegalStateException("Unexpected root: $root")
        }
    }

    internal fun findLocalFile(path: String) = applicationEnvironment.localFileSystem.findFileByPath(path)

    private fun findLocalFile(root: JvmContentRoot): VirtualFile? {
        return findLocalFile(root.file.absolutePath).also {
            if (it == null) {
                report(STRONG_WARNING, "Classpath entry points to a non-existent location: ${root.file}")
            }
        }
    }

    private fun findJarRoot(file: File): VirtualFile? =
            applicationEnvironment.jarFileSystem.findFileByPath("$file${URLUtil.JAR_SEPARATOR}")

    private fun getSourceRootsCheckingForDuplicates(): Collection<String> {
        val uniqueSourceRoots = linkedSetOf<String>()

        configuration.kotlinSourceRoots.forEach { path ->
            if (!uniqueSourceRoots.add(path)) {
                report(STRONG_WARNING, "Duplicate source root: $path")
            }
        }

        return uniqueSourceRoots
    }

    private fun report(severity: CompilerMessageSeverity, message: String) {
        configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY).report(severity, message)
    }

    companion object {
        private val ideaCompatibleBuildNumber = "171.9999"

        val CACHED_ENVIRONMENT = hashMapOf<NBProject, KotlinEnvironment>()

        @Synchronized
        fun getEnvironment(kotlinProject: NBProject): KotlinEnvironment {
            if (!CACHED_ENVIRONMENT.containsKey(kotlinProject)) {
                CACHED_ENVIRONMENT.put(kotlinProject, KotlinEnvironment(kotlinProject, Disposer.newDisposable()))
            }

            return CACHED_ENVIRONMENT[kotlinProject]!!
        }

        @Synchronized
        fun updateKotlinEnvironment(kotlinProject: NBProject):KotlinEnvironment {
            CACHED_ENVIRONMENT.remove(kotlinProject)
            return getEnvironment(kotlinProject)
        }

        private fun createApplicationEnvironment(
                parentDisposable: Disposable, configuration: CompilerConfiguration): JavaCoreApplicationEnvironment {
            Extensions.cleanRootArea(parentDisposable)
            registerAppExtensionPoints()
            val applicationEnvironment = object : JavaCoreApplicationEnvironment(parentDisposable) {
                override fun createJrtFileSystem(): VirtualFileSystem? {
                    val jdkHome = configuration[JVMConfigurationKeys.JDK_HOME] ?: return null
                    return CoreJrtFileSystem.create(jdkHome)
                }
            }

            getExtensionsFromCommonXml()
            getExtensionsFromKotlin2JvmXml()

            registerApplicationServicesForCLI(applicationEnvironment)
            registerApplicationServices(applicationEnvironment)

            return applicationEnvironment
        }

        private fun registerAppExtensionPoints() {
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), BinaryFileStubBuilders.EP_NAME, FileTypeExtensionPoint::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), FileContextProvider.EP_NAME, FileContextProvider::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), MetaDataContributor.EP_NAME, MetaDataContributor::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), PsiAugmentProvider.EP_NAME, PsiAugmentProvider::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), JavaMainMethodProvider.EP_NAME, JavaMainMethodProvider::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ContainerProvider.EP_NAME, ContainerProvider::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClsCustomNavigationPolicy.EP_NAME, ClsCustomNavigationPolicy::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), ClassFileDecompilers.EP_NAME, ClassFileDecompilers.Decompiler::class.java)
            //
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), TypeAnnotationModifier.EP_NAME, TypeAnnotationModifier::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(Extensions.getRootArea(), MetaLanguage.EP_NAME, MetaLanguage::class.java)
        }


        private fun registerApplicationServicesForCLI(applicationEnvironment: JavaCoreApplicationEnvironment) {
            // ability to get text from annotations xml files
            applicationEnvironment.registerFileType(PlainTextFileType.INSTANCE, "xml")
            applicationEnvironment.registerParserDefinition(JavaParserDefinition())
        }

        // made public for Upsource
        @Suppress("MemberVisibilityCanPrivate")
        @JvmStatic
        fun registerApplicationServices(applicationEnvironment: JavaCoreApplicationEnvironment) {
            with(applicationEnvironment) {
                registerFileType(KotlinFileType.INSTANCE, "kt")
                registerFileType(KotlinFileType.INSTANCE, KotlinParserDefinition.STD_SCRIPT_SUFFIX)
                registerParserDefinition(KotlinParserDefinition())
                application.registerService(KotlinBinaryClassCache::class.java, KotlinBinaryClassCache())
                application.registerService(JavaClassSupers::class.java, JavaClassSupersImpl::class.java)
                application.registerService(TransactionGuard::class.java, TransactionGuardImpl::class.java)
            }
        }

        private fun registerProjectExtensionPoints(area: ExtensionsArea) {
            CoreApplicationEnvironment.registerExtensionPoint(area, PsiTreeChangePreprocessor.EP_NAME, PsiTreeChangePreprocessor::class.java)
            CoreApplicationEnvironment.registerExtensionPoint(area, PsiElementFinder.EP_NAME, PsiElementFinder::class.java)
        }

        // made public for Upsource
        @JvmStatic
        fun registerProjectServices(projectEnvironment: JavaCoreProjectEnvironment, kotlinProject: NBProject, messageCollector: MessageCollector?) {
            with(projectEnvironment.project) {
                val kotlinScriptDefinitionProvider = KotlinScriptDefinitionProvider()
                registerService(KotlinScriptDefinitionProvider::class.java, kotlinScriptDefinitionProvider)
                registerService(ScriptDependenciesProvider::class.java, CliScriptDependenciesProvider(this, kotlinScriptDefinitionProvider))
                registerService(KotlinJavaPsiFacade::class.java, KotlinJavaPsiFacade(this))
                registerService(KotlinLightClassManager::class.java, KotlinLightClassManager(kotlinProject))
                registerService(KtLightClassForFacade.FacadeStubCache::class.java, KtLightClassForFacade.FacadeStubCache(this))
                if (messageCollector != null) {
                    registerService(ScriptReportSink::class.java, CliScriptReportSink(messageCollector))
                }
                registerService(KotlinSourceIndex::class.java, KotlinSourceIndex())
                registerService(KotlinCacheService::class.java, KotlinCacheServiceImpl(this, kotlinProject))
                registerService(ImportInsertHelper::class.java, KotlinImportInserterHelper())
            }

        }

        private fun registerProjectServicesForCLI(@Suppress("UNUSED_PARAMETER") projectEnvironment: JavaCoreProjectEnvironment) {
            /**
             * Note that Kapt may restart code analysis process, and CLI services should be aware of that.
             * Use PsiManager.getModificationTracker() to ensure that all the data you cached is still valid.
             */
        }

        private fun getExtensionsFromCommonXml() {
            CoreApplicationEnvironment.registerApplicationExtensionPoint(
                    ExtensionPointName("org.jetbrains.kotlin.diagnosticSuppressor"), DiagnosticSuppressor::class.java)
            CoreApplicationEnvironment.registerApplicationExtensionPoint(
                    ExtensionPointName("org.jetbrains.kotlin.defaultErrorMessages"), DefaultErrorMessages.Extension::class.java)
            CoreApplicationEnvironment.registerApplicationExtensionPoint(
                    ExtensionPointName("org.jetbrains.kotlin.suppressStringProvider"), SuppressStringProvider::class.java)
//            CoreApplicationEnvironment.registerApplicationExtensionPoint(
//                    ExtensionPointName(("org.jetbrains.kotlin.expressionCodegenExtension")), ExpressionCodegenExtension::class.java)
//            CoreApplicationEnvironment.registerApplicationExtensionPoint(
//                    ExtensionPointName(("org.jetbrains.kotlin.classBuilderFactoryInterceptorExtension")), ClassBuilderInterceptorExtension::class.java)
//            CoreApplicationEnvironment.registerApplicationExtensionPoint(
//                    ExtensionPointName(("org.jetbrains.kotlin.packageFragmentProviderExtension")), PackageFragmentProviderExtension::class.java)
            CoreApplicationEnvironment.registerApplicationExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME, KotlinSettingsProvider::class.java)
            CoreApplicationEnvironment.registerApplicationExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME, KotlinLanguageCodeStyleSettingsProvider::class.java)


            with(Extensions.getRootArea()) {
                getExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME).registerExtension(KotlinSettingsProvider())
                getExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME).registerExtension(KotlinLanguageCodeStyleSettingsProvider())
                getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(DefaultErrorMessagesJvm())
                getExtensionPoint(DefaultErrorMessages.Extension.EP_NAME).registerExtension(DefaultErrorMessagesJs())
            }
        }

        private fun getExtensionsFromKotlin2JvmXml() {
            CoreApplicationEnvironment.registerComponentInstance<DefaultErrorMessages.Extension>(Extensions.getRootArea().picoContainer,
                    DefaultErrorMessages.Extension::class.java, DefaultErrorMessagesJvm())
        }
    }

    fun getVirtualFileInJar(pathToJar: String, relativePath: String): VirtualFile? {
        val decodedPathToJar = URLDecoder.decode(pathToJar, "UTF-8") ?: pathToJar
        val decodedRelativePath = URLDecoder.decode(relativePath, "UTF-8") ?: relativePath

        return applicationEnvironment.jarFileSystem.findFileByPath("$decodedPathToJar!/$decodedRelativePath")
    }

    fun getVirtualFile(location: String) = applicationEnvironment.localFileSystem.findFileByPath(location)

    private fun configureContentRoots(kotlinProject: NBProject) {
        val classpath = ProjectUtils.getClasspath(kotlinProject)
        KotlinLogger.INSTANCE.logInfo("Project ${kotlinProject.projectDirectory.path} classpath is $classpath")
        classpath.forEach {
            configuration.addJvmClasspathRoot(normalizeClassPath(it))
        }
    }

    private fun normalizeClassPath(path: String):File {
        val filePath = if(path.endsWith("!/")) {
            path.split("!/")[0].substringAfter("file:")
        } else {
            path
        }
        return File(filePath)
    }

}
