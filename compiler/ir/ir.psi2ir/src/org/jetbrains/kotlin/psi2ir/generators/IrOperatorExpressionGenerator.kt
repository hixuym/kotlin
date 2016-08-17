/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.psi2ir.generators

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor
import org.jetbrains.kotlin.ir.expressions.IrBinaryOperatorExpressionImpl
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrOperator
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi2ir.generators.values.*
import org.jetbrains.kotlin.psi2ir.toExpectedType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.isSafeCall
import org.jetbrains.kotlin.types.typeUtil.makeNullable

internal val KT_TOKEN_TO_IR_OPERATOR =
        mapOf(
                KtTokens.EQ to IrOperator.EQ,

                KtTokens.PLUSEQ to IrOperator.PLUSEQ,
                KtTokens.MINUSEQ to IrOperator.MINUSEQ,
                KtTokens.MULTEQ to IrOperator.MULTEQ,
                KtTokens.DIVEQ to IrOperator.DIVEQ,
                KtTokens.PERCEQ to IrOperator.PERCEQ,

                KtTokens.PLUS to IrOperator.PLUS,
                KtTokens.MINUS to IrOperator.MINUS,
                KtTokens.MUL to IrOperator.MUL,
                KtTokens.DIV to IrOperator.DIV,
                KtTokens.PERC to IrOperator.PERC,
                KtTokens.RANGE to IrOperator.RANGE,

                KtTokens.LT to IrOperator.LT,
                KtTokens.LTEQ to IrOperator.LTEQ,
                KtTokens.GT to IrOperator.GT,
                KtTokens.GTEQ to IrOperator.GTEQ,

                KtTokens.EQEQ to IrOperator.EQEQ,
                KtTokens.EXCLEQ to IrOperator.EXCLEQ,
                KtTokens.EQEQEQ to IrOperator.EQEQEQ,
                KtTokens.EXCLEQEQEQ to IrOperator.EXCLEQEQ
        )

internal val AUGMENTED_ASSIGNMENTS =
        setOf(IrOperator.PLUSEQ, IrOperator.MINUSEQ, IrOperator.MULTEQ, IrOperator.DIVEQ, IrOperator.PERCEQ)

internal val BINARY_OPERATORS_DESUGARED_TO_CALLS =
        setOf(IrOperator.PLUS, IrOperator.MINUS, IrOperator.MUL, IrOperator.DIV, IrOperator.PERC, IrOperator.RANGE)

internal val COMPARISON_OPERATORS =
        setOf(IrOperator.LT, IrOperator.LTEQ, IrOperator.GT, IrOperator.GTEQ)

internal val EQUALITY_OPERATORS =
        setOf(IrOperator.EQEQ, IrOperator.EXCLEQ)

internal val IDENTITY_OPERATORS =
        setOf(IrOperator.EQEQEQ, IrOperator.EXCLEQEQ)

class IrOperatorExpressionGenerator(val irStatementGenerator: IrStatementGenerator): IrGenerator {
    override val context: IrGeneratorContext get() = irStatementGenerator.context

    fun generateBinaryExpression(expression: KtBinaryExpression): IrExpression {
        val ktOperator = expression.operationReference.getReferencedNameElementType()
        val irOperator = getIrOperator(ktOperator)

        return when (irOperator) {
            null -> createDummyExpression(expression, ktOperator.toString())
            IrOperator.EQ -> generateAssignment(expression)
            in AUGMENTED_ASSIGNMENTS -> generateAugmentedAssignment(expression, irOperator)
            in BINARY_OPERATORS_DESUGARED_TO_CALLS -> generateBinaryOperatorWithConventionalCall(expression, irOperator)
            in COMPARISON_OPERATORS -> generateComparisonOperator(expression, irOperator)
            in EQUALITY_OPERATORS -> generateEqualityOperator(expression, irOperator)
            in IDENTITY_OPERATORS -> generateIdentityOperator(expression, irOperator)
            else -> createDummyExpression(expression, ktOperator.toString())
        }
    }

    private fun generateIdentityOperator(expression: KtBinaryExpression, irOperator: IrOperator): IrExpression {
        val irArgument0 = irStatementGenerator.generateExpression(expression.left!!)
        val irArgument1 = irStatementGenerator.generateExpression(expression.right!!)
        return IrBinaryOperatorExpressionImpl(
                expression.startOffset, expression.endOffset, context.builtIns.booleanType,
                irOperator, null, irArgument0, irArgument1
        )
    }

    private fun generateEqualityOperator(expression: KtBinaryExpression, irOperator: IrOperator): IrExpression {
        val relatedCall = getResolvedCall(expression)!!
        val relatedDescriptor = relatedCall.resultingDescriptor

        val irCallGenerator = IrCallGenerator(irStatementGenerator)

        // NB special typing rules for equality operators: both arguments are nullable

        val irArgument0 =
                irCallGenerator.generateReceiver(expression.left!!, relatedCall.dispatchReceiver)!!
                        .toExpectedType(relatedDescriptor.dispatchReceiverParameter!!.type.makeNullable())

        val valueParameter0 = relatedDescriptor.valueParameters[0]
        val irArgument1 =
                irCallGenerator.generateValueArgument(
                        relatedCall.valueArgumentsByIndex!![0],
                        valueParameter0, valueParameter0.type.makeNullable()
                )!!

        return IrBinaryOperatorExpressionImpl(
                expression.startOffset, expression.endOffset, context.builtIns.booleanType,
                irOperator, relatedDescriptor, irArgument0, irArgument1
        )
    }

    private fun generateComparisonOperator(expression: KtBinaryExpression, irOperator: IrOperator): IrExpression {
        val relatedCall = getResolvedCall(expression)!!
        val relatedDescriptor = relatedCall.resultingDescriptor

        val irCallGenerator = IrCallGenerator(irStatementGenerator)
        val irArgument0 = irCallGenerator.generateReceiver(expression.left!!, relatedCall.dispatchReceiver, relatedDescriptor.dispatchReceiverParameter!!)!!
        val valueParameter0 = relatedDescriptor.valueParameters[0]
        val irArgument1 = irCallGenerator.generateValueArgument(relatedCall.valueArgumentsByIndex!![0], valueParameter0)!!
        return IrBinaryOperatorExpressionImpl(
                expression.startOffset, expression.endOffset, context.builtIns.booleanType,
                irOperator, relatedDescriptor, irArgument0, irArgument1
        )
    }

    private fun generateBinaryOperatorWithConventionalCall(expression: KtBinaryExpression, irOperator: IrOperator): IrExpression {
        val operatorCall = getResolvedCall(expression)!!
        return IrCallGenerator(irStatementGenerator).generateCall(expression, operatorCall, irOperator)
    }

    private fun generateAugmentedAssignment(expression: KtBinaryExpression, irOperator: IrOperator): IrExpression {
        val ktLeft = expression.left!!

        val irLhs = generateLValue(ktLeft, irOperator)

        val isSimpleAssignment = get(BindingContext.VARIABLE_REASSIGNMENT, expression) ?: false

        val operatorCall = getResolvedCall(expression)!!

        if (isSimpleAssignment && irLhs is IrLValueWithAugmentedStore) {
            return irLhs.augmentedStore(operatorCall, irStatementGenerator.generateExpression(expression.right!!))
        }

        val opCallGenerator = IrCallGenerator(irStatementGenerator).apply { putValue(ktLeft, irLhs) }
        val irOpCall = opCallGenerator.generateCall(expression, operatorCall, irOperator)

        return if (isSimpleAssignment) {
            // Set( Op( Get(), RHS ) )
            irLhs.store(irOpCall)
        }
        else {
            // Op( Get(), RHS )
            irOpCall
        }
    }

    private fun getIrOperator(ktOperator: IElementType): IrOperator? =
            KT_TOKEN_TO_IR_OPERATOR[ktOperator]

    private fun generateAssignment(expression: KtBinaryExpression): IrExpression {
        val ktLeft = expression.left!!
        val ktRight = expression.right!!
        val lhsReference = generateLValue(ktLeft, IrOperator.EQ)
        return lhsReference.store(irStatementGenerator.generateExpression(ktRight))
    }

    private fun generateLValue(ktLeft: KtExpression, irOperator: IrOperator?): IrLValue {
        if (ktLeft is KtArrayAccessExpression) {
            val irArrayValue = irStatementGenerator.generateExpression(ktLeft.arrayExpression!!)
            val indexExpressions = ktLeft.indexExpressions.map { it to irStatementGenerator.generateExpression(it) }
            val indexedGetCall = get(BindingContext.INDEXED_LVALUE_GET, ktLeft)
            val indexedSetCall = get(BindingContext.INDEXED_LVALUE_SET, ktLeft)
            return IrIndexedLValue(irStatementGenerator, ktLeft, irOperator,
                                   irArrayValue, indexExpressions, indexedGetCall, indexedSetCall)
        }

        val resolvedCall = getResolvedCall(ktLeft) ?: TODO("no resolved call for LHS")
        val descriptor = resolvedCall.candidateDescriptor

        return when (descriptor) {
            is LocalVariableDescriptor ->
                if (descriptor.isDelegated)
                    TODO("Delegated local variable")
                else
                    IrVariableLValueValue(ktLeft, irOperator, descriptor)
            is PropertyDescriptor ->
                IrCallGenerator(irStatementGenerator).run {
                    IrPropertyLValueValue(
                            ktLeft, irOperator, descriptor,
                            generateReceiver(ktLeft, resolvedCall.dispatchReceiver, descriptor.dispatchReceiverParameter),
                            generateReceiver(ktLeft, resolvedCall.extensionReceiver, descriptor.extensionReceiverParameter),
                            resolvedCall.call.isSafeCall()
                    )
                }
            else ->
                TODO("Other cases of LHS")
        }
    }
}
