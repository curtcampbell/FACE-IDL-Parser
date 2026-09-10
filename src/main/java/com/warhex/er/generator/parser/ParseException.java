package com.warhex.er.generator.parser;

/**
 * Thrown when the FACE IDL parser encounters a syntax error or an unresolvable
 * include directive.
 */
public class ParseException extends RuntimeException {

    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
