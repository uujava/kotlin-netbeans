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
package org.jetbrains.kotlin.hints.fixes

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.util.IdeDescriptorRenderers
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.renderer.*
import org.jetbrains.kotlin.resolve.OverrideResolver
import org.jetbrains.kotlin.utils.ProjectUtils
import org.netbeans.modules.csl.api.HintSeverity
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinParserResult
import org.jetbrains.kotlin.resolve.KotlinAnalyzer
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.psi.psiUtil.getElementTextWithContext
import org.jetbrains.kotlin.diagnostics.netbeans.parser.KotlinError
import org.jetbrains.kotlin.diagnostics.Errors
import javax.swing.text.Document
import org.jetbrains.kotlin.reformatting.format
import org.jetbrains.kotlin.hints.atomicChange

class ImplementMembersFix(kotlinError: KotlinError,
                          parserResult: KotlinParserResult) : KotlinQuickFix(kotlinError, parserResult) {

    override val hintSeverity = HintSeverity.ERROR

    override fun isApplicable() = when (kotlinError.diagnostic.factory) {
        Errors.ABSTRACT_MEMBER_NOT_IMPLEMENTED,
        Errors.ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED -> true
        else -> false
    }

    override fun createFixes() = listOf(this)

    override fun getDescription() = "Implement members"

    override fun implement() {
        val doc = parserResult.snapshot?.source?.getDocument(false) ?: ProjectUtils.getDocumentFromFileObject(parserResult.file)

        val classOrObject: KtClassOrObject = PsiTreeUtil.getParentOfType(kotlinError.psi, KtClassOrObject::class.java, false) ?: return

        val missingImplementations = collectMethodsToGenerate(classOrObject)
        if (missingImplementations.isEmpty()) return

        doc.atomicChange {
            generateMethods(this, classOrObject, missingImplementations)
            format(this, classOrObject.textRange.startOffset)
        }
    }

    private val OVERRIDE_RENDERER = DescriptorRenderer.withOptions {
        modifiers = setOf(DescriptorRendererModifier.OVERRIDE)
        withDefinedIn = false
        classifierNamePolicy = ClassifierNamePolicy.SHORT
        overrideRenderingPolicy = OverrideRenderingPolicy.RENDER_OVERRIDE
        unitReturnType = false
        typeNormalizer = IdeDescriptorRenderers.APPROXIMATE_FLEXIBLE_TYPES
    }

    private fun generateMethods(document: Document, classOrObject: KtClassOrObject, selectedElements: Set<CallableMemberDescriptor>) {
        val body = classOrObject.getBody()
        val psiFactory = KtPsiFactory(classOrObject.project)
        if (body == null) {
            val bodyText = "${psiFactory.createWhiteSpace().text}${psiFactory.createEmptyClassBody().text}"
            insertAfter(classOrObject, bodyText, document)
        } else {
            removeWhitespaceAfterLBrace(body, document)
        }

        val insertOffset = findLBraceEndOffset(document, classOrObject.textRange.startOffset) ?: return

        val generatedText = generateOverridingMembers(selectedElements, classOrObject, "\n")
                .joinToString("\n", postfix = "\n") { it.node.text }

        document.insertString(insertOffset, generatedText, null)
    }

    private fun insertAfter(psi: PsiElement, text: String, doc: Document) {
        val end = psi.textRange.endOffset
        doc.insertString(end, text, null)
    }

    private fun removeWhitespaceAfterLBrace(body: KtClassBody, document: Document) {
        val lBrace = body.lBrace ?: return
        val sibling = lBrace.nextSibling
        val needNewLine = sibling.nextSibling is KtDeclaration
        if (sibling is PsiWhiteSpace && !needNewLine) {
            document.remove(sibling.textRange.startOffset, sibling.textLength)
        }
    }

    private fun findLBraceEndOffset(document: Document, startIndex: Int): Int? {
        val text = document.getText(0, document.length)

        return (startIndex..text.lastIndex)
                .firstOrNull { text[it] == '{' }
                ?.let { it + 1 }
    }

    private fun DeclarationDescriptor.escapedName() = DescriptorRenderer.COMPACT.renderName(name)

    private fun collectMethodsToGenerate(classOrObject: KtClassOrObject): Set<CallableMemberDescriptor> {
        val descriptor = classOrObject.resolveToDescriptor()
        if (descriptor is ClassDescriptor) {
            return OverrideResolver.getMissingImplementations(descriptor)
        }
        return emptySet()
    }

    private fun KtElement.resolveToDescriptor(): DeclarationDescriptor {
        val ktFile = containingKtFile
        val analysisResult = KotlinAnalyzer.analyzeFile(parserResult.project, ktFile).analysisResult
        return BindingContextUtils.getNotNull<PsiElement, DeclarationDescriptor>(
                analysisResult.bindingContext,
                BindingContext.DECLARATION_TO_DESCRIPTOR,
                this,
                "Descriptor wasn't found for declaration ${toString()}\n${getElementTextWithContext()}")
    }

    private fun generateUnsupportedOrSuperCall(descriptor: CallableMemberDescriptor): String {
        val isAbstract = descriptor.modality == Modality.ABSTRACT
        if (isAbstract) {
            return "throw UnsupportedOperationException()"
        } else {
            val builder = StringBuilder()
            builder.append("super.${descriptor.escapedName()}")

            if (descriptor is FunctionDescriptor) {
                val paramTexts = descriptor.valueParameters.map {
                    val renderedName = it.escapedName()
                    if (it.varargElementType != null) "*$renderedName" else renderedName
                }
                paramTexts.joinTo(builder, prefix = "(", postfix = ")")
            }

            return builder.toString()
        }
    }

    private fun overrideProperty(classOrObject: KtClassOrObject,
                                 descriptor: PropertyDescriptor,
                                 lineDelimiter: String): KtElement {
        val newDescriptor = descriptor.copy(descriptor.containingDeclaration, Modality.OPEN, descriptor.visibility,
                descriptor.kind, /* copyOverrides = */ true) as PropertyDescriptor
        newDescriptor.overriddenDescriptors = listOf(descriptor)

        val body = StringBuilder()
        body.append("${lineDelimiter}get()")
        body.append(" = ")
        body.append(generateUnsupportedOrSuperCall(descriptor))
        if (descriptor.isVar) {
            body.append("${lineDelimiter}set(value) {\n}")
        }
        return KtPsiFactory(classOrObject.project).createProperty("${OVERRIDE_RENDERER.render(newDescriptor)}$body")
    }

    private fun overrideFunction(classOrObject: KtClassOrObject,
                                 descriptor: FunctionDescriptor,
                                 lineDelimiter: String): KtNamedFunction {
        val newDescriptor: FunctionDescriptor = descriptor.copy(descriptor.containingDeclaration, Modality.OPEN, descriptor.visibility,
                descriptor.kind, /* copyOverrides = */ true)
        newDescriptor.overriddenDescriptors = listOf(descriptor)

        val returnType = descriptor.returnType
        val returnsNotUnit = returnType != null && !KotlinBuiltIns.isUnit(returnType)
        val isAbstract = descriptor.modality == Modality.ABSTRACT

        val delegation = generateUnsupportedOrSuperCall(descriptor)

        val body = "{$lineDelimiter${if (returnsNotUnit && !isAbstract) "return " else ""}$delegation$lineDelimiter}"

        return KtPsiFactory(classOrObject.project).createFunction("${OVERRIDE_RENDERER.render(newDescriptor)}$body")
    }

    private fun generateOverridingMembers(selectedElements: Set<CallableMemberDescriptor>,
                                          classOrObject: KtClassOrObject,
                                          lineDelimiter: String): List<KtElement> {
        val overridingMembers = arrayListOf<KtElement>()
        for (selectedElement in selectedElements) {
            if (selectedElement is SimpleFunctionDescriptor) {
                overridingMembers.add(overrideFunction(classOrObject, selectedElement, lineDelimiter))
            } else if (selectedElement is PropertyDescriptor) {
                overridingMembers.add(overrideProperty(classOrObject, selectedElement, lineDelimiter))
            }
        }
        return overridingMembers
    }
}