package org.json_kula.valem.core.spreadsheet;

import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BinaryOp;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.BoolLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.CellRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.FuncCall;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.NumberLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.Percent;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.RangeRef;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.StringLit;
import org.json_kula.valem.core.spreadsheet.ast.ExcelExpr.UnaryNeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.CROSS_SHEET_REFERENCE;
import static org.json_kula.valem.core.spreadsheet.UnsupportedFormulaException.Reason.MALFORMED_FORMULA;

/**
 * A hand-rolled recursive-descent parser for the bounded Excel-formula grammar v1 supports
 * (excel-to-spec-v1-design.md §5.2) — not POI's internal {@code Ptg} token API, which is
 * evaluator/renderer-oriented, not a general-purpose AST for translating into a different target
 * language (see the design spec §1 for why). Input is the raw string {@code Cell.getCellFormula()}
 * returns (no leading {@code =}).
 *
 * <p>Grammar:
 * <pre>
 * expr        := comparison
 * comparison  := concat (('='|'&lt;&gt;'|'&lt;'|'&gt;'|'&lt;='|'&gt;=') concat)*
 * concat      := additive ('&' additive)*
 * additive    := term (('+'|'-') term)*
 * term        := unary (('*'|'/') unary)*
 * unary       := '-' unary | power
 * power       := postfix ('^' unary)?
 * postfix     := primary '%'?
 * primary     := NUMBER | STRING | TRUE | FALSE | cellRef (':' cellRef)?
 *              | IDENT '(' (expr (',' expr)*)? ')' | '(' expr ')'
 * cellRef     := ('$')? COLLETTERS ('$')? ROWNUM
 * STRING      := '"' ( any-char-except-quote | '""' )* '"'   -- '""' is a literal quote
 * </pre>
 */
public final class ExcelFormulaParser {

    private ExcelFormulaParser() {}

    public static ExcelExpr parse(String formula) {
        List<Token> tokens = tokenize(formula);
        Cursor cursor = new Cursor(tokens, formula);
        ExcelExpr result = parseExpr(cursor);
        cursor.expectEnd();
        return result;
    }

    /** Same as {@link #parse}, but returns empty instead of throwing — for speculative call sites
     *  (e.g. {@code SpreadsheetCompiler}'s summary-cell detection) that must not fail the whole
     *  compile over a formula elsewhere on the sheet that just isn't the pattern they're looking for. */
    public static java.util.Optional<ExcelExpr> parseSafely(String formula) {
        try {
            return java.util.Optional.of(parse(formula));
        } catch (UnsupportedFormulaException e) {
            return java.util.Optional.empty();
        }
    }

    // ── Tokens ──────────────────────────────────────────────────────────────

    private enum TokenType {
        NUMBER, STRING, IDENT, CELLREF, SHEET_REF, SYMBOL, EOF
    }

    private record Token(TokenType type, String text, double number, int col, int row,
                         boolean colAbsolute, boolean rowAbsolute) {
        static Token symbol(String s) { return new Token(TokenType.SYMBOL, s, 0, 0, 0, false, false); }
        static Token ident(String s) { return new Token(TokenType.IDENT, s, 0, 0, 0, false, false); }
        static Token string(String s) { return new Token(TokenType.STRING, s, 0, 0, 0, false, false); }
        static Token number(double v) { return new Token(TokenType.NUMBER, null, v, 0, 0, false, false); }
        static Token cellRef(int col, int row, boolean colAbs, boolean rowAbs) {
            return new Token(TokenType.CELLREF, null, 0, col, row, colAbs, rowAbs);
        }
        static Token eof() { return new Token(TokenType.EOF, null, 0, 0, 0, false, false); }
    }

    private static List<Token> tokenize(String formula) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = formula.length();
        while (i < n) {
            char c = formula.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }

            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(formula.charAt(i + 1)))) {
                int start = i;
                while (i < n && (Character.isDigit(formula.charAt(i)) || formula.charAt(i) == '.')) i++;
                out.add(Token.number(Double.parseDouble(formula.substring(start, i))));
                continue;
            }

            if (c == '!' ) {
                throw new UnsupportedFormulaException(CROSS_SHEET_REFERENCE,
                        "Cross-sheet reference is not supported in v1: '" + formula + "'");
            }

            if (c == '"') {
                int start = i + 1;
                StringBuilder value = new StringBuilder();
                int j = start;
                while (true) {
                    if (j >= n) {
                        throw new UnsupportedFormulaException(MALFORMED_FORMULA,
                                "Unterminated string literal in '" + formula + "'");
                    }
                    char cj = formula.charAt(j);
                    if (cj == '"') {
                        if (j + 1 < n && formula.charAt(j + 1) == '"') { // "" -> literal "
                            value.append('"');
                            j += 2;
                            continue;
                        }
                        break; // closing quote
                    }
                    value.append(cj);
                    j++;
                }
                out.add(Token.string(value.toString()));
                i = j + 1;
                continue;
            }

            if (Character.isLetter(c) || c == '$') {
                int start = i;
                boolean colAbsolute = false;
                if (c == '$') { colAbsolute = true; i++; }
                int letterStart = i;
                while (i < n && Character.isLetter(formula.charAt(i))) i++;
                String letters = formula.substring(letterStart, i);
                if (letters.isEmpty()) {
                    throw new UnsupportedFormulaException(MALFORMED_FORMULA,
                            "Malformed token at position " + start + " in '" + formula + "'");
                }
                boolean rowAbsolute = false;
                int afterLetters = i;
                int dollarBeforeDigits = i;
                if (i < n && formula.charAt(i) == '$') { dollarBeforeDigits = i + 1; }
                int digitsStart = dollarBeforeDigits;
                int j = digitsStart;
                while (j < n && Character.isDigit(formula.charAt(j))) j++;
                boolean looksLikeCellRef = j > digitsStart && isValidColumnLetters(letters)
                        // reject letters immediately followed by '(' from being mistaken for a ref
                        && !(j < n && formula.charAt(j) == '(');
                if (looksLikeCellRef) {
                    if (dollarBeforeDigits > afterLetters) rowAbsolute = true;
                    int rowNum = Integer.parseInt(formula.substring(digitsStart, j));
                    out.add(Token.cellRef(columnLettersToIndex(letters), rowNum - 1, colAbsolute, rowAbsolute));
                    i = j;
                } else {
                    if (colAbsolute) {
                        throw new UnsupportedFormulaException(MALFORMED_FORMULA,
                                "'$' not followed by a valid cell reference in '" + formula + "'");
                    }
                    out.add(Token.ident(letters.toUpperCase(Locale.ROOT)));
                    i = afterLetters;
                }
                continue;
            }

            if (c == '<') {
                if (i + 1 < n && formula.charAt(i + 1) == '=') { out.add(Token.symbol("<=")); i += 2; }
                else if (i + 1 < n && formula.charAt(i + 1) == '>') { out.add(Token.symbol("<>")); i += 2; }
                else { out.add(Token.symbol("<")); i++; }
                continue;
            }
            if (c == '>') {
                if (i + 1 < n && formula.charAt(i + 1) == '=') { out.add(Token.symbol(">=")); i += 2; }
                else { out.add(Token.symbol(">")); i++; }
                continue;
            }
            if ("+-*/^%=(),:&".indexOf(c) >= 0) {
                out.add(Token.symbol(String.valueOf(c)));
                i++;
                continue;
            }

            throw new UnsupportedFormulaException(MALFORMED_FORMULA,
                    "Unrecognized character '" + c + "' at position " + i + " in '" + formula + "'");
        }
        out.add(Token.eof());
        return out;
    }

    private static boolean isValidColumnLetters(String letters) {
        if (letters.isEmpty() || letters.length() > 3) return false;
        for (int i = 0; i < letters.length(); i++) {
            char ch = Character.toUpperCase(letters.charAt(i));
            if (ch < 'A' || ch > 'Z') return false;
        }
        return true;
    }

    /** 0-based column index: "A"→0, "B"→1, …, "Z"→25, "AA"→26, … */
    static int columnLettersToIndex(String letters) {
        int result = 0;
        for (int i = 0; i < letters.length(); i++) {
            result = result * 26 + (Character.toUpperCase(letters.charAt(i)) - 'A' + 1);
        }
        return result - 1;
    }

    // ── Parser ──────────────────────────────────────────────────────────────

    private static final class Cursor {
        final List<Token> tokens;
        final String source;
        int pos = 0;

        Cursor(List<Token> tokens, String source) {
            this.tokens = tokens;
            this.source = source;
        }

        Token peek() { return tokens.get(pos); }

        Token advance() { return tokens.get(pos++); }

        boolean atSymbol(String s) {
            Token t = peek();
            return t.type() == TokenType.SYMBOL && t.text().equals(s);
        }

        void expectSymbol(String s) {
            if (!atSymbol(s)) {
                throw malformed("Expected '" + s + "'");
            }
            advance();
        }

        void expectEnd() {
            if (peek().type() != TokenType.EOF) {
                throw malformed("Unexpected trailing input at '" + peek().text() + "'");
            }
        }

        UnsupportedFormulaException malformed(String why) {
            return new UnsupportedFormulaException(MALFORMED_FORMULA, why + " in formula: '" + source + "'");
        }
    }

    private static ExcelExpr parseExpr(Cursor c) {
        return parseComparison(c);
    }

    private static final List<String> COMPARISON_OPS = List.of("=", "<>", "<=", ">=", "<", ">");

    private static ExcelExpr parseComparison(Cursor c) {
        ExcelExpr left = parseConcat(c);
        while (c.peek().type() == TokenType.SYMBOL && COMPARISON_OPS.contains(c.peek().text())) {
            String op = c.advance().text();
            ExcelExpr right = parseConcat(c);
            left = new BinaryOp(op, left, right);
        }
        return left;
    }

    private static ExcelExpr parseConcat(Cursor c) {
        ExcelExpr left = parseAdditive(c);
        while (c.atSymbol("&")) {
            c.advance();
            ExcelExpr right = parseAdditive(c);
            left = new BinaryOp("&", left, right);
        }
        return left;
    }

    private static ExcelExpr parseAdditive(Cursor c) {
        ExcelExpr left = parseTerm(c);
        while (c.atSymbol("+") || c.atSymbol("-")) {
            String op = c.advance().text();
            ExcelExpr right = parseTerm(c);
            left = new BinaryOp(op, left, right);
        }
        return left;
    }

    private static ExcelExpr parseTerm(Cursor c) {
        ExcelExpr left = parseUnary(c);
        while (c.atSymbol("*") || c.atSymbol("/")) {
            String op = c.advance().text();
            ExcelExpr right = parseUnary(c);
            left = new BinaryOp(op, left, right);
        }
        return left;
    }

    private static ExcelExpr parseUnary(Cursor c) {
        if (c.atSymbol("-")) {
            c.advance();
            return new UnaryNeg(parseUnary(c));
        }
        return parsePower(c);
    }

    private static ExcelExpr parsePower(Cursor c) {
        ExcelExpr base = parsePostfix(c);
        if (c.atSymbol("^")) {
            c.advance();
            ExcelExpr exponent = parseUnary(c);
            return new BinaryOp("^", base, exponent);
        }
        return base;
    }

    private static ExcelExpr parsePostfix(Cursor c) {
        ExcelExpr expr = parsePrimary(c);
        if (c.atSymbol("%")) {
            c.advance();
            return new Percent(expr);
        }
        return expr;
    }

    private static ExcelExpr parsePrimary(Cursor c) {
        Token t = c.peek();
        switch (t.type()) {
            case NUMBER -> { c.advance(); return new NumberLit(t.number()); }
            case STRING -> { c.advance(); return new StringLit(t.text()); }
            case CELLREF -> {
                c.advance();
                CellRef from = new CellRef(t.col(), t.row(), t.colAbsolute(), t.rowAbsolute());
                if (c.atSymbol(":")) {
                    c.advance();
                    Token t2 = c.peek();
                    if (t2.type() != TokenType.CELLREF) throw c.malformed("Expected cell reference after ':'");
                    c.advance();
                    CellRef to = new CellRef(t2.col(), t2.row(), t2.colAbsolute(), t2.rowAbsolute());
                    return new RangeRef(from, to);
                }
                return from;
            }
            case IDENT -> {
                String name = t.text();
                c.advance();
                if ("TRUE".equals(name)) return new BoolLit(true);
                if ("FALSE".equals(name)) return new BoolLit(false);
                c.expectSymbol("(");
                List<ExcelExpr> args = new ArrayList<>();
                if (!c.atSymbol(")")) {
                    args.add(parseExpr(c));
                    while (c.atSymbol(",")) {
                        c.advance();
                        args.add(parseExpr(c));
                    }
                }
                c.expectSymbol(")");
                return new FuncCall(name, args);
            }
            case SYMBOL -> {
                if ("(".equals(t.text())) {
                    c.advance();
                    ExcelExpr inner = parseExpr(c);
                    c.expectSymbol(")");
                    return inner;
                }
                throw c.malformed("Unexpected token '" + t.text() + "'");
            }
            default -> throw c.malformed("Unexpected end of formula");
        }
    }
}
