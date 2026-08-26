package dev.frierendb.engine.sql;

import java.util.ArrayList;
import java.util.List;

final class Lexer {

    enum Kind { WORD, QUOTED_WORD, NUMBER, STRING, SYMBOL, EOF }

    record Token(Kind kind, String text, int position) {}

    private final String input;
    private int pos;

    private Lexer(String input) {
        this.input = input;
    }

    static List<Token> tokenize(String input) {
        return new Lexer(input).run();
    }

    private List<Token> run() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= input.length()) {
                tokens.add(new Token(Kind.EOF, "", pos));
                return tokens;
            }
            char c = input.charAt(pos);
            if (isWordStart(c)) {
                tokens.add(word());
            } else if (Character.isDigit(c) || (c == '.' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                tokens.add(number());
            } else if (c == '\'') {
                tokens.add(quoted('\'', Kind.STRING));
            } else if (c == '"') {
                tokens.add(quoted('"', Kind.QUOTED_WORD));
            } else {
                tokens.add(symbol());
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private Token word() {
        int start = pos;
        while (pos < input.length() && isWordPart(input.charAt(pos))) {
            pos++;
        }
        return new Token(Kind.WORD, input.substring(start, pos), start);
    }

    private Token number() {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            int exponentStart = pos;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                pos++;
            }
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                pos = exponentStart;
            } else {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
        }
        return new Token(Kind.NUMBER, input.substring(start, pos), start);
    }

    private Token quoted(char quote, Kind kind) {
        int start = pos;
        pos++;
        StringBuilder text = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == quote) {
                if (pos + 1 < input.length() && input.charAt(pos + 1) == quote) {
                    text.append(quote);
                    pos += 2;
                    continue;
                }
                pos++;
                return new Token(kind, text.toString(), start);
            }
            text.append(c);
            pos++;
        }
        throw new SqlException("Unterminated " + (kind == Kind.STRING ? "string literal" : "quoted identifier")
                + " starting at position " + start);
    }

    private Token symbol() {
        int start = pos;
        char c = input.charAt(pos);
        String two = pos + 1 < input.length() ? input.substring(pos, pos + 2) : "";
        if (two.equals("<=") || two.equals(">=") || two.equals("!=") || two.equals("<>")) {
            pos += 2;
            return new Token(Kind.SYMBOL, two, start);
        }
        if ("(),.*+-/%=<>;".indexOf(c) >= 0) {
            pos++;
            return new Token(Kind.SYMBOL, String.valueOf(c), start);
        }
        throw new SqlException("Unexpected character '" + c + "' at position " + start);
    }

    private static boolean isWordStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
