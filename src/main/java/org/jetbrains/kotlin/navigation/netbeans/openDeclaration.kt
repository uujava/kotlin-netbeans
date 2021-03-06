/**
 * *****************************************************************************
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
 ******************************************************************************
 */
package org.jetbrains.kotlin.navigation.netbeans

import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.netbeans.api.project.Project
import org.jetbrains.kotlin.resolve.lang.java.resolver.NetBeansJavaSourceElement
import org.jetbrains.kotlin.resolve.lang.java.structure.NetBeansJavaElement
import org.jetbrains.kotlin.resolve.lang.java.structure.NetBeansJavaMember
import org.jetbrains.kotlin.resolve.lang.java.structure.NetBeansJavaClass
import org.jetbrains.kotlin.resolve.lang.java.findType
import org.jetbrains.kotlin.resolve.lang.java.openInEditor
import org.jetbrains.kotlin.resolve.lang.java.findMember
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParser
import org.jetbrains.kotlin.navigation.references.createReferences
import org.jetbrains.kotlin.psi.KtElement
import org.openide.cookies.EditorCookie
import org.openide.filesystems.FileObject
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryPackageSourceElement
import javax.lang.model.element.ElementKind
import com.intellij.psi.PsiElement
import org.openide.filesystems.FileUtil
import java.io.File
import org.jetbrains.kotlin.navigation.*
import org.jetbrains.kotlin.utils.ProjectUtils
import org.jetbrains.kotlin.utils.LineEndUtil
import javax.swing.text.StyledDocument
import org.netbeans.modules.editor.NbEditorUtilities
import org.openide.loaders.DataObject
import org.openide.text.NbDocument
import org.openide.text.Line
import javax.swing.text.Document
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.VirtualFileKotlinClass
import org.jetbrains.kotlin.load.kotlin.header.KotlinClassHeader
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedCallableMemberDescriptor
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.log.KotlinLogger
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.resolve.NetBeansDescriptorUtils

fun navigate(referenceExpression: KtReferenceExpression, project: Project, file: FileObject): Pair<Document, Int>? {
    val data = getNavigationData(referenceExpression, project) ?: return null
    return gotoElement(data.sourceElement, data.descriptor, referenceExpression, project, file)
}

private fun gotoElement(element: SourceElement, descriptor: DeclarationDescriptor,
                        fromElement: KtElement, project: Project, currentFile: FileObject): Pair<Document, Int>? {
    when (element) {
        is NetBeansJavaSourceElement -> {
            var elementHandle = (element.javaElement as NetBeansJavaElement<*>).elementHandle.elementHandle ?: return null
            if (elementHandle.kind == ElementKind.CONSTRUCTOR) {
                val containingClass = (element.javaElement as NetBeansJavaMember<*>).containingClass
                elementHandle = (containingClass as NetBeansJavaClass).elementHandle.elementHandle ?: return null
            }
            
            elementHandle.openInEditor(project)
        }
        
        is KotlinSourceElement -> return gotoKotlinDeclaration(element.psi, fromElement, currentFile)
        
        is KotlinJvmBinarySourceElement -> gotoElementInBinaryClass(element.binaryClass, descriptor, project)
        
        is KotlinJvmBinaryPackageSourceElement -> gotoClassByPackageSourceElement(element, descriptor, project)
        
        else -> return null
    }
    return null
}

private fun gotoClassByPackageSourceElement(sourceElement: KotlinJvmBinaryPackageSourceElement,
                                            descriptor: DeclarationDescriptor,
                                            project: Project) {
    if (descriptor !is DeserializedCallableMemberDescriptor) return
    
    val binaryClass = sourceElement.getContainingBinaryClass(descriptor) ?: return
    gotoElementInBinaryClass(binaryClass, descriptor, project)
}

private fun gotoElementInBinaryClass(binaryClass: KotlinJvmBinaryClass,
                                     descriptor: DeclarationDescriptor, 
                                     project: Project) {
    if (binaryClass !is VirtualFileKotlinClass) return
    
    val className = if (KotlinClassHeader.Kind.MULTIFILE_CLASS == binaryClass.classHeader.kind) {
        if (descriptor !is DeserializedCallableMemberDescriptor) return
        getImplClassName(descriptor)?.asString() ?: return
    } else binaryClass.classId.asSingleFqName().asString()
    
    val elementHandle = project.findType(className)?.elementHandle ?: return
    if (descriptor is MemberDescriptor) {
        elementHandle.findMember(descriptor, project)?.openInEditor(project) ?: elementHandle.openInEditor(project)
    } else elementHandle.openInEditor(project)
}

private fun getImplClassName(memberDescriptor: DeserializedCallableMemberDescriptor): Name? {
    val nameIndex: Int
    
    try
    {
        val getProtoMethod = DeserializedCallableMemberDescriptor::class.java.getMethod("getProto")
        val proto = getProtoMethod!!.invoke(memberDescriptor)
        val implClassNameField = Class.forName("org.jetbrains.kotlin.serialization.jvm.JvmProtoBuf").getField("implClassName")
        val implClassName = implClassNameField!!.get(null)
        val protobufCallable = Class.forName("org.jetbrains.kotlin.serialization.ProtoBuf\$Callable")
        val getExtensionMethod = protobufCallable!!.getMethod("getExtension", implClassName!!::class.java)
        val indexObj = getExtensionMethod!!.invoke(proto, implClassName) as? Int ?: return null

        nameIndex = indexObj
    } catch (e: ReflectiveOperationException) {
        KotlinLogger.INSTANCE.logException("", e)
        return null
    } catch (e: IllegalArgumentException) {
        KotlinLogger.INSTANCE.logException("", e)
        return null
    } catch (e: SecurityException) {
        KotlinLogger.INSTANCE.logException("", e)
        return null
    }
    
    return memberDescriptor.nameResolver.getName(nameIndex)
}

private fun gotoKotlinDeclaration(psi: PsiElement, fromElement: KtElement,
                                  currentFile: FileObject): Pair<Document, Int>? {
    val declarationFile = findFileObjectForReferencedElement(psi, fromElement, currentFile) ?: return null
    val document = ProjectUtils.getDocumentFromFileObject(declarationFile) ?: return null
    
    val startOffset = LineEndUtil.convertCrToDocumentOffset(psi.containingFile.text, psi.textOffset)
    openFileAtOffset(document, startOffset)
    return Pair(document, startOffset)
}

private fun findFileObjectForReferencedElement(psi: PsiElement, fromElement: KtElement,
                                               currentFile: FileObject): FileObject? {
    if (fromElement.containingFile == psi.containingFile) return currentFile
    
    val virtualFile = psi.containingFile.virtualFile ?: return null
    var file = FileUtil.toFileObject(File(virtualFile.path))
    if (file != null) return file
    
    file = getFileObjectFromJar(virtualFile.path) ?: return null
    return file
}

fun FileObject.openFileAtPosition(lineNumber: Int, columnNumber: Int) {
    val dataObject = DataObject.find(this) ?: return
    val editorCookie = dataObject.lookup.lookup(EditorCookie::class.java) ?: return
    
    if (lineNumber == -1 || lineNumber == 0) editorCookie.open()
    
    editorCookie.openDocument()
    val line = editorCookie.lineSet.getOriginal(lineNumber - 1)
    if (!line.isDeleted) {
        line.show(Line.ShowOpenType.REUSE, Line.ShowVisibilityType.FOCUS, columnNumber)
    }
}

fun openFileAtOffset(doc: StyledDocument, offset: Int) {
    val line = NbEditorUtilities.getLine(doc, offset, false)
    val colNumber = NbDocument.findLineColumn(doc, offset)
    line.show(Line.ShowOpenType.OPEN, Line.ShowVisibilityType.FRONT, colNumber)
}

fun moveCaretToOffset(doc: StyledDocument, offset: Int) {
    val line = NbEditorUtilities.getLine(doc, offset, false)
    val colNumber = NbDocument.findLineColumn(doc, offset)
    line.show(Line.ShowOpenType.NONE, Line.ShowVisibilityType.NONE, colNumber)
}

fun getElementWithSource(descriptor: DeclarationDescriptor, project: Project): SourceElement? {
    val sourceElements = NetBeansDescriptorUtils.descriptorToDeclarations(descriptor, project)
    
    return sourceElements.firstOrNull { it != SourceElement.NO_SOURCE }
}

fun getNavigationData(referenceExpression: KtReferenceExpression,
                              project: Project): NavigationData? {
    val ktFile = referenceExpression.containingKtFile
    val analysisResult = KotlinParser.getAnalysisResult(ktFile, project) ?: return null
    val context = analysisResult.analysisResult.bindingContext
    
    return createReferences(referenceExpression)
            .asSequence()
            .flatMap { it.getTargetDescriptors(context).asSequence() }
            .mapNotNull { 
                val elementWithSource = getElementWithSource(it, project)
                if (elementWithSource != null) NavigationData(elementWithSource, it) else null
            }
            .firstOrNull()
}

data class NavigationData(val sourceElement: SourceElement, val descriptor: DeclarationDescriptor)










