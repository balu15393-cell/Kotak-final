package com.example.multyfikotakneo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SignalParser {
    private SignalParser() {}

    private static final String NUM = "([0-9][0-9,]*(?:\\.[0-9]+)?)";
    private static final Set<String> SYMBOL_BLACKLIST = new HashSet<>(Arrays.asList(
            "BUY", "SELL", "INTRADAY", "MIS", "NSE", "BSE", "ENTRY", "TARGET",
            "STOP", "LOSS", "SL", "AT", "ABOVE", "BELOW", "CMP", "CALL", "TRADE",
            "RELEASED", "EQUITY", "STOCK", "NAME", "UPDATE", "BOOK", "PROFIT"
    ));

    /**
     * Multyfi commonly publishes an informational pre-alert such as:
     * "Equity Intraday Trade In 15 Mins".
     * It must never be treated as an entry signal.
     */
    public static boolean looksLikePreAlert(String title, String body) {
        String lower = normalize((title == null ? "" : title) + " " + (body == null ? "" : body))
                .toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "equity intraday trade in 15 mins",
                "equity intraday trade in 15 min",
                "trade idea will be posted within 15 minutes",
                "trade idea will be posted within 15 mins",
                "new trades releasing soon");
    }

    /**
     * Recognizes both the old explicit BUY/SELL wording and Multyfi's current
     * "Released: Equity Intraday Trade" format that may omit BUY/SELL.
     */
    public static boolean looksLikeEntry(String title, String body) {
        String raw = normalize((title == null ? "" : title) + " " + (body == null ? "" : body));
        String lower = raw.toLowerCase(Locale.ROOT);
        String upper = raw.toUpperCase(Locale.ROOT);

        if (looksLikePreAlert(title, body)) return false;
        if (upper.matches(".*\\b(BUY|SELL)\\b.*")) return true;

        boolean released = containsAny(lower,
                "released: equity intraday trade",
                "released equity intraday trade");
        boolean hasStock = containsAny(lower, "stock name:", "stock name -", "stock:", "symbol:", "ticker:");
        boolean hasEntry = containsAny(lower, "entry range:", "entry range -", "entry price:", "entry:");
        boolean hasTarget = containsAny(lower, "target:", "target -", "tgt:");
        boolean hasStop = containsAny(lower, "stop loss:", "stop loss -", "stoploss:", "sl:");
        return released && hasStock && hasEntry && hasTarget && hasStop;
    }

    public static Signal parse(String title, String body, boolean strictIntraday) {
        String combined = normalize((title == null ? "" : title) + " " + (body == null ? "" : body));
        String lower = combined.toLowerCase(Locale.ROOT);
        String upper = combined.toUpperCase(Locale.ROOT);

        if (looksLikePreAlert(title, body)) {
            throw new IllegalArgumentException("This is Multyfi's 15-minute pre-alert, not the released trade.");
        }

        boolean intraday = containsAny(lower, "intraday", "intra day", "day trade", "daytrade", "mis");
        if (containsAny(lower, "positional", "swing", "delivery", "long term", "long-term", "investment")) {
            throw new IllegalArgumentException("Alert looks positional/swing/delivery, so it was blocked.");
        }
        if (strictIntraday && !intraday) {
            throw new IllegalArgumentException("Strict intraday mode: intraday/MIS wording was not detected.");
        }

        String explicitSide = extractExplicitSide(combined);
        String symbol = extractSymbol(upper, explicitSide);
        if (symbol == null) throw new IllegalArgumentException("Stock ticker/symbol could not be identified reliably.");

        double[] range = extractEntry(combined, explicitSide, symbol);
        if (range == null) throw new IllegalArgumentException("Multyfi entry price/range was not found.");

        Double target = extractSingle(combined, "(?:target(?:\\s*1)?|tgt(?:\\s*1)?)");
        Double stop = extractSingle(combined, "(?:stop\\s*loss|stoploss|\\bsl\\b)");
        if (target == null || target <= 0) {
            throw new IllegalArgumentException("Multyfi target was not found. Entry blocked because the app must use the advisory target exactly.");
        }
        if (stop == null || stop <= 0) {
            throw new IllegalArgumentException("Multyfi stop-loss was not found. Entry blocked because the app must use the advisory SL exactly.");
        }

        String side = explicitSide;
        if (side == null) {
            side = inferSideFromLevels(range[0], range[1], target, stop);
        }
        if (side == null) {
            throw new IllegalArgumentException("BUY/SELL is not written in the Multyfi alert and direction could not be inferred safely from entry/target/SL.");
        }

        validateDirection(side, range[0], range[1], target, stop);
        return new Signal(side, symbol, range[0], range[1], target, stop, combined, intraday);
    }

    private static String extractExplicitSide(String text) {
        Matcher sideMatcher = Pattern.compile("\\b(BUY|SELL)\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        return sideMatcher.find() ? sideMatcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String inferSideFromLevels(double low, double high, double target, double stop) {
        // BUY: target is above the full entry range and SL is below it.
        if (target > high && stop < low) return "BUY";
        // SELL: target is below the full entry range and SL is above it.
        if (target < low && stop > high) return "SELL";
        return null;
    }

    private static void validateDirection(String side, double low, double high, double target, double stop) {
        if ("BUY".equals(side)) {
            if (!(target > low && stop < high)) {
                throw new IllegalArgumentException("Multyfi BUY levels are inconsistent: target should be above entry and stop-loss below entry.");
            }
        } else if ("SELL".equals(side)) {
            if (!(target < high && stop > low)) {
                throw new IllegalArgumentException("Multyfi SELL levels are inconsistent: target should be below entry and stop-loss above entry.");
            }
        }
    }

    private static String extractSymbol(String upper, String side) {
        Pattern[] patterns = new Pattern[] {
                Pattern.compile("\\b(?:STOCK\\s*NAME|SYMBOL|TICKER|SCRIP|STOCK)\\s*[:=\\-]\\s*([A-Z][A-Z0-9&.\\-]{1,24})\\b"),
                side == null ? null : Pattern.compile("\\b" + Pattern.quote(side) + "\\s+([A-Z][A-Z0-9&.\\-]{1,24})\\b"),
                side == null ? null : Pattern.compile("\\b([A-Z][A-Z0-9&.\\-]{1,24})\\s+" + Pattern.quote(side) + "\\b")
        };
        for (Pattern p : patterns) {
            if (p == null) continue;
            Matcher m = p.matcher(upper);
            if (m.find()) {
                String candidate = m.group(1);
                if (!SYMBOL_BLACKLIST.contains(candidate)) return candidate;
            }
        }
        return null;
    }

    private static double[] extractEntry(String text, String side, String symbol) {
        String labels = "(?:entry\\s*range|entry(?:\\s*price)?|buy\\s*price|sell\\s*price|buy\\s*around|sell\\s*around)";
        Pattern rangePattern = Pattern.compile(
                labels + "\\s*[:=@]?\\s*(?:around|near|at)?\\s*(?:rs\\.?|inr|₹)?\\s*" + NUM +
                        "\\s*(?:-|–|—|to)\\s*(?:rs\\.?|inr|₹)?\\s*" + NUM,
                Pattern.CASE_INSENSITIVE);
        Matcher r = rangePattern.matcher(text);
        if (r.find()) {
            double a = parseNum(r.group(1));
            double b = parseNum(r.group(2));
            return new double[] {Math.min(a, b), Math.max(a, b)};
        }

        Pattern singlePattern = Pattern.compile(
                labels + "\\s*[:=@]?\\s*(?:around|near|at|above|below)?\\s*(?:rs\\.?|inr|₹)?\\s*" + NUM,
                Pattern.CASE_INSENSITIVE);
        Matcher s = singlePattern.matcher(text);
        if (s.find()) {
            double v = parseNum(s.group(1));
            return new double[] {v, v};
        }

        if (side != null) {
            Pattern compact = Pattern.compile(
                    "\\b" + Pattern.quote(side) + "\\b\\s+" + Pattern.quote(symbol) +
                            "\\s*(?:@|at)\\s*(?:rs\\.?|inr|₹)?\\s*" + NUM,
                    Pattern.CASE_INSENSITIVE);
            Matcher c = compact.matcher(text);
            if (c.find()) {
                double v = parseNum(c.group(1));
                return new double[] {v, v};
            }
        }
        return null;
    }

    private static Double extractSingle(String text, String labelRegex) {
        Pattern p = Pattern.compile(labelRegex + "\\s*(?:price)?\\s*[:=@-]?\\s*(?:rs\\.?|inr|₹)?\\s*" + NUM,
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? parseNum(m.group(1)) : null;
    }

    private static double parseNum(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}
