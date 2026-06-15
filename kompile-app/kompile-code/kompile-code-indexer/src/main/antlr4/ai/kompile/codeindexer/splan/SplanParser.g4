/*
 * Splan Parser Grammar
 *
 * Semantic Plan (splan) v0.1.0 — a language-neutral grammar for expressing
 * batched code operations with content blocks.
 *
 * A plan is a flat sequence of:
 *   - operations   (command + arguments)
 *   - declarations  (named content blocks, scoped to sections)
 *   - sections      (--- separators that partition declaration namespaces)
 *   - comments      (# text, preserved for round-tripping)
 *   - blank lines   (ignored)
 *
 * All operations form a single atomic transaction regardless of sections.
 */
parser grammar SplanParser;

options { tokenVocab = SplanLexer; }

// ─── TOP-LEVEL ──────────────────────────────────────────────────────────

plan
    : planItem* EOF
    ;

planItem
    : operation
    | declaration
    | sectionSeparator
    | comment
    | blankLine
    ;

// ─── OPERATIONS ─────────────────────────────────────────────────────────
// command followed by zero or more arguments, terminated by newline

operation
    : command argument* NL
    ;

command
    : COMMAND
    ;

argument
    : contentBlock       // inline ::: content :::
    | declarationRef     // :name reference
    | token              // opaque token (semantic path, keyword, etc.)
    ;

// ─── TOKENS ─────────────────────────────────────────────────────────────

token
    : TOKEN
    | COMMAND   // a command-like word in argument position is a token
    ;

// ─── DECLARATION REFERENCES ─────────────────────────────────────────────

declarationRef
    : DECL_REF
    ;

// ─── CONTENT BLOCKS ─────────────────────────────────────────────────────
// Delimited content with matched openers/closers.
// Supports ::: ### $$$ @@@ %%% as delimiter characters.

contentBlock
    : DELIM_COLON   contentBody_colon   CLOSE_COLON
    | DELIM_HASH    contentBody_hash    CLOSE_HASH
    | DELIM_DOLLAR  contentBody_dollar  CLOSE_DOLLAR
    | DELIM_AT      contentBody_at      CLOSE_AT
    | DELIM_PERCENT contentBody_percent CLOSE_PERCENT
    ;

contentBody_colon   : CONTENT_COLON_TEXT?   ;
contentBody_hash    : CONTENT_HASH_TEXT?    ;
contentBody_dollar  : CONTENT_DOLLAR_TEXT?  ;
contentBody_at      : CONTENT_AT_TEXT?      ;
contentBody_percent : CONTENT_PERCENT_TEXT? ;

// ─── DECLARATIONS ───────────────────────────────────────────────────────
// :name::: content ::: — binds a name to a content block.
// The name and delimiter are adjacent (no whitespace between).

declaration
    : DECL_REF contentBlock NL
    ;

// ─── SECTIONS ───────────────────────────────────────────────────────────

sectionSeparator
    : SECTION_SEP
    ;

// ─── COMMENTS & BLANKS ─────────────────────────────────────────────────

comment
    : COMMENT
    ;

blankLine
    : BLANK_LINE
    ;
