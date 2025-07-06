/*
[The "BSD licence"]
Copyright (c) 2014 AutoTest Technologies, LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
3. The name of the author may not be used to endorse or promote products
derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/** CORBA IDL v3.5 grammar built from the OMG IDL language spec 'ptc-13-02-02'
    http://www.omg.org/spec/IDL35/Beta1/PDF/

    Initial IDL spec implementation in ANTLR v3 by Dong Nguyen.
    Migrated to ANTLR v4 by Steve Osselton.
    Current revision prepared by Nikita Visnevski.
    Renaming of COMA to COMMA and addition of OCTAL_LITERAL in `literal`
    by Oliver Kellogg.
    Support for IDL4 annotation applications, integration of eProsima IDL4
    updates by Oliver Kellogg.
*/

// $antlr-format alignTrailingComments true, columnLimit 150, minEmptyLines 1, maxEmptyLinesToKeep 1, reflowComments false, useTab false
// $antlr-format allowShortRulesOnASingleLine false, allowShortBlocksOnASingleLine true, alignSemicolons hanging, alignColons hanging

grammar FACE_IDL;

@lexer::members {
    public static final int PREPROCESSOR_CHANNEL = 1;
}

specification : definition* EOF;

definition
    : module SEMICOLON
      | const_dcl SEMICOLON
      | type_dcl SEMICOLON
      | except_dcl SEMICOLON
      | interface_or_forward_dcl SEMICOLON
      | template_module_dcl SEMICOLON
      | template_module_inst SEMICOLON
    ;

//
// Building Block Template Modules
//
 template_module_dcl
    : KW_MODULE identifier LEFT_ANG_BRACKET formal_parameters RIGHT_ANG_BRACKET LEFT_BRACE tpl_definition + RIGHT_BRACE
    ;

 formal_parameters
    : formal_parameter (COMMA formal_parameter)*
    ;

 formal_parameter
    : formal_parameter_type identifier
    ;

 formal_parameter_type
    : KW_TYPENAME | KW_INTERFACE | KW_VALUETYPE
    | KW_STRUCT | KW_UNION | KW_EXCEPTION
    | KW_ENUM | KW_SEQUENCE
    | KW_CONST const_type
    | sequence_type
    ;

 tpl_definition
    : definition | template_module_ref SEMICOLON
    ;

 template_module_inst
    : KW_MODULE scoped_name LEFT_ANG_BRACKET actual_parameters RIGHT_ANG_BRACKET identifier
    ;

 actual_parameters
    : actual_parameter ( COMMA actual_parameter)*
    ;

 actual_parameter
    :type_spec | const_expr
     ;

template_module_ref
    : KW_ALIAS scoped_name LEFT_ANG_BRACKET formal_parameter_names RIGHT_ANG_BRACKET identifier
    ;

 formal_parameter_names
    : identifier  (COMMA identifier)*
    ;


///
// Building Block Core Data Types
// Building Block Interfaces – Basic
// Building Block Interfaces – Full
//
module
    : KW_MODULE identifier LEFT_BRACE definition+ RIGHT_BRACE
    ;

interface_or_forward_dcl
    : (interface_dcl | forward_dcl)
    ;

interface_dcl
    : interface_header LEFT_BRACE interface_body RIGHT_BRACE
    ;

forward_dcl
    : (KW_ABSTRACT | KW_LOCAL)? KW_INTERFACE identifier
    ;

interface_header
    : (KW_ABSTRACT | KW_LOCAL)? KW_INTERFACE identifier (interface_inheritance_spec)?
    ;

interface_body
    : export_*
    ;

export_
    : (
        op_dcl SEMICOLON
        | attr_dcl SEMICOLON
        | type_dcl SEMICOLON
        | const_dcl SEMICOLON
        | except_dcl SEMICOLON
    )
    ;

interface_inheritance_spec
    : COLON interface_name (COMMA interface_name)*
    ;

interface_name
    : a_scoped_name
    ;

// scoped_name with optional prefixed annotations
a_scoped_name
    : scoped_name
    ;

scoped_name
    : (DOUBLE_COLON)? ID (DOUBLE_COLON ID)*
    ;

const_dcl
    : KW_CONST const_type identifier EQUAL const_expr
     ;

const_type
    : (
        integer_type
        | char_type
        | wide_char_type
        | boolean_type
        | floating_pt_type
        | string_type
        | wide_string_type
        | fixed_pt_const_type
        | scoped_name
        | octet_type
    )
    ;

const_expr
     : or_expr
    ;

or_expr
    : xor_expr (PIPE xor_expr)*
    ;

xor_expr
    : and_expr (CARET and_expr)*
    ;

and_expr
    : shift_expr (AMPERSAND shift_expr)*
    ;

shift_expr
    : add_expr ((RIGHT_SHIFT | LEFT_SHIFT) add_expr)*
    ;

add_expr
    : mult_expr ((PLUS | MINUS) mult_expr)*
    ;

mult_expr
    : unary_expr ((STAR | SLASH | PERCENT) unary_expr)*
    ;

unary_expr
    : unary_operator primary_expr
    | primary_expr
    ;

unary_operator
    : (MINUS | PLUS | TILDE)
    ;

primary_expr
    : scoped_name
    | literal
    | LEFT_BRACKET const_expr RIGHT_BRACKET
    ;

literal
    : (
        HEX_LITERAL
        | INTEGER_LITERAL
        | OCTAL_LITERAL
        | STRING_LITERAL
        | WIDE_STRING_LITERAL
        | CHARACTER_LITERAL
        | WIDE_CHARACTER_LITERAL
        | FIXED_PT_LITERAL
        | FLOATING_PT_LITERAL
        | BOOLEAN_LITERAL
    )
    ;

positive_int_const
    : const_expr
     ;

type_dcl
    : KW_TYPEDEF type_dclarator
    | struct_type
    | union_type
    | enum_type
    | bitset_type
    | bitmask_type
    | KW_NATIVE simple_dclarator
    | constr_forward_dcl
    ;

type_dclarator
    : type_spec declarators
    ;

type_spec
    : simple_type_spec
    | constr_type_spec
    ;

simple_type_spec
    : base_type_spec
    | template_type_spec
    | scoped_name
    ;

bitfield_type_spec
    : integer_type
    | boolean_type
    | octet_type
    ;

base_type_spec
    : floating_pt_type
    | integer_type
    | char_type
    | wide_char_type
    | boolean_type
    | octet_type
    | any_type
    | object_type
    | value_base_type
    ;

template_type_spec
    : sequence_type
    | set_type
    | map_type
    | string_type
    | wide_string_type
    | fixed_pt_type
    ;

constr_type_spec
    : struct_type
    | union_type
    | enum_type
    | bitset_type
    | bitmask_type
    ;

simple_dclarators
    : identifier (COMMA identifier)*
    ;

declarators
    : declarator (COMMA declarator)*
    ;

declarator
    : (simple_dclarator | complex_dclarator)
    ;

simple_dclarator
    : ID
    ;

complex_dclarator
    : array_dclarator
    ;

floating_pt_type
    : (KW_FLOAT | KW_DOUBLE | KW_LONG KW_DOUBLE)
    ;

integer_type
    : signed_int
    | unsigned_int
    ;

signed_int
    : signed_short_int
    | signed_long_int
    | signed_longlong_int
    | signed_tiny_int
    ;

signed_tiny_int
    : KW_INT8
    ;

signed_short_int
    : KW_SHORT
    | KW_INT16
    ;

signed_long_int
    : KW_LONG
    | KW_INT32
    ;

signed_longlong_int
    : KW_LONG KW_LONG
    | KW_INT64
    ;

unsigned_int
    : unsigned_short_int
    | unsigned_long_int
    | unsigned_longlong_int
    | unsigned_tiny_int
    ;

unsigned_tiny_int
    : KW_UINT8
    ;

unsigned_short_int
    : KW_UNSIGNED KW_SHORT
    | KW_UINT16
    ;

unsigned_long_int
    : KW_UNSIGNED KW_LONG
    | KW_UINT32
    ;

unsigned_longlong_int
    : KW_UNSIGNED KW_LONG KW_LONG
    | KW_UINT64
    ;

char_type
    : KW_CHAR
    ;

wide_char_type
    : KW_WCHAR
    ;

boolean_type
    : KW_BOOLEAN
    ;

octet_type
    : KW_OCTET
    ;

any_type
    : KW_ANY
    ;

object_type
    : KW_OBJECT
    ;


bitset_type
    : KW_BITSET identifier (COLON scoped_name)? LEFT_BRACE bitfield RIGHT_BRACE
    ;

bitfield
    : (bitfield_spec (simple_dclarators)? SEMICOLON)+
    ;

bitfield_spec
    : KW_BITFIELD LEFT_ANG_BRACKET positive_int_const (COMMA bitfield_type_spec)? RIGHT_ANG_BRACKET
    ;

bitmask_type
    : KW_BITMASK identifier LEFT_BRACE bit_values RIGHT_BRACE
    ;

bit_values
    : identifier (COMMA identifier)*
    ;

struct_type
    : KW_STRUCT identifier (COLON scoped_name)? LEFT_BRACE member_list RIGHT_BRACE
    ;

member_list
    : member*
    ;

member
    : type_spec declarators SEMICOLON
    ;

union_type
    : KW_UNION identifier KW_SWITCH LEFT_BRACKET switch_type_spec RIGHT_BRACKET LEFT_BRACE switch_body RIGHT_BRACE
    ;

switch_type_spec
    : integer_type
    | char_type
    | wide_char_type
    | octet_type
    | boolean_type
    | enum_type
    | scoped_name
    ;

switch_body
    : case_stmt+
    ;

case_stmt
    : case_label+ element_spec SEMICOLON
    ;

case_label
    : (KW_CASE const_expr COLON | KW_DEFAULT COLON)
    ;

element_spec
    : type_spec declarator
    ;

enum_type
    : KW_ENUM identifier LEFT_BRACE enumerator (COMMA enumerator)* RIGHT_BRACE
    ;

enumerator
    : identifier
    ;

sequence_type
    : KW_SEQUENCE LEFT_ANG_BRACKET simple_type_spec (COMMA positive_int_const)? RIGHT_ANG_BRACKET
    ;

set_type
    : KW_SET LEFT_ANG_BRACKET simple_type_spec (COMMA positive_int_const)? RIGHT_ANG_BRACKET
    ;

map_type
    : KW_MAP LEFT_ANG_BRACKET simple_type_spec COMMA simple_type_spec (COMMA positive_int_const)? RIGHT_ANG_BRACKET
    ;

string_type
    : KW_STRING (LEFT_ANG_BRACKET positive_int_const RIGHT_ANG_BRACKET)?
    ;

wide_string_type
    : KW_WSTRING (LEFT_ANG_BRACKET positive_int_const RIGHT_ANG_BRACKET)?
    ;

array_dclarator
    : ID fixed_array_size+
    ;

fixed_array_size
    : LEFT_SQUARE_BRACKET positive_int_const RIGHT_SQUARE_BRACKET
    ;

attr_dcl
    : readonly_attr_spec
    | attr_spec
    ;

except_dcl
    : KW_EXCEPTION identifier LEFT_BRACE member* RIGHT_BRACE
    ;

op_dcl
    : (op_attribute)? op_type_spec identifier parameter_dcls (raises_expr)? (context_expr)?
    ;

op_attribute
    : KW_ONEWAY
    ;

op_type_spec
    : (param_type_spec | KW_VOID)
    ;

parameter_dcls
    : LEFT_BRACKET (param_dcl (COMMA param_dcl)*)? RIGHT_BRACKET
    ;

param_dcl
    : param_attribute param_type_spec simple_dclarator
    ;

param_attribute
    : KW_IN
    | KW_OUT
    | KW_INOUT
    ;

raises_expr
    : KW_RAISES LEFT_BRACKET a_scoped_name (COMMA a_scoped_name)* RIGHT_BRACKET
    ;

context_expr
    : KW_CONTEXT LEFT_BRACKET STRING_LITERAL (COMMA STRING_LITERAL)* RIGHT_BRACKET
    ;

param_type_spec
    : base_type_spec
    | string_type
    | wide_string_type
    | scoped_name
    ;

fixed_pt_type
    : KW_FIXED LEFT_ANG_BRACKET positive_int_const COMMA positive_int_const RIGHT_ANG_BRACKET
    ;

fixed_pt_const_type
    : KW_FIXED
    ;

value_base_type
    : KW_VALUEBASE
    ;

constr_forward_dcl
    : KW_STRUCT ID
    | KW_UNION ID
    ;

readonly_attr_spec
    : KW_READONLY KW_ATTRIBUTE param_type_spec readonly_attr_dclarator
    ;

readonly_attr_dclarator
    : simple_dclarator (raises_expr | (COMMA simple_dclarator)*)
    ;

attr_spec
    : KW_ATTRIBUTE param_type_spec attr_dclarator
    ;

attr_dclarator
    : simple_dclarator (attr_raises_expr | (COMMA simple_dclarator)*)
    ;

attr_raises_expr
    : get_excep_expr (set_excep_expr)?
    | set_excep_expr
    ;

get_excep_expr
    : KW_GETRAISES exception_list
    ;

set_excep_expr
    : KW_SETRAISES exception_list
    ;

exception_list
    : LEFT_BRACKET a_scoped_name (COMMA a_scoped_name)* RIGHT_BRACKET
    ;



identifier
    : ID
    ;

///
// Lexical tokens
//
INTEGER_LITERAL
    : ('0' | '1' .. '9' '0' .. '9'*) INTEGER_TYPE_SUFFIX?
    ;

OCTAL_LITERAL
    : '0' ('0' .. '7')+ INTEGER_TYPE_SUFFIX?
    ;

HEX_LITERAL
    : '0' ('x' | 'X') HEX_DIGIT+ INTEGER_TYPE_SUFFIX?
    ;

fragment HEX_DIGIT
    : ('0' .. '9' | 'a' .. 'f' | 'A' .. 'F')
    ;

fragment INTEGER_TYPE_SUFFIX
    : ('l' | 'L')
    ;

FLOATING_PT_LITERAL
    : ('0' .. '9')+ '.' ('0' .. '9')* EXPONENT? FLOAT_TYPE_SUFFIX?
    | '.' ('0' .. '9')+ EXPONENT? FLOAT_TYPE_SUFFIX?
    | ('0' .. '9')+ EXPONENT FLOAT_TYPE_SUFFIX?
    | ('0' .. '9')+ EXPONENT? FLOAT_TYPE_SUFFIX
    ;

FIXED_PT_LITERAL
    : FLOATING_PT_LITERAL
    ;

fragment EXPONENT
    : ('e' | 'E') (PLUS | MINUS)? ('0' .. '9')+
    ;

fragment FLOAT_TYPE_SUFFIX
    : ('f' | 'F' | 'd' | 'D')
    ;

WIDE_CHARACTER_LITERAL
    : 'L' CHARACTER_LITERAL
    ;

CHARACTER_LITERAL
    : '\'' (ESCAPE_SEQUENCE | ~ ('\'' | '\\')) '\''
    ;

WIDE_STRING_LITERAL
    : 'L' STRING_LITERAL
    ;

STRING_LITERAL
    : '"' (ESCAPE_SEQUENCE | ~ ('\\' | '"'))* '"'
    ;

BOOLEAN_LITERAL
    : 'TRUE'
    | 'FALSE'
    ;

fragment ESCAPE_SEQUENCE
    : '\\' ('b' | 't' | 'n' | 'f' | 'r' | '"' | '\'' | '\\')
    | UNICODE_ESCAPE
    | OCTAL_ESCAPE
    ;

fragment OCTAL_ESCAPE
    : '\\' ('0' .. '3') ('0' .. '7') ('0' .. '7')
    | '\\' ('0' .. '7') ('0' .. '7')
    | '\\' ('0' .. '7')
    ;

fragment UNICODE_ESCAPE
    : '\\' 'u' HEX_DIGIT HEX_DIGIT HEX_DIGIT HEX_DIGIT
    ;

fragment LETTER
    : '\u0024'
    | '\u0041' .. '\u005a'
    | '\u005f'
    | '\u0061' .. '\u007a'
    | '\u00c0' .. '\u00d6'
    | '\u00d8' .. '\u00f6'
    | '\u00f8' .. '\u00ff'
    | '\u0100' .. '\u1fff'
    | '\u3040' .. '\u318f'
    | '\u3300' .. '\u337f'
    | '\u3400' .. '\u3d2d'
    | '\u4e00' .. '\u9fff'
    | '\uf900' .. '\ufaff'
    ;

fragment ID_DIGIT
    : '\u0030' .. '\u0039'
    | '\u0660' .. '\u0669'
    | '\u06f0' .. '\u06f9'
    | '\u0966' .. '\u096f'
    | '\u09e6' .. '\u09ef'
    | '\u0a66' .. '\u0a6f'
    | '\u0ae6' .. '\u0aef'
    | '\u0b66' .. '\u0b6f'
    | '\u0be7' .. '\u0bef'
    | '\u0c66' .. '\u0c6f'
    | '\u0ce6' .. '\u0cef'
    | '\u0d66' .. '\u0d6f'
    | '\u0e50' .. '\u0e59'
    | '\u0ed0' .. '\u0ed9'
    | '\u1040' .. '\u1049'
    ;

SEMICOLON
    : ';'
    ;

COLON
    : ':'
    ;

COMMA
    : ','
    ;

LEFT_BRACE
    : '{'
    ;

RIGHT_BRACE
    : '}'
    ;

LEFT_BRACKET
    : '('
    ;

RIGHT_BRACKET
    : ')'
    ;

LEFT_SQUARE_BRACKET
    : '['
    ;

RIGHT_SQUARE_BRACKET
    : ']'
    ;

TILDE
    : '~'
    ;

SLASH
    : '/'
    ;

LEFT_ANG_BRACKET
    : '<'
    ;

RIGHT_ANG_BRACKET
    : '>'
    ;

STAR
    : '*'
    ;

PLUS
    : '+'
    ;

MINUS
    : '-'
    ;

CARET
    : '^'
    ;

AMPERSAND
    : '&'
    ;

PIPE
    : '|'
    ;

EQUAL
    : '='
    ;

PERCENT
    : '%'
    ;

DOUBLE_COLON
    : '::'
    ;

RIGHT_SHIFT
    : '>>'
    ;

LEFT_SHIFT
    : '<<'
    ;

AT
    : '@'
    ;

KW_ALIAS
    : 'alias'
    ;

KW_SETRAISES
    : 'setraises'
    ;

KW_OUT
    : 'out'
    ;

KW_EMITS
    : 'emits'
    ;

KW_STRING
    : 'string'
    ;

KW_SWITCH
    : 'switch'
    ;

KW_PUBLISHES
    : 'publishes'
    ;

KW_TYPEDEF
    : 'typedef'
    ;

KW_TYPENAME
    : 'typename'
    ;

KW_USES
    : 'uses'
    ;

KW_PRIMARYKEY
    : 'primarykey'
    ;

KW_CUSTOM
    : 'custom'
    ;

KW_OCTET
    : 'octet'
    ;

KW_SEQUENCE
    : 'sequence'
    ;

KW_IMPORT
    : 'import'
    ;

PREPROCESS_INCLUDE
    : '\n#include'
    ;

PREPROCESS_DEFINE
    : '\n#define'
    ;

PREPROCESS_IFNDEF
    :'\n#ifndef'
    ;

PREPROCESS_IFDEF
    :'\n#ifdef'
    ;

PREPROCESS_ENDIF
    :'\n#endif'
    ;

PREPROCESS_PRAGMA
    :'\n#pragma'
    ;

KW_STRUCT
    : 'struct'
    ;

KW_NATIVE
    : 'native'
    ;

KW_READONLY
    : 'readonly'
    ;

KW_FINDER
    : 'finder'
    ;

KW_RAISES
    : 'raises'
    ;

KW_VOID
    : 'void'
    ;

KW_PRIVATE
    : 'private'
    ;

KW_EVENTTYPE
    : 'eventtype'
    ;

KW_WCHAR
    : 'wchar'
    ;

KW_IN
    : 'in'
    ;

KW_DEFAULT
    : 'default'
    ;

KW_PUBLIC
    : 'public'
    ;

KW_SHORT
    : 'short'
    ;

KW_LONG
    : 'long'
    ;

KW_ENUM
    : 'enum'
    ;

KW_WSTRING
    : 'wstring'
    ;

KW_CONTEXT
    : 'context'
    ;

KW_HOME
    : 'home'
    ;

KW_FACTORY
    : 'factory'
    ;

KW_EXCEPTION
    : 'exception'
    ;

KW_GETRAISES
    : 'getraises'
    ;

KW_CONST
    : 'const'
    ;

KW_VALUEBASE
    : 'ValueBase'
    ;

KW_VALUETYPE
    : 'valuetype'
    ;

KW_SUPPORTS
    : 'supports'
    ;

KW_MODULE
    : 'module'
    ;

KW_OBJECT
    : 'Object'
    ;

KW_TRUNCATABLE
    : 'truncatable'
    ;

KW_UNSIGNED
    : 'unsigned'
    ;

KW_FIXED
    : 'fixed'
    ;

KW_UNION
    : 'union'
    ;

KW_ONEWAY
    : 'oneway'
    ;

KW_ANY
    : 'any'
    ;

KW_CHAR
    : 'char'
    ;

KW_CASE
    : 'case'
    ;

KW_FLOAT
    : 'float'
    ;

KW_BOOLEAN
    : 'boolean'
    ;

KW_ABSTRACT
    : 'abstract'
    ;

KW_INOUT
    : 'inout'
    ;

KW_DOUBLE
    : 'double'
    ;

KW_ATTRIBUTE
    : 'attribute'
    ;

KW_LOCAL
    : 'local'
    ;

KW_MANAGES
    : 'manages'
    ;

KW_INTERFACE
    : 'interface'
    ;

KW_SET
    : 'set'
    ;

KW_MAP
    : 'map'
    ;

KW_BITFIELD
    : 'bitfield'
    ;

KW_BITSET
    : 'bitset'
    ;

KW_BITMASK
    : 'bitmask'
    ;

KW_INT8
    : 'int8'
    ;

KW_UINT8
    : 'uint8'
    ;

KW_INT16
    : 'int16'
    ;

KW_UINT16
    : 'uint16'
    ;

KW_INT32
    : 'int32'
    ;

KW_UINT32
    : 'uint32'
    ;

KW_INT64
    : 'int64'
    ;

KW_UINT64
    : 'uint64'
    ;

KW_AT_ANNOTATION
    : '@annotation'
    ;

ID
    : LETTER (LETTER | ID_DIGIT)*
    ;

WS
    : (' ' | '\r' | '\t' | '\u000C' | '\n') -> channel (HIDDEN)
    ;

COMMENT
    : '/*' .*? '*/' -> channel (HIDDEN)
    ;

LINE_COMMENT
    : '//' ~ ('\n' | '\r')* '\r'? '\n' -> channel (HIDDEN)
    ;

PREPROCESSOR
    : '#' .*? '\n' -> channel (1)
    ;
