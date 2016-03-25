/*
 * Copyright 2010-2015 JetBrains s.r.o.
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
 */

package org.jetbrains.kotlin.idea.core

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.idea.util.FuzzyType
import org.jetbrains.kotlin.idea.util.fuzzyExtensionReceiverType
import org.jetbrains.kotlin.idea.util.nullability
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.utils.collectFunctions
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.util.isValidOperator
import java.util.*

abstract class TypesWithOperatorDetector(
        private val name: Name,
        private val scope: LexicalScope,
        private val indicesHelper: KotlinIndicesHelper?
) {
    protected abstract fun checkIsSuitableByType(function: FunctionDescriptor, freeTypeParams: Collection<TypeParameterDescriptor>): TypeSubstitutor?

    private val cache = HashMap<FuzzyType, Pair<FunctionDescriptor, TypeSubstitutor>?>()

    val extensionOperators: Collection<FunctionDescriptor> by lazy {
        val result = ArrayList<FunctionDescriptor>()

        val extensionsFromScope = scope
                .collectFunctions(name, NoLookupLocation.FROM_IDE)
                .filter { it.extensionReceiverParameter != null }
        result.addSuitableOperators(extensionsFromScope)

        indicesHelper?.getTopLevelExtensionOperatorsByName(name.asString())?.let { result.addSuitableOperators(it) }

        result.distinctBy { it.original }
    }

    val classesWithMemberOperators: Collection<ClassDescriptor> by lazy {
        if (indicesHelper == null) return@lazy emptyList<ClassDescriptor>()
        val operators = ArrayList<FunctionDescriptor>().addSuitableOperators(indicesHelper.getMemberOperatorsByName(name.asString()))
        operators.map { it.containingDeclaration as ClassDescriptor }.distinct()
    }

    private fun MutableCollection<FunctionDescriptor>.addSuitableOperators(functions: Collection<FunctionDescriptor>): MutableCollection<FunctionDescriptor> {
        for (function in functions) {
            if (!function.isValidOperator()) continue
            val substitutor = checkIsSuitableByType(function, function.typeParameters) ?: continue
            add(function.substitute(substitutor))
        }
        return this
    }

    fun findOperator(type: FuzzyType): Pair<FunctionDescriptor, TypeSubstitutor>? {
        if (cache.containsKey(type)) {
            return cache[type]
        }
        else {
            val result = findOperatorNoCache(type)
            cache[type] = result
            return result
        }
    }

    private fun findOperatorNoCache(type: FuzzyType): Pair<FunctionDescriptor, TypeSubstitutor>? {
        if (type.nullability() != TypeNullability.NULLABLE) {
            for (memberFunction in type.type.memberScope.getContributedFunctions(name, NoLookupLocation.FROM_IDE)) {
                if (memberFunction.isValidOperator()) {
                    checkIsSuitableByType(memberFunction, type.freeParameters)?.let { substitutor ->
                        return Pair(memberFunction.substitute(substitutor), substitutor)
                    }
                }
            }
        }

        for (operator in extensionOperators) {
            val substitutor = type.checkIsSubtypeOf(operator.fuzzyExtensionReceiverType()!!)
            if (substitutor != null) {
                return Pair(operator.substitute(substitutor), substitutor)
            }
        }

        return null
    }
}

class TypesWithContainsDetector(
        scope: LexicalScope,
        indicesHelper: KotlinIndicesHelper?,
        private val argumentType: KotlinType
) : TypesWithOperatorDetector(OperatorNameConventions.CONTAINS, scope, indicesHelper) {

    override fun checkIsSuitableByType(function: FunctionDescriptor, freeTypeParams: Collection<TypeParameterDescriptor>): TypeSubstitutor? {
        val parameter = function.valueParameters.single()
        val fuzzyParameterType = FuzzyType(parameter.type, function.typeParameters + freeTypeParams)
        return fuzzyParameterType.checkIsSuperTypeOf(argumentType)
    }
}

class TypesWithGetValueDetector(
        scope: LexicalScope,
        indicesHelper: KotlinIndicesHelper?,
        private val propertyOwnerType: KotlinType,
        private val propertyType: KotlinType?
) : TypesWithOperatorDetector(OperatorNameConventions.GET_VALUE, scope, indicesHelper) {

    override fun checkIsSuitableByType(function: FunctionDescriptor, freeTypeParams: Collection<TypeParameterDescriptor>): TypeSubstitutor? {
        val paramType = FuzzyType(function.valueParameters.first().type, freeTypeParams)
        val substitutor = paramType.checkIsSuperTypeOf(propertyOwnerType) ?: return null

        if (propertyType == null) return substitutor

        val fuzzyReturnType = FuzzyType(function.returnType ?: return null, freeTypeParams)
        val substitutorFromPropertyType = fuzzyReturnType.checkIsSubtypeOf(propertyType) ?: return null
        return TypeSubstitutor.createChainedSubstitutor(substitutor.substitution, substitutorFromPropertyType.substitution)
    }
}

class TypesWithSetValueDetector(
        scope: LexicalScope,
        indicesHelper: KotlinIndicesHelper?,
        private val propertyOwnerType: KotlinType
) : TypesWithOperatorDetector(OperatorNameConventions.SET_VALUE, scope, indicesHelper) {

    override fun checkIsSuitableByType(function: FunctionDescriptor, freeTypeParams: Collection<TypeParameterDescriptor>): TypeSubstitutor? {
        val paramType = FuzzyType(function.valueParameters.first().type, freeTypeParams)
        return paramType.checkIsSuperTypeOf(propertyOwnerType)
    }
}