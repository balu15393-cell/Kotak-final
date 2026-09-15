package com.example.multyfikotakneo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Persists the one approved ticket that the accessibility helper is allowed to prepare in Kotak Neo. */
public final class KotakOrderAutomationStore {
    public static final String STATE_OPENING = "OPENING";
    public static final String STATE_SEARCH_OPENED = "SEARCH_OPENED";
    public static final String STATE_SYMBOL_TYPED = "SYMBOL_TYPED";
    public static final String STATE_STOCK_OPENED = "STOCK_OPENED";
    public static final String STATE_SIDE_CLICKED = "SIDE_CLICKED";
    public static final String STATE_ORDER_FORM = "ORDER_FORM";
    public static final String STATE_PRODUCT_SELECTED = "PRODUCT_SELECTED";
    public static final String STATE_TYPE_SELECTED = "TYPE_SELECTED";
    public static final String STATE_MARGIN_PROBE = "MARGIN_PROBE";
    public static final String STATE_MARGIN_VERIFY = "MARGIN_VERIFY";
    public static final String STATE_QTY_FILLED = "QTY_FILLED";
    public static final String STATE_PRICE_FILLED = "PRICE_FILLED";

    private static final String PREFS = "kotak_order_accessibility";
    private static final String K_TICKET = "ticket_json";
    private static final String K_STATE = "state";
    private static final String K_ARMED_AT = "armed_at";
    private static final String K_PROGRESS_AT = "progress_at";
    private static final String K_MARGIN_CANDIDATE = "margin_candidate";
    private static final String K_MARGIN_ATTEMPTS = "margin_attempts";

    private final SharedPreferences prefs;

    public KotakOrderAutomationStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void arm(TradeTicket ticket) throws Exception {
        if (ticket == null) throw new IllegalArgumentException("Trade ticket is missing.");
        if (ticket.dryRun) throw new IllegalArgumentException("Test tickets are not armed for Kotak UI automation.");
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(K_TICKET, ticket.toJson().toString())
                .putString(K_STATE, STATE_OPENING)
                .putLong(K_ARMED_AT, now)
                .putLong(K_PROGRESS_AT, now)
                .apply();
    }

    public TradeTicket loadTicket() {
        try {
            String raw = prefs.getString(K_TICKET, "");
            if (raw == null || raw.trim().isEmpty()) return null;
            return TradeTicket.fromJson(new JSONObject(raw));
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    public String state() {
        return prefs.getString(K_STATE, "");
    }

    public long armedAt() {
        return prefs.getLong(K_ARMED_AT, 0L);
    }

    public long progressAt() {
        return prefs.getLong(K_PROGRESS_AT, 0L);
    }

    public boolean isArmed() {
        return loadTicket() != null && !state().isEmpty();
    }

    public void setState(String state) {
        prefs.edit()
                .putString(K_STATE, state == null ? "" : state)
                .putLong(K_PROGRESS_AT, System.currentTimeMillis())
                .apply();
    }

    public void touch() {
        prefs.edit().putLong(K_PROGRESS_AT, System.currentTimeMillis()).apply();
    }

    public void setMarginCandidate(int qty) {
        prefs.edit().putInt(K_MARGIN_CANDIDATE, qty).putLong(K_PROGRESS_AT, System.currentTimeMillis()).apply();
    }

    public int marginCandidate() {
        return prefs.getInt(K_MARGIN_CANDIDATE, 0);
    }

    public int marginAttempts() {
        return prefs.getInt(K_MARGIN_ATTEMPTS, 0);
    }

    public void setMarginAttempts(int attempts) {
        prefs.edit().putInt(K_MARGIN_ATTEMPTS, attempts).putLong(K_PROGRESS_AT, System.currentTimeMillis()).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
