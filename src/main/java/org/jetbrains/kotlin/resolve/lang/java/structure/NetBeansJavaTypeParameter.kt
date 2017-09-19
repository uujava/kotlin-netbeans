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
package org.jetbrains.kotlin.resolve.lang.java.structure

import org.jetbrains.kotlin.load.java.structure.JavaAnnotation
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.netbeans.api.project.Project
import org.jetbrains.kotlin.resolve.lang.java.*
import javax.lang.model.element.TypeParameterElement

/*

  @author Alexander.Baratynski
  Created on Sep 7, 2016
*/

class NetBeansJavaTypeParameter(elementHandle: ElemHandle<TypeParameterElement>, project: Project) :
        NetBeansJavaClassifier<TypeParameterElement>(elementHandle, project), JavaTypeParameter {

    override val name: Name
        get() = elementHandle.getName(project)

    override val upperBounds: Collection<JavaClassifierType>
        get() = elementHandle.getUpperBounds(project)

    override val annotations: Collection<JavaAnnotation>
        get() = emptyList()

    override fun findAnnotation(fqName: FqName): JavaAnnotation? = null
    override fun toString(): String = name.asString()

    override fun equals(other: Any?): Boolean {
        if (other !is NetBeansJavaTypeParameter) return false

        val bound = upperBounds.firstOrNull()?.classifierQualifiedName ?: ""
        val otherBound = other.upperBounds.firstOrNull()?.classifierQualifiedName ?: ""

        val fullName = "$name $bound"
        val otherFullName = "${other.name} $otherBound"

        return fullName == otherFullName
    }


    override fun hashCode(): Int {
        val bound = upperBounds.firstOrNull()?.classifierQualifiedName ?: ""

        return "$name $bound".hashCode()
    }

}