/*******************************************************************************
 * Copyright (c) 2025 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package io.openliberty.http.netty.channel.utils;

import java.util.Objects;
import com.ibm.ws.http.channel.internal.HttpChannelConfig;

/**
 * Processes and validates HTTP header names and values in compliance with
 * RFC 7230, "Hypertext Transfer Protocol (HTTP/1.1): Message Syntax
 * and Routing"
 * 
 * This utility ensures:
 * Header names are composed of valid "tchar" characters.
 * Header names and values do not exceed a configured maximum field length.
 * Control characters (except for valid folding) and non-ASCII characters
 * are properly handled.
 * When following a folding sequence, CR must be followed by LF, and LF
 * must be followed by a whitespace.
 * Trailing CR of LF is disallowed.
 * 
 * The class also normalizes header names by trimming leading/trailing
 * whitespaces from both names and values.
 */
public class HeaderValidator {

    private static final char CR = '\r';
    private static final char LF = '\n';
    private static final char TAB = '\t';
    private static final char SPACE = ' ';

    /**
     * Defines a lookup for valid header names (token characters or "tchars") as specified in
     * RFC 7230, Section 3.2.6, "Field Value Components".
     * ASCII range 0-127
     */
    private static final boolean[] T_CHAR = new boolean[128];
    static {
        String SPECIAL_TCHARS = "!#$%&'*+-.^_`|~";
        for (char c = '0'; c <= '9'; c++)
            T_CHAR[c] = true;
        for (char c = 'A'; c <= 'Z'; c++)
            T_CHAR[c] = true;
        for (char c = 'a'; c <= 'z'; c++)
            T_CHAR[c] = true;
        for (int i = 0; i < SPECIAL_TCHARS.length(); i++)
            T_CHAR[SPECIAL_TCHARS.charAt(i)] = true;
    }

    /**
     * Enumerates if the field token being processed is a header name or header value.
     */
    public enum FieldType {
        NAME, VALUE
    }

    private HeaderValidator() {
        //Utility Singleton
    }

    /**
     * Peforms processing of a header field (name or value).
     * 
     * This method normalizes a header field:
     * Ensures a non-null input if field type is {@link FieldType#NAME}.
     * Trims the input if it is non-null.
     * Substitutes a null input with an empty string if the field type is
     * {@link FieldType#VALUE}.
     * 
     * NOTE -> Should seek approval for:
     * Lowercases the token if the field type is {@link FieldType#NAME}
     * 
     * @param token  a raw header field token; may be {@code null} for values,
     *                   but not for names.
     * @param type   whether this token is a header name or value
     * @param config the HTTP configuration object
     * @return processed and possibly normalized header field, ensuring to
     *         comply with the configured validation requirements
     * @throws IllegalArgumentException if the field is invalid (too long,
     *                                      contains illegal characters, or null name)
     */
    public static String process(String token, FieldType type, HttpChannelConfig config) {

        Objects.requireNonNull(type);
        Objects.requireNonNull(config);

        if (type == FieldType.NAME && token == null) {
            throw new IllegalArgumentException("Header name must not be null");
        }
        String normalized = (token == null) ? "" : token.trim();

        // if (FEATURE_RFE_ENFORCE_FIELD_LEN && config.getLimitOfFieldSize() > 0
        //                     && normalized.length() > config.getLimitOfFieldSize()) {

        //     throw new IllegalArgumentException("Header " + type + " length " + normalized.length()
        //                                        + " exceeds configured limit " + config.getLimitOfFieldSize());
        // }

        // if(FEATURE_RFE_LOWERCASE_NAMES && type == FieldType.NAME){
        //     normalized = normalized.toLowerCase();
        // }

        return (type == FieldType.NAME) ? validateName(normalized, config) : validateValue(normalized, config);
    }

    private static String validateName(String token, HttpChannelConfig config) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Header name must not be empty");
        }

        if (!config.isHeaderValidationEnabled())
            return token;

        for (int i = 0, len = token.length(); i < len; i++) {
            char c = token.charAt(i);
            if (!isTChar(c)) {
                throw new IllegalArgumentException("Invalid character " + c + "in header name");
            }
        }
        return token;
    }

    private static String validateValue(String token, HttpChannelConfig config) {
        if (token.isEmpty() || !config.isHeaderValidationEnabled())
            return token;

        char lastChar = token.charAt(token.length() - 1);
        if (lastChar == CR || lastChar == LF) {
            throw new IllegalArgumentException("Illegal trailing EOL in header field: " + token);
        }

        //scan to see if we need to allocate the string builder
        for (int i = 0, len = token.length(); i < len; i++) {
            char c = token.charAt(i);
            if (c < 32 || c >= 127) {
                break;
            }
            if (i == len - 1) { // no change needed
                return token;
            }
        }

        StringBuilder sb = new StringBuilder(token.length());
        String error = null;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);

            if (c == CR) {
                if (i + 1 >= token.length() || token.charAt(i + 1) != LF) {
                    error = "Invalid CR not followed by LF in header " + token;
                }
            } else if (c == LF) {
                if (i + 1 < token.length()) {
                    char next = token.charAt(i + 1);
                    if (next != SPACE && next != TAB) {
                        error = "Invalid LF not followed by whitespace in header " + token;
                    }
                }
            }
            if (c >= 32 && c < 127) {
                sb.append(c);
            } else if (c == CR || c == LF) {
                sb.append(SPACE);
            } else {
                int maskedCode = c & 0xFF;
                if (maskedCode == CR || maskedCode == LF) {
                    sb.append('?');
                } else {
                    sb.append(c);
                }
            }
            if (error != null) {
                break;
            }
        }
        if (error != null) {
            throw new IllegalArgumentException(error);
        }

        return sb.toString();
    }

    private static boolean isTChar(char c) {
        return c < 128 && T_CHAR[c];
    }

}
