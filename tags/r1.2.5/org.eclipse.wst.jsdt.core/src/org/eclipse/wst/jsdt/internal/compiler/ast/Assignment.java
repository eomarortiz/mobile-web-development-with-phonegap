/*******************************************************************************
 * Copyright (c) 2000, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Genady Beriozkin - added support for reporting assignment with no effect
 *******************************************************************************/
package org.eclipse.wst.jsdt.internal.compiler.ast;

import org.eclipse.wst.jsdt.core.ast.IASTNode;
import org.eclipse.wst.jsdt.core.ast.IAssignment;
import org.eclipse.wst.jsdt.core.ast.IExpression;
import org.eclipse.wst.jsdt.core.ast.IJsDoc;
import org.eclipse.wst.jsdt.core.infer.InferredType;
import org.eclipse.wst.jsdt.internal.compiler.ASTVisitor;
import org.eclipse.wst.jsdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowContext;
import org.eclipse.wst.jsdt.internal.compiler.flow.FlowInfo;
import org.eclipse.wst.jsdt.internal.compiler.impl.Constant;
import org.eclipse.wst.jsdt.internal.compiler.lookup.BaseTypeBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.Binding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.BlockScope;
import org.eclipse.wst.jsdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.wst.jsdt.internal.compiler.lookup.TagBits;
import org.eclipse.wst.jsdt.internal.compiler.lookup.TypeBinding;

public class Assignment extends Expression implements IAssignment {

	public Expression lhs;
	public Expression expression;
	public Javadoc javadoc;
	public InferredType inferredType;

public Assignment(Expression lhs, Expression expression, int sourceEnd) {
	//lhs is always a reference by construction ,
	//but is build as an expression ==> the checkcast cannot fail
	this.lhs = lhs;
	lhs.bits |= IsStrictlyAssigned; // tag lhs as assigned
	this.expression = expression;
	this.sourceStart = lhs.sourceStart;
	this.sourceEnd = sourceEnd;
}

public FlowInfo analyseCode(BlockScope currentScope, FlowContext flowContext, FlowInfo flowInfo) {
	// record setting a variable: various scenarii are possible, setting an array reference,
// a field reference, a blank final field reference, a field of an enclosing instance or
// just a local variable.
	LocalVariableBinding local = this.lhs.localVariableBinding();
//	if (local!=null && local.isSameCompilationUnit(currentScope))
//		local=null;
	int nullStatus = this.expression.nullStatus(flowInfo);
	if (local != null && (local.type.tagBits & TagBits.IsBaseType) == 0) {
		if (nullStatus == FlowInfo.NULL) {
			flowContext.recordUsingNullReference(currentScope, local, this.lhs,
				FlowContext.CAN_ONLY_NULL| FlowContext.IN_ASSIGNMENT, flowInfo);
		}
	}
	flowInfo = ((Reference) lhs)
		.analyseAssignment(currentScope, flowContext, flowInfo, this, false)
		.unconditionalInits();
	if (local != null && (local.type.tagBits & TagBits.IsBaseType) == 0) {
		switch(nullStatus) {
			case FlowInfo.NULL :
				flowInfo.markAsDefinitelyNull(local);
				break;
			case FlowInfo.NON_NULL :
				flowInfo.markAsDefinitelyNonNull(local);
				break;
			default:
				flowInfo.markAsDefinitelyUnknown(local);
		}
		if (flowContext.initsOnFinally != null) {
			switch(nullStatus) {
				case FlowInfo.NULL :
					flowContext.initsOnFinally.markAsDefinitelyNull(local);
					break;
				case FlowInfo.NON_NULL :
					flowContext.initsOnFinally.markAsDefinitelyNonNull(local);
					break;
				default:
					flowContext.initsOnFinally.markAsDefinitelyUnknown(local);
			}
		}
	}
	return flowInfo;
}

void checkAssignment(BlockScope scope, TypeBinding lhsType, TypeBinding rhsType) {
//	FieldBinding leftField = getLastField(this.lhs);
//	if (leftField != null && !leftField.isStatic() && leftField.declaringClass != null /*length pseudo field*/&& leftField.declaringClass.isRawType()) {
//	    scope.problemReporter().unsafeRawFieldAssignment(leftField, rhsType, this.lhs);
//	} else
}

public static Binding getDirectBinding(Expression someExpression) {
	if ((someExpression.bits & ASTNode.IgnoreNoEffectAssignCheck) != 0) {
		return null;
	}
	if (someExpression instanceof SingleNameReference) {
		return ((SingleNameReference)someExpression).binding;
	} else if (someExpression instanceof FieldReference) {
		FieldReference fieldRef = (FieldReference)someExpression;
		if (fieldRef.receiver.isThis() && !(fieldRef.receiver instanceof QualifiedThisReference)) {
			return fieldRef.binding;
		}
	} else if (someExpression instanceof Assignment) {
		Expression lhs = ((Assignment)someExpression).lhs;
		if ((lhs.bits & ASTNode.IsStrictlyAssigned) != 0) {
			// i = i = ...; // eq to int i = ...;
			return getDirectBinding (((Assignment)someExpression).lhs);
		} else if (someExpression instanceof PrefixExpression) {
			// i = i++; // eq to ++i;
			return getDirectBinding (((Assignment)someExpression).lhs);
		}
	}
//		} else if (someExpression instanceof PostfixExpression) { // recurse for postfix: i++ --> i
//			// note: "b = b++" is equivalent to doing nothing, not to "b++"
//			return getDirectBinding(((PostfixExpression) someExpression).lhs);
	return null;
}



public int nullStatus(FlowInfo flowInfo) {
	return this.expression.nullStatus(flowInfo);
}

public StringBuffer print(int indent, StringBuffer output) {
	//no () when used as a statement
	printIndent(indent, output);
	return printExpressionNoParenthesis(indent, output);
}
public StringBuffer printExpression(int indent, StringBuffer output) {
	//subclass redefine printExpressionNoParenthesis()
	output.append('(');
	return printExpressionNoParenthesis(0, output).append(')');
}

public StringBuffer printExpressionNoParenthesis(int indent, StringBuffer output) {
	lhs.printExpression(indent, output).append(" = "); //$NON-NLS-1$
	return expression.printExpression(0, output);
}

public StringBuffer printStatement(int indent, StringBuffer output) {
	//no () when used as a statement
	return print(indent, output).append(';');
}

public TypeBinding resolveType(BlockScope scope) {
	// due to syntax lhs may be only a NameReference, a FieldReference or an ArrayReference
	this.constant = Constant.NotAConstant;
	if (!(this.lhs instanceof Reference) || this.lhs.isThis()) {
		scope.problemReporter().expressionShouldBeAVariable(this.lhs);
		return null;
	}
	TypeBinding rhsType = this.expression.resolveType(scope);
	TypeBinding lhsType = lhs.resolveType(scope,true,rhsType);
//	this.expression.setExpectedType(lhsType); // needed in case of generic method invocation
	if (lhsType != null)
		this.resolvedType = lhsType;
	if (lhsType == null || rhsType == null) {
		return null;
	}

	//check if the lhs is prototype, in which case we are done
	if( lhs instanceof FieldReference && ((FieldReference)lhs).isPrototype() )
		return this.resolvedType;

	// check for assignment with no effect
	Binding left = getDirectBinding(this.lhs);
	if (left != null && left == getDirectBinding(this.expression)) {
		scope.problemReporter().assignmentHasNoEffect(this, left.shortReadableName());
	}

	// Compile-time conversion of base-types : implicit narrowing integer into byte/short/character
	// may require to widen the rhs expression at runtime
//	if (lhsType != rhsType) // must call before computeConversion() and typeMismatchError()
//		scope.compilationUnitScope().recordTypeConversion(lhsType, rhsType);


	if ((this.expression.isConstantValueOfTypeAssignableToType(rhsType, lhsType)
			|| (lhsType.isBaseType() && BaseTypeBinding.isWidening(lhsType.id, rhsType.id)))
			|| rhsType.isCompatibleWith(lhsType)) {
//		this.expression.computeConversion(scope, lhsType, rhsType);
		checkAssignment(scope, lhsType, rhsType);
		return this.resolvedType;
	} else if (scope.isBoxingCompatibleWith(rhsType, lhsType)
						|| (rhsType.isBaseType()  // narrowing then boxing ?
								&& scope.compilerOptions().sourceLevel >= ClassFileConstants.JDK1_5 // autoboxing
								&& !lhsType.isBaseType()
								&& this.expression.isConstantValueOfTypeAssignableToType(rhsType, scope.environment().computeBoxingType(lhsType)))) {
		return this.resolvedType;
	}
	if (rhsType.isFunctionType() && this.lhs.isTypeReference())
		return lhsType;
	scope.problemReporter().typeMismatchError(rhsType, lhsType, this.expression);
	return lhsType;
}

/**
 * @see org.eclipse.wst.jsdt.internal.compiler.ast.Expression#resolveTypeExpecting(org.eclipse.wst.jsdt.internal.compiler.lookup.BlockScope, org.eclipse.wst.jsdt.internal.compiler.lookup.TypeBinding)
 */
public TypeBinding resolveTypeExpecting(BlockScope scope, TypeBinding expectedType) {

	TypeBinding type = super.resolveTypeExpecting(scope, expectedType);
	if (type == null) return null;
	TypeBinding lhsType = this.resolvedType;
	TypeBinding rhsType = this.expression.resolvedType;
	// signal possible accidental boolean assignment (instead of using '==' operator)
	if (expectedType == TypeBinding.BOOLEAN
			&& lhsType == TypeBinding.BOOLEAN
			&& (this.lhs.bits & IsStrictlyAssigned) != 0) {
		scope.problemReporter().possibleAccidentalBooleanAssignment(this);
	}
	checkAssignment(scope, lhsType, rhsType);
	return type;
}

public void traverse(ASTVisitor visitor, BlockScope scope) {
	if (visitor.visit(this, scope)) {
		lhs.traverse(visitor, scope);
		expression.traverse(visitor, scope);
	}
	visitor.endVisit(this, scope);
}
public LocalVariableBinding localVariableBinding() {
	return lhs.localVariableBinding();
}
public int getASTType() {
	return IASTNode.ASSIGNMENT;

}

public IExpression getExpression() {
	return this.expression;
}

public IExpression getLeftHandSide() {
	return this.lhs;
}

public IJsDoc getJsDoc() {
	return javadoc;
}

public void setInferredType(InferredType type) {
	this.inferredType = type;
	
}

public InferredType getInferredType() {
	return this.inferredType;
}
}
