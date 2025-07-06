lexer grammar FACE_PreprocessLexer;

fragment FRAG_NEWLINE
    : ('\r'|'\n'|'\r\n' | EOF)
    ;

// Tokens
LINE_TEXT
    : ~[#]* FRAG_NEWLINE
    ;

COMMENT
    : '//' -> pushMode(LINE_COMMENT_MODE), channel(HIDDEN)
    ;

PREPROCESS_IND
    : '#'
    -> mode(PREPROCESSING);

//WS
//    : (' ' | '\r' | '\t' | '\u000C') -> channel (HIDDEN)
//    ;


mode PREPROCESSING;

FACE
    : 'FACE'
    ;

INCLUDE_GUARD
    : 'include_guard'
    ;

STRING_LITERAL
    : QUOTE (~["\\] | '\\' .)* QUOTE
    ;

STRING_LITERAL_2
    : LEFT_ANG_BRACKET (~[<>\\] | '\\' .)* RIGHT_ANG_BRACKET
    ;

INCLUDE
    : 'include'
    ;

DEFINE
    :  'define'
    ;

IFNDEF
    :  'ifndef'
    ;

ENDIF
    :  'endif'
    ;

PRAGMA
    :  'pragma'
    ;

LEFT_ANG_BRACKET
    : '<'
    ;

RIGHT_ANG_BRACKET
    : '>'
    ;

QUOTE
    : '"'
    ;

COMMA
    : ','
    ;

IDENTIFIER
    : [a-zA-Z_] [a-zA-Z_0-9]*
    ;

NEWLINE
    : FRAG_NEWLINE -> mode(DEFAULT_MODE)
    ;

WS_
    : (' ' | '\r' | '\t' | '\u000C') -> channel (HIDDEN)
    ;

LINE_COMMENT
    : '//' -> pushMode(LINE_COMMENT_MODE), channel(HIDDEN)
    ;

//COMMENT
//    : '/*' -> pushMode(COMMENT_MODE), channel(HIDDEN)
//    ;

mode LINE_COMMENT_MODE;

LINE_COMMENTED_TEXT
    : ~('\r'|'\n') ->channel(HIDDEN)
    ;

COMMENTED_LINE
    : FRAG_NEWLINE -> popMode, more
    ;

//mode COMMENT_MODE;
//
//NEWLINE_IN_COMMENT
//    : FRAG_NEWLINE  {setType(NEWLINE);}
//    ;
//
//COMMENTED_TEXT
//    : ~('\r'|'\n' | '*' | '\\') ->channel(HIDDEN)
//    ;
//
//END_COMMENT
//    : '*/'  -> popMode
//    ;
