# Step 9 - Lexer

Package: `dev.frierendb.engine.sql`. Classes: `Lexer` (package-private), `SqlException`.

## Goal

Turn SQL text into a list of tokens, each with its position, so the parser works on tokens and can report errors precisely.

## Concepts

- **Token**: the smallest meaningful unit (a word, a number, a string literal, a symbol).
- **Lexing vs parsing**: the lexer knows spelling; the parser knows structure. Keeping them apart keeps both small.

## Design

```java
enum Kind { WORD, QUOTED_WORD, NUMBER, STRING, SYMBOL, EOF }
record Token(Kind kind, String text, int position) {}
static List<Token> tokenize(String input)
```

| Kind | Source text | `text` holds |
|---|---|---|
| `WORD` | letters, digits, `_`, starting with a letter or `_` | the word as written (keywords are decided by the parser, not here) |
| `QUOTED_WORD` | `"identifier"`, `""` is an escaped quote | contents without the quotes |
| `NUMBER` | `123`, `1.5`, `.5`, `1e10`, `2.5E-3` | the literal text |
| `STRING` | `'text'`, `''` is an escaped quote | contents without the quotes, quotes unescaped |
| `SYMBOL` | `( ) , . * + - / % = < > ;` and two-character `<= >= != <>` | the symbol |
| `EOF` | end of input | empty; always the last token |

Other rules:

- Whitespace is skipped; `-- comment` skips to end of line.
- A number may not start with a sign (the parser handles unary minus).
- Exponent: `1e5` is a number; `1e` (no digits after `e`) is the number `1` followed by the word `e`.
- Unterminated string or quoted identifier → `SqlException` with the starting position.
- Any other character → `SqlException("Unexpected character 'c' at position n")`.
- Keywords are **not** distinguished here; the parser compares words case-insensitively.

## Build it

1. `SqlException extends FrierenException` (with `serialVersionUID`).
2. Main loop: skip whitespace/comments; at end add `EOF`; dispatch on the first character:

```java
if (isWordStart(c)) tokens.add(word());
else if (Character.isDigit(c) || (c == '.' && next is digit)) tokens.add(number());
else if (c == '\'') tokens.add(quoted('\'', Kind.STRING));
else if (c == '"') tokens.add(quoted('"', Kind.QUOTED_WORD));
else tokens.add(symbol());
```

3. `quoted(quote, kind)`: scan to the closing quote; if the next character is the same quote, append one quote and skip both (SQL escaping). End of input → exception.
4. `symbol()`: check for two-character operators first.
5. Positions are 0-based character offsets in the input, used in error messages as `at position N`.

## Pitfalls

- `--` is a comment, so `x --5` is `x` followed by a comment, not `x - -5`. A test that builds a long run of minus signs must separate them with spaces.
- `.5` is a number but `a.b` is a column reference: only treat `.` as a number start when a digit follows **and** we are at a token start.
- Do not decide keywords in the lexer; `"select"` quoted is an identifier, `select` unquoted is a keyword, and only the parser knows the difference.
- Use `Character.isLetter` so Unicode identifiers work.

## Tests to write

Covered inside `ParserTest`: string escapes and comments (`parsesStringEscapesAndComments`), quoted identifiers that are reserved words, and `rejectsUnterminatedStringsAndStrangeCharacters`. If you add a dedicated `LexerTest`, cover: numbers with exponents, `1e` edge case, `<>` vs `<`, adjacent symbols (`a<=b`), and position values.

## Checkpoint

`Lexer.tokenize("SELECT a, 'it''s' FROM t -- x")` yields: WORD, WORD, SYMBOL `,`, STRING `it's`, WORD, WORD, EOF.
