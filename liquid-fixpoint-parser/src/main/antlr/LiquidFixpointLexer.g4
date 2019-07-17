lexer grammar LiquidFixpointLexer;

@header {
    package org.jetbrains.research.fixpoint;
}


SOLUTION_KEYWORD: 'Solution:';

QUERY_QUALIFIER: 'qualif';
QUERY_EXPAND: 'expand';
QUERY_CONSTANT: 'constant';
QUERY_BIND: 'bind';
QUERY_CONSTRAINT:'constraint:';
QUERY_WF:'wf:';
QUERY_CONSTANT_FUNC : 'func';

CONSTRAINT_ENV: 'env';
CONSTRAINT_LHS: 'lhs';
CONSTRAINT_RHS: 'rhs';
CONSTRAINT_ID: 'id';
CONSTRAINT_TAG: 'tag';
CONSTRAINT_REFT: 'reft';

SPACE:              [ \t\r\n]+    -> channel(HIDDEN);
LINE_COMMENT:       '//' ~[\r\n]* -> channel(HIDDEN);
DECIMAL:             DEC_DIGIT+;
IDENTIFIER:         ( LETTER | DOLLAR | SHARP | UNDERLINE | DOT | FullWidthLetter | DEC_DIGIT )+;

ASSIGMENT:      ':=';
EQUALITY:       '~~';
NONEQUALITY:    '!=';
AND_AND:        '&&';
OR_OR:          '||';
GTE:            '>=';
LTE:            '<=';

PREDICATE_EQUALITY:        '<=>';

EQUAL:               '=';

GREATER:             '>';
LESS:                '<';
EXCLAMATION:         '!';

PLUS_ASSIGN:         '+=';
MINUS_ASSIGN:        '-=';
MULT_ASSIGN:         '*=';
DIV_ASSIGN:          '/=';
MOD_ASSIGN:          '%=';
AND_ASSIGN:          '&=';
XOR_ASSIGN:          '^=';
OR_ASSIGN:           '|=';

DOT:                 '.';
UNDERLINE:           '_';
AT:                  '@';
SHARP:               '#';
DOLLAR:              '$';
LR_BRACKET:          '(';
RR_BRACKET:          ')';
LS_BRACKET:  '[' ;
RS_BRACKET:  ']' ;
LF_BRACKET:  '{' ;
RF_BRACKET:  '}' ;
COMMA:               ',';
SEMI:                ';';
COLON:               ':';
STAR:                '*';
DIVIDE:              '/';
MODULE:              '%';
PLUS:                '+';
MINUS:               '-';
BIT_NOT:             '~';
BIT_OR:              '|';
BIT_AND:             '&';
BIT_XOR:             '^';

LETTER : (LETTER_UP | LETTER_LOW);

fragment DEC_DIGIT:    [0-9];
fragment LETTER_UP:       [A-Z];
fragment LETTER_LOW:       [a-z];

fragment FullWidthLetter
    : '\u00c0'..'\u00d6'
    | '\u00d8'..'\u00f6'
    | '\u00f8'..'\u00ff'
    | '\u0100'..'\u1fff'
    | '\u2c00'..'\u2fff'
    | '\u3040'..'\u318f'
    | '\u3300'..'\u337f'
    | '\u3400'..'\u3fff'
    | '\u4e00'..'\u9fff'
    | '\ua000'..'\ud7ff'
    | '\uf900'..'\ufaff'
    | '\uff00'..'\ufff0'
    // | '\u10000'..'\u1F9FF'  //not support four bytes chars
    // | '\u20000'..'\u2FA1F'
    ;
