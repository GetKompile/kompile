/*
 * Splan Lexer Grammar
 *
 * Semantic Plan (splan) v0.1.0 — a language-neutral grammar for expressing
 * batched code operations with content blocks.
 *
 * This lexer uses ANTLR4 modes to handle context-sensitive content blocks:
 *   - DEFAULT_MODE: line-oriented classification (comments, sections, operations, declarations)
 *   - CONTENT_MODE: captures arbitrary bytes between matched delimiters
 */
lexer grammar SplanLexer;

// ─── DEFAULT MODE ───────────────────────────────────────────────────────

// Section separator: exactly "---" followed by newline
SECTION_SEP
    : '---' WS_INLINE* NEWLINE
    ;

// Comment: # to end of line
COMMENT
    : '#' ~[\r\n]* NEWLINE
    ;

// Blank line
BLANK_LINE
    : WS_INLINE* NEWLINE
    ;

// Content block delimiters — each pushes into CONTENT mode
DELIM_COLON   : ':::' -> pushMode(CONTENT_COLON) ;
DELIM_HASH    : '###' -> pushMode(CONTENT_HASH) ;
DELIM_DOLLAR  : '$$$' -> pushMode(CONTENT_DOLLAR) ;
DELIM_AT      : '@@@' -> pushMode(CONTENT_AT) ;
DELIM_PERCENT : '%%%' -> pushMode(CONTENT_PERCENT) ;

// Declaration name prefix: :identifier immediately before a delimiter
// The parser distinguishes declarations from refs by line position
DECL_REF
    : ':' IDENT_FRAG
    ;

// Command: starts with a letter, continues with letters/digits/_/-
COMMAND
    : LETTER (LETTER | DIGIT | '_' | '-')*
    ;

// Token: non-whitespace chars not starting with ':'
// Matches semantic paths, keywords, flags, etc.
TOKEN
    : TOKEN_START_CHAR TOKEN_CHAR*
    ;

// Inline whitespace (space/tab) — skip between arguments
WS : WS_INLINE+ -> skip ;

// Newline as explicit token for line termination
NL : NEWLINE ;

// ─── FRAGMENTS ──────────────────────────────────────────────────────────

fragment LETTER      : [a-zA-Z] ;
fragment DIGIT       : [0-9] ;
fragment IDENT_FRAG  : LETTER (LETTER | DIGIT | '_')* ;
fragment WS_INLINE   : [ \t] ;
fragment NEWLINE     : '\r'? '\n' | '\r' ;

// Token chars: anything non-whitespace that doesn't start with ':'
fragment TOKEN_START_CHAR : ~[ \t\r\n:] ;
fragment TOKEN_CHAR       : ~[ \t\r\n] ;

// ─── CONTENT MODES ──────────────────────────────────────────────────────
// One mode per delimiter type so closing delimiter is unambiguous.
// Content is everything between opening and closing delimiter.

mode CONTENT_COLON;
CONTENT_COLON_TEXT  : (~[:]+ | ':' ~':'  | '::' ~':')+ ;
CLOSE_COLON         : ':::' -> popMode ;

mode CONTENT_HASH;
CONTENT_HASH_TEXT   : (~[#]+ | '#' ~'#'  | '##' ~'#')+ ;
CLOSE_HASH          : '###' -> popMode ;

mode CONTENT_DOLLAR;
CONTENT_DOLLAR_TEXT : (~[$]+ | '$' ~'$'  | '$$' ~'$')+ ;
CLOSE_DOLLAR        : '$$$' -> popMode ;

mode CONTENT_AT;
CONTENT_AT_TEXT     : (~[@]+ | '@' ~'@'  | '@@' ~'@')+ ;
CLOSE_AT            : '@@@' -> popMode ;

mode CONTENT_PERCENT;
CONTENT_PERCENT_TEXT : (~[%]+ | '%' ~'%'  | '%%' ~'%')+ ;
CLOSE_PERCENT        : '%%%' -> popMode ;
