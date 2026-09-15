package com.example.multyfikotakneo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accessibility helper for the no-static-IP workflow.
 *
 * It is deliberately restricted to the Kotak Neo package. After the user approves a Multyfi ticket,
 * it tries to navigate to the matching stock's order form, select MIS/Intraday, select MARKET/LIMIT,
 * and fill quantity/limit price when those fields are exposed through Android accessibility.
 *
 * It NEVER presses a final Place/Buy/Sell/Swipe/Confirm control. The live broker confirmation remains
 * a manual action inside Kotak Neo.
 */
public final class KotakOrderAccessibilityService extends AccessibilityService {
    private static final String KOTAK_PACKAGE = "com.kotak.neo";
    private static final long EVENT_THROTTLE_MS = 350L;
    private static final long ARM_EXPIRY_MS = 2 * 60 * 1000L;
    private static final long STALL_MS = 25 * 1000L;
    private static final double MARGIN_SAFETY_FACTOR = 0.99;
    private static final int MAX_MARGIN_VERIFY_ATTEMPTS = 4;
    private static final Pattern MONEY_PATTERN = Pattern.compile("(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private long lastEventAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[]{KOTAK_PACKAGE};
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !KOTAK_PACKAGE.contentEquals(pkg)) return;

        long now = System.currentTimeMillis();
        if (now - lastEventAt < EVENT_THROTTLE_MS) return;
        lastEventAt = now;

        KotakOrderAutomationStore store = new KotakOrderAutomationStore(this);
        TradeTicket ticket = store.loadTicket();
        if (ticket == null) return;

        if (store.armedAt() <= 0 || now - store.armedAt() > ARM_EXPIRY_MS) {
            store.clear();
            ApprovalNotifier.showStatus(this, "Kotak preparation expired",
                    "The approved ticket was not prepared within 2 minutes. Continue manually in Kotak Neo or approve a fresh Multyfi ticket.");
            return;
        }
        if (store.progressAt() > 0 && now - store.progressAt() > STALL_MS) {
            store.clear();
            ApprovalNotifier.showStatus(this, ticket.symbol + " — Kotak helper paused",
                    "Kotak Neo's current screen could not be identified safely. The app has stopped tapping. Continue the MIS ticket manually from the current Kotak screen.");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            drive(root, ticket, store);
        } catch (Exception e) {
            store.clear();
            ApprovalNotifier.showStatus(this, ticket.symbol + " — Kotak helper stopped",
                    "Could not prepare the order form safely: " + safe(e) + ". Continue manually in Kotak Neo.");
        }
    }

    @Override
    public void onInterrupt() {
        // Android interrupted accessibility feedback. Do not perform any broker action.
    }

    private void drive(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        String state = store.state();
        if (state == null || state.isEmpty()) return;

        // If Kotak already shows an order form, only touch it when the intended symbol is visible.
        // This prevents an approved ticket from modifying a stale/previous stock order screen.
        if (!KotakOrderAutomationStore.STATE_ORDER_FORM.equals(state)
                && !KotakOrderAutomationStore.STATE_PRODUCT_SELECTED.equals(state)
                && !KotakOrderAutomationStore.STATE_TYPE_SELECTED.equals(state)
                && !KotakOrderAutomationStore.STATE_MARGIN_PROBE.equals(state)
                && !KotakOrderAutomationStore.STATE_MARGIN_VERIFY.equals(state)
                && !KotakOrderAutomationStore.STATE_QTY_FILLED.equals(state)
                && !KotakOrderAutomationStore.STATE_PRICE_FILLED.equals(state)
                && looksLikeOrderForm(root)) {
            if (!containsIntendedSymbol(root, t)) {
                stopAtOrderForm(t, store,
                        "Kotak Neo opened on an existing order form, but the intended symbol " + t.symbol + " could not be verified. No order fields were changed.");
                return;
            }
            store.setState(KotakOrderAutomationStore.STATE_ORDER_FORM);
            return;
        }

        switch (state) {
            case KotakOrderAutomationStore.STATE_OPENING:
                if (findSearchEditable(root) != null) {
                    setSymbol(root, t, store);
                    return;
                }
                AccessibilityNodeInfo search = findClickableContaining(root,
                        "search", "search stocks", "search & trade", "search scrip", "search here");
                if (search != null && safeClick(search)) {
                    store.setState(KotakOrderAutomationStore.STATE_SEARCH_OPENED);
                }
                return;

            case KotakOrderAutomationStore.STATE_SEARCH_OPENED:
                setSymbol(root, t, store);
                return;

            case KotakOrderAutomationStore.STATE_SYMBOL_TYPED:
                AccessibilityNodeInfo symbolResult = findExactNonEditable(root, t.symbol, t.tradingSymbol);
                if (symbolResult != null && safeClick(symbolResult)) {
                    store.setState(KotakOrderAutomationStore.STATE_STOCK_OPENED);
                }
                return;

            case KotakOrderAutomationStore.STATE_STOCK_OPENED:
                if (!containsIntendedSymbol(root, t)) return;
                // This is the stock-detail BUY/SELL button, not the final broker confirmation.
                AccessibilityNodeInfo side = findExactClickable(root, t.side);
                if (side != null && safeClick(side)) {
                    store.setState(KotakOrderAutomationStore.STATE_SIDE_CLICKED);
                }
                return;

            case KotakOrderAutomationStore.STATE_SIDE_CLICKED:
                if (looksLikeOrderForm(root) && containsIntendedSymbol(root, t)) {
                    store.setState(KotakOrderAutomationStore.STATE_ORDER_FORM);
                }
                return;

            case KotakOrderAutomationStore.STATE_ORDER_FORM:
                selectProduct(root, store);
                return;

            case KotakOrderAutomationStore.STATE_PRODUCT_SELECTED:
                selectOrderType(root, t, store);
                return;

            case KotakOrderAutomationStore.STATE_TYPE_SELECTED:
                if (t.calculateQtyFromKotakMargin && !"EXIT".equals(t.ticketKind)) {
                    beginMarginQuantityCalculation(root, t, store);
                } else {
                    fillQuantity(root, t, store);
                }
                return;

            case KotakOrderAutomationStore.STATE_MARGIN_PROBE:
                calculateCandidateFromLiveMargin(root, t, store);
                return;

            case KotakOrderAutomationStore.STATE_MARGIN_VERIFY:
                verifyCandidateAgainstLiveMargin(root, t, store);
                return;

            case KotakOrderAutomationStore.STATE_QTY_FILLED:
                if ("LIMIT".equalsIgnoreCase(t.orderType)) {
                    fillLimitPrice(root, t, store);
                } else {
                    finishAtConfirmation(root, t, store, false);
                }
                return;

            case KotakOrderAutomationStore.STATE_PRICE_FILLED:
                finishAtConfirmation(root, t, store, true);
                return;

            default:
                store.clear();
        }
    }

    private void setSymbol(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        AccessibilityNodeInfo edit = findSearchEditable(root);
        if (edit == null) return;
        if (setText(edit, cleanSymbol(t.symbol))) {
            store.setState(KotakOrderAutomationStore.STATE_SYMBOL_TYPED);
        }
    }

    private void selectProduct(AccessibilityNodeInfo root, KotakOrderAutomationStore store) {
        AccessibilityNodeInfo product = findExactClickable(root, "Intraday", "MIS");
        if (product != null) {
            safeClick(product); // clicking an already-selected tab is harmless and never submits an order
        }
        store.setState(KotakOrderAutomationStore.STATE_PRODUCT_SELECTED);
    }

    private void selectOrderType(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        String wanted = "LIMIT".equalsIgnoreCase(t.orderType) ? "Limit" : "Market";
        AccessibilityNodeInfo type = findExactClickable(root, wanted);
        if (type != null) {
            safeClick(type);
        }
        store.setState(KotakOrderAutomationStore.STATE_TYPE_SELECTED);
    }


    private void beginMarginQuantityCalculation(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        AccessibilityNodeInfo qty = findEditableByKeyword(root, "quantity", "qty");
        if (qty == null) {
            stopAtOrderForm(t, store,
                    "Kotak order page opened, but the Quantity field was not exposed clearly. Quantity cannot be calculated from live MIS margin automatically.");
            return;
        }
        if (!setText(qty, "1")) {
            stopAtOrderForm(t, store,
                    "Kotak order page opened, but the helper could not set a 1-share margin probe. Quantity cannot be calculated safely.");
            return;
        }
        t.quantity = 1;
        store.setMarginAttempts(0);
        store.setState(KotakOrderAutomationStore.STATE_MARGIN_PROBE);
        ApprovalNotifier.showStatus(this, t.symbol + " — READING LIVE MIS MARGIN",
                "Kotak MIS order page opened. Reading Kotak's live Available Margin and Margin Required to calculate quantity. No manual capital value is being used.");
    }

    private void calculateCandidateFromLiveMargin(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        MarginSnapshot m = readMarginSnapshot(root);
        if (!m.isComplete()) return; // wait for Kotak to refresh the 1-share margin values
        if (m.available <= 0 || m.required <= 0) {
            stopAtOrderForm(t, store, "Kotak displayed invalid MIS margin values. Quantity calculation stopped.");
            return;
        }
        int candidate = (int) Math.floor((m.available * MARGIN_SAFETY_FACTOR) / m.required);
        if (candidate < 1) {
            stopAtOrderForm(t, store, String.format(Locale.ROOT,
                    "Kotak Available Margin is ₹%.2f and 1-share MIS Margin Required is ₹%.2f, so there is not enough margin for one share.",
                    m.available, m.required));
            return;
        }
        candidate = Math.min(candidate, 1_000_000);
        AccessibilityNodeInfo qty = findEditableByKeyword(root, "quantity", "qty");
        if (qty == null || !setText(qty, String.valueOf(candidate))) {
            stopAtOrderForm(t, store, "Live MIS margin was read, but the calculated quantity could not be filled automatically.");
            return;
        }
        t.quantity = candidate;
        store.setMarginCandidate(candidate);
        store.setMarginAttempts(1);
        store.setState(KotakOrderAutomationStore.STATE_MARGIN_VERIFY);
    }

    private void verifyCandidateAgainstLiveMargin(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        MarginSnapshot m = readMarginSnapshot(root);
        if (!m.isComplete()) return;
        int candidate = store.marginCandidate();
        if (candidate < 1) candidate = Math.max(1, t.quantity);

        double allowed = m.available * MARGIN_SAFETY_FACTOR;
        if (m.required <= allowed) {
            t.quantity = candidate;
            store.setState(KotakOrderAutomationStore.STATE_QTY_FILLED);
            ApprovalNotifier.showStatus(this, t.symbol + " — LIVE MIS QTY CALCULATED",
                    String.format(Locale.ROOT,
                            "Kotak live Available Margin ₹%.2f • Margin Required for Qty %d ₹%.2f • final Qty %d. The helper will continue preparing the order page and stop before final confirmation.",
                            m.available, candidate, m.required, candidate));
            return;
        }

        int attempts = store.marginAttempts();
        if (attempts >= MAX_MARGIN_VERIFY_ATTEMPTS) {
            stopAtOrderForm(t, store, String.format(Locale.ROOT,
                    "Kotak live margin verification did not settle safely after %d attempts. Current Available Margin ₹%.2f, required ₹%.2f. Enter quantity manually.",
                    attempts, m.available, m.required));
            return;
        }

        int revised = (int) Math.floor(candidate * (allowed / m.required));
        if (revised >= candidate) revised = candidate - 1;
        if (revised < 1) {
            stopAtOrderForm(t, store, "Kotak live MIS margin is insufficient for one share after verification.");
            return;
        }
        AccessibilityNodeInfo qty = findEditableByKeyword(root, "quantity", "qty");
        if (qty == null || !setText(qty, String.valueOf(revised))) {
            stopAtOrderForm(t, store, "Kotak live margin required a smaller quantity, but the revised quantity could not be filled automatically.");
            return;
        }
        t.quantity = revised;
        store.setMarginCandidate(revised);
        store.setMarginAttempts(attempts + 1);
        store.touch();
    }

    private MarginSnapshot readMarginSnapshot(AccessibilityNodeInfo root) {
        Double available = findMoneyForLabel(root, "available margin", "margin available", "available funds", "available cash");
        Double required = findMoneyForLabel(root, "margin required", "required margin", "margin reqd", "required");
        return new MarginSnapshot(available == null ? 0 : available, required == null ? 0 : required);
    }

    private Double findMoneyForLabel(AccessibilityNodeInfo root, String... labels) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            String raw = nodeMeta(n);
            if (!containsKeyword(raw, labels)) continue;
            Double same = parseMoney(raw);
            if (same != null) return same;
        }

        // Label/value are often separate accessibility nodes. Find the nearest numeric node to each matching label.
        Double bestValue = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo label : nodes) {
            if (!containsKeyword(nodeMeta(label), labels)) continue;
            Rect lb = new Rect();
            label.getBoundsInScreen(lb);
            for (AccessibilityNodeInfo value : nodes) {
                if (value == label || isEditable(value)) continue;
                Double money = parseMoney(visibleText(value));
                if (money == null) continue;
                Rect vb = new Rect();
                value.getBoundsInScreen(vb);
                if (lb.isEmpty() || vb.isEmpty()) continue;
                int vertical = Math.abs(vb.centerY() - lb.centerY());
                int horizontal = Math.abs(vb.centerX() - lb.centerX());
                if (vertical > dp(180) && vb.top - lb.bottom > dp(220)) continue;
                long score = vertical * 3L + horizontal;
                if (score < bestScore) {
                    bestScore = score;
                    bestValue = money;
                }
            }
        }
        return bestValue;
    }

    private Double parseMoney(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        Matcher m = MONEY_PATTERN.matcher(text);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                if (v > 0) return v;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static final class MarginSnapshot {
        final double available;
        final double required;
        MarginSnapshot(double available, double required) {
            this.available = available;
            this.required = required;
        }
        boolean isComplete() { return available > 0 && required > 0; }
    }

    private void fillQuantity(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        AccessibilityNodeInfo qty = findEditableByKeyword(root, "quantity", "qty");
        if (qty == null) {
            stopAtOrderForm(t, store,
                    "Kotak order page opened, but the Quantity field was not exposed clearly to Android Accessibility. Enter quantity " + t.quantity + " manually and confirm the MIS order yourself.");
            return;
        }
        if (!setText(qty, String.valueOf(t.quantity))) {
            stopAtOrderForm(t, store,
                    "Kotak order page opened, but Quantity could not be filled automatically. Enter quantity " + t.quantity + " manually.");
            return;
        }
        store.setState(KotakOrderAutomationStore.STATE_QTY_FILLED);
    }

    private void fillLimitPrice(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        if (t.orderPrice == null || t.orderPrice <= 0) {
            stopAtOrderForm(t, store, "LIMIT was selected but the prepared limit price is missing. Continue manually.");
            return;
        }
        AccessibilityNodeInfo price = findEditableByKeyword(root, "price", "limit price", "order price");
        if (price == null) {
            stopAtOrderForm(t, store,
                    String.format(Locale.ROOT, "Kotak order page opened. Enter LIMIT price ₹%.2f manually, then review and confirm.", t.orderPrice));
            return;
        }
        if (!setText(price, String.format(Locale.ROOT, "%.2f", t.orderPrice))) {
            stopAtOrderForm(t, store,
                    String.format(Locale.ROOT, "Kotak order page opened. LIMIT price ₹%.2f could not be filled automatically; enter it manually.", t.orderPrice));
            return;
        }
        store.setState(KotakOrderAutomationStore.STATE_PRICE_FILLED);
    }

    private void finishAtConfirmation(AccessibilityNodeInfo root, TradeTicket t,
                                      KotakOrderAutomationStore store, boolean limitFilled) {
        // Deliberately do not search for or click any final Place/Buy/Sell/Swipe/Confirm control.
        store.clear();
        String order = "LIMIT".equalsIgnoreCase(t.orderType) && t.orderPrice != null
                ? String.format(Locale.ROOT, "LIMIT ₹%.2f", t.orderPrice)
                : "MARKET";
        String targetSl = "EXIT".equals(t.ticketKind)
                ? "This is the prepared Multyfi exit."
                : "Multyfi Target: " + money(t.target) + " • SL: " + money(t.stopLoss) + ".";
        String qtySource = t.calculateQtyFromKotakMargin && !"EXIT".equals(t.ticketKind)
                ? "Qty " + t.quantity + " (calculated from Kotak live MIS margin)"
                : "Qty " + t.quantity;
        ApprovalNotifier.showStatus(this,
                t.symbol + " — KOTAK ORDER PAGE READY",
                "Kotak Neo order form prepared: " + t.side + " • MIS/Intraday • " + qtySource + " • " + order + ". " +
                        targetSl + " Review every field and use Kotak's final confirmation yourself. The helper will not submit the order.");
    }

    private void stopAtOrderForm(TradeTicket t, KotakOrderAutomationStore store, String message) {
        store.clear();
        ApprovalNotifier.showStatus(this, t.symbol + " — ORDER PAGE OPEN", message +
                " The helper has stopped and will not press the final broker confirmation.");
    }


    private boolean containsIntendedSymbol(AccessibilityNodeInfo root, TradeTicket t) {
        String wanted = cleanSymbol(t == null ? null : t.symbol);
        if (wanted.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (isEditable(n)) continue;
            String visible = visibleText(n).toUpperCase(Locale.ROOT);
            if (visible.isEmpty()) continue;
            String compact = cleanSymbol(visible);
            if (wanted.equals(compact)) return true;
            String[] parts = visible.split("[^A-Z0-9&]+");
            for (String part : parts) {
                if (wanted.equals(cleanSymbol(part))) return true;
            }
        }
        return false;
    }

    private boolean looksLikeOrderForm(AccessibilityNodeInfo root) {
        int score = 0;
        if (containsAnyText(root, "intraday", "mis")) score++;
        if (containsAnyText(root, "quantity", "qty")) score++;
        if (containsAnyText(root, "market", "limit", "order type")) score++;
        return score >= 2;
    }

    private AccessibilityNodeInfo findSearchEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo byKeyword = findEditableByKeyword(root, "search", "search stocks", "search scrip");
        if (byKeyword != null) return byKeyword;
        List<AccessibilityNodeInfo> edits = new ArrayList<>();
        collectEditables(root, edits);
        if (edits.size() == 1 && !looksLikeOrderForm(root)) return edits.get(0);
        return null;
    }

    private AccessibilityNodeInfo findEditableByKeyword(AccessibilityNodeInfo root, String... keywords) {
        List<AccessibilityNodeInfo> edits = new ArrayList<>();
        collectEditables(root, edits);
        for (AccessibilityNodeInfo e : edits) {
            String meta = nodeMeta(e);
            if (containsKeyword(meta, keywords)) return e;
        }

        // Fall back to an editable immediately beside/below a visible accessibility label.
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        collectNodes(root, labels);
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo label : labels) {
            String labelMeta = nodeMeta(label);
            if (!containsKeyword(labelMeta, keywords)) continue;
            Rect lb = new Rect();
            label.getBoundsInScreen(lb);
            for (AccessibilityNodeInfo e : edits) {
                Rect eb = new Rect();
                e.getBoundsInScreen(eb);
                if (lb.isEmpty() || eb.isEmpty()) continue;
                int vertical = Math.max(0, eb.top - lb.bottom);
                int horizontal = Math.abs(eb.centerX() - lb.centerX());
                if (vertical > dp(280)) continue;
                long score = vertical * 4L + horizontal;
                if (score < bestScore) {
                    bestScore = score;
                    best = e;
                }
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo root, String... needles) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            String meta = nodeMeta(n);
            if (containsKeyword(meta, needles) && clickableAncestor(n) != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findExactClickable(AccessibilityNodeInfo root, String... values) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            String visible = visibleText(n);
            for (String v : values) {
                if (v != null && !v.trim().isEmpty() && visible.equalsIgnoreCase(v.trim())
                        && clickableAncestor(n) != null) {
                    return n;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findExactNonEditable(AccessibilityNodeInfo root, String... values) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (isEditable(n)) continue;
            String visible = visibleText(n);
            for (String v : values) {
                if (v == null || v.trim().isEmpty()) continue;
                String wanted = cleanSymbol(v);
                String got = cleanSymbol(visible);
                if (!wanted.isEmpty() && wanted.equalsIgnoreCase(got) && clickableAncestor(n) != null) return n;
            }
        }
        return null;
    }

    private boolean containsAnyText(AccessibilityNodeInfo root, String... values) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (containsKeyword(nodeMeta(n), values)) return true;
        }
        return false;
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null || value == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean safeClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo c = clickableAncestor(node);
        return c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; n != null && i < 6; i++) {
            if (n.isClickable() && n.isEnabled()) return n;
            n = n.getParent();
        }
        return null;
    }

    private void collectEditables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (isEditable(node) && node.isEnabled()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectEditables(child, out);
        }
    }

    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectNodes(child, out);
        }
    }

    private boolean isEditable(AccessibilityNodeInfo n) {
        if (n == null) return false;
        if (n.isEditable()) return true;
        CharSequence cls = n.getClassName();
        return cls != null && cls.toString().toLowerCase(Locale.ROOT).contains("edittext");
    }

    private String nodeMeta(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder b = new StringBuilder();
        add(b, n.getText());
        add(b, n.getContentDescription());
        add(b, n.getHintText());
        add(b, n.getViewIdResourceName());
        return normalize(b.toString());
    }

    private String visibleText(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder b = new StringBuilder();
        add(b, n.getText());
        add(b, n.getContentDescription());
        return b.toString().trim();
    }

    private static void add(StringBuilder b, CharSequence s) {
        if (s == null) return;
        String x = s.toString().trim();
        if (x.isEmpty()) return;
        if (b.length() > 0) b.append(' ');
        b.append(x);
    }

    private static boolean containsKeyword(String haystack, String... needles) {
        String h = normalize(haystack);
        for (String n : needles) {
            if (n != null && !n.trim().isEmpty() && h.contains(normalize(n))) return true;
        }
        return false;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String cleanSymbol(String s) {
        if (s == null) return "";
        String x = s.trim().toUpperCase(Locale.ROOT);
        int colon = x.indexOf(':');
        if (colon >= 0 && colon + 1 < x.length()) x = x.substring(colon + 1);
        int dash = x.indexOf('-');
        if (dash > 0) x = x.substring(0, dash);
        return x.replaceAll("[^A-Z0-9&]", "");
    }

    private static String money(Double v) {
        return v == null ? "not parsed" : String.format(Locale.ROOT, "₹%.2f", v);
    }

    private int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String safe(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.trim().isEmpty() ? "Unknown error" : m;
    }

    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager != null && manager.isEnabled()) {
            List<AccessibilityServiceInfo> enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            String expected = new ComponentName(context, KotakOrderAccessibilityService.class).flattenToString();
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null) continue;
                String id = info.getId();
                if (id != null && (id.equals(expected) || id.endsWith("/" + KotakOrderAccessibilityService.class.getName()))) {
                    return true;
                }
            }
        }

        // Some OEMs return a delayed AccessibilityManager list; check the secure setting as a fallback.
        String setting = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(setting);
        ComponentName expectedComponent = new ComponentName(context, KotakOrderAccessibilityService.class);
        while (splitter.hasNext()) {
            ComponentName enabledComponent = ComponentName.unflattenFromString(splitter.next());
            if (expectedComponent.equals(enabledComponent)) return true;
        }
        return false;
    }
}
