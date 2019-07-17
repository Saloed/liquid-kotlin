parser grammar LiquidFixpointParser;

@header {
    package org.jetbrains.research.fixpoint;
}

options { tokenVocab = LiquidFixpointLexer; }

file
   : solution_file EOF
   | query_file EOF
   ;

solution_file
   : SOLUTION_KEYWORD solution_statement*
   ;

solution_statement
    : IDENTIFIER ASSIGMENT solution_clauses
    ;

solution_clauses
    : (expression AND_AND?)+
    ;


clause_elements_combinator
    : AND_AND
    | GTE
    | LTE
    | EQUALITY
    | NONEQUALITY
    | EQUAL
    | GREATER
    | LESS
    | BIT_NOT
    | PLUS
    | MINUS
    | PREDICATE_EQUALITY
    | DIV_ASSIGN
    ;


query_file
    : query_statement*
    ;

query_statement
    : expand_statement
    | qualifier_statement
    | constant_statement
    | bind_statement
    | constraint_statement
    | wf_statement
    ;

expand_statement
    : QUERY_EXPAND LS_BRACKET RS_BRACKET
    ;

qualifier_statement
    : QUERY_QUALIFIER IDENTIFIER LR_BRACKET qualifier_arguments RR_BRACKET COLON  expression
    ;

qualifier_arguments
    : (qualifier_argument COMMA?)+
    ;
qualifier_argument
    : identifier_with_type
    | IDENTIFIER COLON at_decimal
    ;

qualifier_clause_element
    : IDENTIFIER
    | DECIMAL
    ;

expression
    : LR_BRACKET+ expression clause_elements_combinator expression  RR_BRACKET+
    | LR_BRACKET+ clause_elements_combinator expression RR_BRACKET+
    | LR_BRACKET+ IDENTIFIER+ RR_BRACKET+
    | LR_BRACKET* IDENTIFIER RR_BRACKET*
    | LR_BRACKET* DECIMAL RR_BRACKET*
    | IDENTIFIER clause_elements_combinator expression
    ;


constant_statement
    : QUERY_CONSTANT IDENTIFIER COLON LR_BRACKET identifier_or_func RR_BRACKET
    ;

identifier_or_func
    : IDENTIFIER
    | LR_BRACKET+ IDENTIFIER+ RR_BRACKET+
    | QUERY_CONSTANT_FUNC LR_BRACKET func_statement RR_BRACKET
    ;

func_statement
    : DECIMAL COMMA LS_BRACKET func_statement_args RS_BRACKET
    ;

func_statement_args
    : (func_statement_arg SEMI?)+
    ;
func_statement_arg
    : at_decimal
    | IDENTIFIER
    | LR_BRACKET IDENTIFIER at_decimal RR_BRACKET
    ;
bind_statement
    : QUERY_BIND DECIMAL identifier_or_func COLON bind_constraint
    ;

bind_constraint
    : LF_BRACKET IDENTIFIER COLON identifier_or_func BIT_OR bind_predicates RF_BRACKET
    ;

bind_predicates
    : LS_BRACKET   (bind_predicate SEMI?)* RS_BRACKET
    ;

bind_predicate
    : expression
    | IDENTIFIER substitution+
    ;

substitution
    : LS_BRACKET IDENTIFIER ASSIGMENT IDENTIFIER RS_BRACKET
    ;

constraint_statement
    : QUERY_CONSTRAINT constraint_statement_env constraint_statement_lhs constraint_statement_rhs constraint_statement_id
    ;

constraint_statement_env
    : CONSTRAINT_ENV LS_BRACKET   (DECIMAL SEMI?)* RS_BRACKET
    ;

constraint_statement_lhs
    : CONSTRAINT_LHS bind_constraint
    ;

constraint_statement_rhs
    : CONSTRAINT_RHS bind_constraint
    ;

constraint_statement_id
    : CONSTRAINT_ID DECIMAL CONSTRAINT_TAG LS_BRACKET DECIMAL RS_BRACKET
    ;


constraint_statement_reft
    : CONSTRAINT_REFT bind_constraint
    ;

wf_statement
    : QUERY_WF constraint_statement_env constraint_statement_reft
    ;

at_decimal
    : AT LR_BRACKET DECIMAL RR_BRACKET
    ;

identifier_with_type
    : IDENTIFIER COLON IDENTIFIER
    ;
