/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */


package com.akiban.server.expression.std;

import com.akiban.server.expression.Expression;
import com.akiban.server.expression.ExpressionComposer;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.expression.ExpressionType;
import com.akiban.server.service.functions.Scalar;
import com.akiban.server.types.AkType;
import com.akiban.server.types.NullValueSource;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.extract.ObjectExtractor;
import com.akiban.server.types.extract.Extractors;
import com.akiban.server.types.util.ValueHolder;


public class CaseConvertExpression extends AbstractUnaryExpression
{
    public static enum ConversionType
    {
        TOUPPER,
        TOLOWER 
    }
    
    private final ConversionType conversionType;
    
    @Scalar ("lcase")
    public static final ExpressionComposer TOLOWER_COMPOSER = new InternalComposer(ConversionType.TOLOWER);
    
    @Scalar ("upcase")
    public static final ExpressionComposer TOUPPER_COMPOSER = new InternalComposer(ConversionType.TOUPPER);
    
    private static final class InternalComposer extends UnaryComposer
    {
        private final ConversionType conversionType;

        public InternalComposer (ConversionType conversionType)
        {
            this.conversionType = conversionType;
        }
        
        @Override
        protected Expression compose(Expression argument) 
        {
            return new CaseConvertExpression(argument, conversionType);
        }         

        @Override
        protected AkType argumentType(AkType givenType) {
            return AkType.VARCHAR;
        }

        @Override
        protected ExpressionType composeType(ExpressionType argumentType) {
            return argumentType; // Same width.
        }
    }
    
    private static final class InnerEvaluation extends AbstractUnaryExpressionEvaluation
    {
        private final ConversionType conversionType;
        
        public InnerEvaluation (ExpressionEvaluation ev, ConversionType conversionType)
        {
            super(ev);
            this.conversionType = conversionType;
        }

        @Override
        public ValueSource eval() 
        {
            ValueSource operandSource = operand();
            if (operandSource.isNull())
                return NullValueSource.only();
            
            ObjectExtractor<String> sExtractor = Extractors.getStringExtractor();
            String st = sExtractor.getObject(operandSource);
            return new ValueHolder(AkType.VARCHAR, 
                    conversionType == ConversionType.TOLOWER ? st.toLowerCase() : st.toUpperCase());
        }         
    }
     
    /**
     * 
     * @param operand
     * @param type 
     */
    public CaseConvertExpression (Expression operand, ConversionType type)
    {
        super(AkType.VARCHAR, operand);
        this.conversionType = type;
    }

    @Override
    protected String name() {
        return conversionType.name();
    }

    @Override
    public ExpressionEvaluation evaluation() {
        return new InnerEvaluation(this.operandEvaluation(), conversionType);
    }
}