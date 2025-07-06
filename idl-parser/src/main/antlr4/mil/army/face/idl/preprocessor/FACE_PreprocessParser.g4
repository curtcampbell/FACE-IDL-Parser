parser grammar FACE_PreprocessParser;


//@parser::header {
//package mil.army.face.idl.preprocessor;
//}

options { tokenVocab=FACE_PreprocessLexer; }

// Preprocessor directives

source
    : (preprocessorDirective | soureceLine)*
    ;

soureceLine
    : LINE_TEXT
    ;

preprocessorDirective
    : PREPROCESS_IND (includeDirective
                         | defineDirective
                         | ifnDefDirective
                         | endifDirective
                         | pragmaDirective
                         ) NEWLINE// includeDirective
    ;


// Include directive
includeDirective
    : INCLUDE includeArgs
    ;

includeArgs
    : STRING_LITERAL | STRING_LITERAL_2
    ;

// Define directive
defineDirective
    : DEFINE definedVariable
    ;

definedVariable
    : IDENTIFIER
    ;

// ifndef directive
ifnDefDirective
    : IFNDEF IDENTIFIER
    ;

endifDirective
    : ENDIF
    ;

// Pragma directive
pragmaDirective
    : PRAGMA pragmaBody
    ;

pragmaBody
    : FACE INCLUDE_GUARD
    ;
    
identifier
    : IDENTIFIER
    ;

//// Tokens
//
//FACE
//    : 'FACE'
//    ;
//
//INCLUDE_GUARD
//    : 'include_guard'
//    ;
//
//STRING_LITERAL
//    : QUOTE (~["\\] | '\\' .)* QUOTE
//    ;
//
//STRING_LITERAL_2
//    : LEFT_ANG_BRACKET (~["\\] | '\\' .)* RIGHT_ANG_BRACKET
//    ;
//
//INCLUDE
//    : 'include'
//    ;
//
//DEFINE
//    :  'define'
//    ;
//
//IFNDEF
//    :  'ifndef'
//    ;
//
//ENDIF
//    :  'endif'
//    ;
//
//PRAGMA
//    :  'pragma'
//    ;
//
////TEXT
////    : ~['\r\n']+
////    ;
//
//LEFT_ANG_BRACKET
//    : '<'
//    ;
//
//RIGHT_ANG_BRACKET
//    : '>'
//    ;
//
//QUOTE
//    : '"'
//    ;
//
//COMMA
//    : ','
//    ;
//
//PREPROCESS_IND
//    : '#'
//    ;
//
//NEWLINE
//    : [\n]
//    ;
//
//IDENTIFIER
//    : [a-zA-Z_] [a-zA-Z_0-9]*
//    ;
//
////First on a new line but ignores white spaces.
//
//
//WS
//    : (' ' | '\r' | '\t' | '\u000C') -> channel (HIDDEN)
//    ;
