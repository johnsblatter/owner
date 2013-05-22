/*
 * Copyright (c) 2013, Luigi R. Viggiano
 * All rights reserved.
 *
 * This software is distributable under the BSD license.
 * See the terms of the BSD license in the documentation provided with this software.
 */

package org.aeonbits.owner;

import org.aeonbits.owner.Config.Separator;
import org.aeonbits.owner.Config.Tokenizer;
import org.aeonbits.owner.Config.TokenizerClass;

import java.beans.PropertyEditor;
import java.beans.PropertyEditorManager;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static java.lang.reflect.Modifier.isStatic;
import static org.aeonbits.owner.Util.expandUserHome;
import static org.aeonbits.owner.Util.unsupported;

/**
 * Converter class from {@link java.lang.String} to property types.
 *
 * @author Luigi R. Viggiano
 */
enum Converters {
    PROPERTY_EDITOR {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            PropertyEditor editor = PropertyEditorManager.findEditor(targetType);
            if (editor != null) {
                editor.setAsText(text);
                return editor.getValue();
            }
            return null;
        }
    },

    FILE {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            if (targetType == File.class)
                return new File(expandUserHome(text));
            return null;
        }
    },

    CLASS_WITH_STRING_CONSTRUCTOR {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            try {
                Constructor<?> constructor = targetType.getConstructor(String.class);
                return constructor.newInstance(text);
            } catch (Exception e) {
                return null;
            }
        }
    },

    CLASS_WITH_OBJECT_CONSTRUCTOR {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            try {
                Constructor<?> constructor = targetType.getConstructor(Object.class);
                return constructor.newInstance(text);
            } catch (Exception e) {
                return null;
            }
        }
    },

    CLASS_WITH_VALUE_OF_METHOD {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            try {
                Method method = targetType.getMethod("valueOf", String.class);
                if (isStatic(method.getModifiers()))
                    return method.invoke(null, text);
                return null;
            } catch (Exception e) {
                return null;
            }
        }
    },

    CLASS {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            try {
                return Class.forName(text);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
    },

    ARRAY {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            if (!targetType.isArray())
                return null;

            Class<?> type = targetType.getComponentType();

            if (text.trim().isEmpty())
                return Array.newInstance(type, 0);

            Tokenizer tokenizer = getTokenizer(targetMethod);
            String[] chunks = tokenizer.tokens(text);

            Converters converter = doConvert(targetMethod, type, chunks[0]).getConverter();
            Object result = Array.newInstance(type, chunks.length);

            for (int i = 0; i < chunks.length; i++) {
                String chunk = chunks[i];
                Object value = converter.tryConvert(targetMethod, type, chunk);
                Array.set(result, i, value);
            }

            return result;
        }
    },

    UNSUPPORTED {
        @Override
        Object tryConvert(Method targetMethod, Class<?> targetType, String text) {
            throw unsupportedConversion(targetType, text);
        }
    };

    // TODO: remark for Luigi, refactor the code below into a new class.
    private static Tokenizer defaultTokenizer = new SplitAndTrimTokenizer(",");
    private static Tokenizer getTokenizer(Method targetMethod) {
        Class<?> declaringClass = targetMethod.getDeclaringClass();
        Separator separatorAnnotationOnClassLevel = declaringClass.getAnnotation(Separator.class);
        TokenizerClass tokenizerClassAnnotationOnClassLevel = declaringClass.getAnnotation(TokenizerClass.class);

        Separator separatorAnnotationOnMethodLevel = targetMethod.getAnnotation(Separator.class);
        TokenizerClass tokenizerClassAnnotationOnMethodLevel = targetMethod.getAnnotation(TokenizerClass.class);

        if (separatorAnnotationOnClassLevel != null && tokenizerClassAnnotationOnClassLevel != null)
            throw unsupported(
                    "You cannot specify both @Separator and @TokenizerClass together on class '%s'",
                    declaringClass.getCanonicalName());

        if (separatorAnnotationOnMethodLevel != null && tokenizerClassAnnotationOnMethodLevel != null)
            throw unsupported(
                    "You cannot specify both @Separator and @TokenizerClass together on method '%s'", targetMethod);

        if (separatorAnnotationOnMethodLevel != null)
            return new SplitAndTrimTokenizer(separatorAnnotationOnMethodLevel.value());

        if (tokenizerClassAnnotationOnMethodLevel != null)
            return createTokenizer(tokenizerClassAnnotationOnMethodLevel.value());

        if (separatorAnnotationOnClassLevel != null)
            return new SplitAndTrimTokenizer(separatorAnnotationOnClassLevel.value());

        if (tokenizerClassAnnotationOnClassLevel != null)
            return createTokenizer(tokenizerClassAnnotationOnClassLevel.value());

        return defaultTokenizer;
    }

    private static Tokenizer createTokenizer(Class<? extends Tokenizer> tokenizerClass) {
        try {
            return tokenizerClass.newInstance();
        } catch (Exception e) {
            throw unsupported(e,
                    "Tokenizer class '%s' cannot be instantiated; see the cause below in the stack trace",
                    tokenizerClass.getCanonicalName());
        }
    }
    //end TODO (remark for luigi): the above code must go out from here.

    abstract Object tryConvert(Method targetMethod, Class<?> targetType, String text);

    static Object convert(Method targetMethod, Class<?> targetType, String text) {
        return doConvert(targetMethod, targetType, text).getConvertedValue();
    }

    private static ConversionResult doConvert(Method targetMethod, Class<?> targetType, String text) {
        for (Converters converter : values()) {
            Object convertedValue = converter.tryConvert(targetMethod, targetType, text);
            if (convertedValue != null)
                return new ConversionResult(converter, convertedValue);
        }
        throw unsupportedConversion(targetType, text); // this line is unreachable, but compiler needs it.
    }

    private static UnsupportedOperationException unsupportedConversion(Class<?> targetType, String text) {
        return unsupported("Cannot convert '%s' to %s", text, targetType.getCanonicalName());
    }

    private static class ConversionResult {
        private final Converters converter;
        private final Object convertedValue;

        public ConversionResult(Converters converter, Object convertedValue) {
            this.converter = converter;
            this.convertedValue = convertedValue;
        }

        public Converters getConverter() {
            return converter;
        }

        public Object getConvertedValue() {
            return convertedValue;
        }
    }
}
