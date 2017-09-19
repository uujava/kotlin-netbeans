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
package org.jetbrains.kotlin.hints.intentions

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.text.Document
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.reformatting.format
import org.jetbrains.kotlin.hints.atomicChange
import org.jetbrains.kotlin.types.isError

class ConvertToBlockBodyIntention(doc: Document,
                                  analysisResult: AnalysisResult?,
                                  psi: PsiElement) : ApplicableIntention(doc, analysisResult, psi) {

    override fun isApplicable(caretOffset: Int): Boolean {
        val declaration = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return false
        if (declaration is KtFunctionLiteral || declaration.hasBlockBody() || !declaration.hasBody()) return false

        when (declaration) {
            is KtNamedFunction -> {
                val bindingContext = analysisResult?.bindingContext ?: return false
                val returnType: KotlinType = declaration.returnType(bindingContext) ?: return false

                // do not convert when type is implicit and unknown
                if (!declaration.hasDeclaredReturnType() && returnType.isError) return false

                return true
            }

            is KtPropertyAccessor -> return true

            else -> error("Unknown declaration type: $declaration")
        }
    }

    override fun getDescription() = "Convert to block body"

    override fun implement() {
        val declaration = PsiTreeUtil.getParentOfType(psi, KtDeclarationWithBody::class.java) ?: return
        val context = analysisResult?.bindingContext ?: return

        val shouldSpecifyType = declaration is KtNamedFunction
                && !declaration.hasDeclaredReturnType()
                && !KotlinBuiltIns.isUnit(declaration.returnType(context)!!)

        val factory = KtPsiFactory(declaration)

        replaceBody(declaration, factory, context, shouldSpecifyType)
    }

    private fun convert(declaration: KtDeclarationWithBody, bindingContext: BindingContext, factory: KtPsiFactory): KtExpression {
        val body = declaration.bodyExpression!!

        fun generateBody(returnsValue: Boolean): KtExpression {
            val bodyType = bindingContext.getType(body)
            val needReturn = returnsValue &&
                    (bodyType == null || (!KotlinBuiltIns.isUnit(bodyType) && !KotlinBuiltIns.isNothing(bodyType)))

            val expression = factory.createExpression(body.text)
            val block: KtBlockExpression = if (needReturn) {
                factory.createBlock("return xyz")
            } else {
                return factory.createBlock(expression.text)
            }
            val returnExpression = PsiTreeUtil.getChildOfType(block, KtReturnExpression::class.java)
            val returned = returnExpression?.returnedExpression ?: return factory.createBlock("return ${expression.text}")
            if (KtPsiUtil.areParenthesesNecessary(expression, returned, returnExpression)) {
                return factory.createBlock("return (${expression.text})")
            }
            return factory.createBlock("return ${expression.text}")
        }

        return when (declaration) {
            is KtNamedFunction -> {
                val returnType = declaration.returnType(bindingContext)!!
                generateBody(!KotlinBuiltIns.isUnit(returnType) && !KotlinBuiltIns.isNothing(returnType))
            }

            is KtPropertyAccessor -> generateBody(declaration.isGetter)

            else -> throw RuntimeException("Unknown declaration type: $declaration")
        }
    }

    private fun replaceBody(declaration: KtDeclarationWithBody, factory: KtPsiFactory,
                            context: BindingContext, shouldSpecifyType: Boolean) {
        val newBody = convert(declaration, context, factory)
        var newBodyText = newBody.node.text

        val anchorToken = declaration.equalsToken
        if (anchorToken!!.nextSibling !is PsiWhiteSpace) {
            newBodyText = "${factory.createWhiteSpace().text}$newBodyText"
        }

        val startOffset = anchorToken.textRange.startOffset
        val endOffset = declaration.bodyExpression!!.textRange.endOffset
        
        doc.atomicChange {
            remove(startOffset, endOffset - startOffset)
            insertString(startOffset, newBodyText, null)
            if (shouldSpecifyType) {
                specifyType(declaration, factory, context)
            }
            format(this, declaration.textRange.startOffset)
        }
    }

    private fun specifyType(declaration: KtDeclarationWithBody, factory: KtPsiFactory, context: BindingContext) {
        val returnType = (declaration as KtNamedFunction).returnType(context).toString()
        val stringToInsert = listOf(factory.createColon(), factory.createWhiteSpace())
                .joinToString(separator = "") { it.text } + returnType
        
        doc.insertString(declaration.valueParameterList!!.textRange.endOffset, stringToInsert, null)
    }

}

private fun KtNamedFunction.returnType(context: BindingContext): KotlinType? {
    val descriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, this] ?: return null
    return (descriptor as FunctionDescriptor).returnType
}