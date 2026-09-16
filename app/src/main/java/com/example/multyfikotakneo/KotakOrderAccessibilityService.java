package com.example.multyfikotakneo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v3.9 navigation-only Kotak Neo accessibility helper.
 *
 * Goal:
 * Multyfi approval -> Kotak Neo -> known/home screen -> search -> intended stock -> BUY/SELL
 * -> order form -> select MIS/Intraday when exposed -> STOP.
 *
 * This helper never enters quantity or price and never presses any final Place/Buy/Sell/Swipe/
 * Confirm action on the order form.
 */
public final class KotakOrderAccessibilityService extends AccessibilityService {
    private static final String KOTAK_PACKAGE = "com.kotak.neo";
    private static final long EVENT_THROTTLE_MS = 220L;
    private static final long ARM_EXPIRY_MS = 2 * 60 * 1000L;
    private static final long STALL_MS = 45 * 1000L;
    private static final long RECOVERY_GAP_MS = 1200L;
    private static final int MAX_BACK_RECOVERY = 4;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean pollScheduled;
    private long lastEventAt;
    private long lastRecoveryAt;
    private long activeArmAt;
    private int backRecoveryCount;
    private boolean searchGestureAttempted;
    private boolean homeGestureAttempted;
    private String lastStep = "";

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            processActiveWindow();
            KotakOrderAutomationStore store = new KotakOrderAutomationStore(KotakOrderAccessibilityService.this);
            if (store.isArmed()) schedulePoll(450L);
        }
    };

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
        if (now - lastEventAt >= EVENT_THROTTLE_MS) {
            lastEventAt = now;
            processActiveWindow();
        }
        schedulePoll(300L);
    }

    private void schedulePoll(long delayMs) {
        if (pollScheduled) return;
        pollScheduled = true;
        pollHandler.postDelayed(pollRunnable, Math.max(100L, delayMs));
    }

    private void processActiveWindow() {
        long now = System.currentTimeMillis();
        KotakOrderAutomationStore store = new KotakOrderAutomationStore(this);
        TradeTicket ticket = store.loadTicket();
        if (ticket == null) return;

        long armedAt = store.armedAt();
        if (armedAt <= 0 || now - armedAt > ARM_EXPIRY_MS) {
            store.clear();
            step(ticket, "EXPIRED", "Kotak navigation expired. Approve a fresh Multyfi ticket and try again.");
            return;
        }

        if (activeArmAt != armedAt) {
            activeArmAt = armedAt;
            backRecoveryCount = 0;
            searchGestureAttempted = false;
            homeGestureAttempted = false;
            lastRecoveryAt = 0L;
            lastStep = "";
        }

        if (store.progressAt() > 0 && now - store.progressAt() > STALL_MS) {
            store.clear();
            step(ticket, "PAUSED", "Kotak Neo did not expose the next screen within 45 seconds. The helper stopped without submitting anything.");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg == null || !KOTAK_PACKAGE.contentEquals(rootPkg)) return;

        try {
            drive(root, ticket, store, now);
        } catch (Exception e) {
            store.clear();
            step(ticket, "STOPPED", "Kotak navigation stopped safely: " + safe(e));
        }
    }

    private void drive(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store, long now) {
        String state = store.state();
        if (state == null || state.isEmpty()) return;

        // Recover from the exact failure seen in v3.8: Kotak opens on the screen that was already open.
        // If that screen is an old order form, back out instead of modifying it.
        if (looksLikeOrderForm(root)
                && !KotakOrderAutomationStore.STATE_SIDE_CLICKED.equals(state)
                && !KotakOrderAutomationStore.STATE_ORDER_FORM.equals(state)) {
            if (containsIntendedSymbol(root, t)) {
                finishAtOrderPage(root, t, store);
            } else if (KotakOrderAutomationStore.STATE_OPENING.equals(state)) {
                recoverTowardHome(root, t, store, now);
            } else {
                store.clear();
                step(t, "WRONG ORDER PAGE", "Kotak showed an order page for a different stock. The helper stopped without touching order fields.");
            }
            return;
        }

        switch (state) {
            case KotakOrderAutomationStore.STATE_OPENING:
                handleOpening(root, t, store, now);
                return;

            case KotakOrderAutomationStore.STATE_SEARCH_OPENED:
                if (setSymbol(root, t, store)) return;
                // Search may animate in after the click. If it did not, safely recover and retry.
                if (now - store.progressAt() > 6000L) {
                    store.setState(KotakOrderAutomationStore.STATE_OPENING);
                    searchGestureAttempted = false;
                    step(t, "SEARCH RETRY", "Kotak search did not expose a text field. Returning to a known screen and retrying.");
                }
                return;

            case KotakOrderAutomationStore.STATE_SYMBOL_TYPED:
                AccessibilityNodeInfo result = findSymbolResult(root, t);
                if (result != null && safeClick(result)) {
                    store.setState(KotakOrderAutomationStore.STATE_STOCK_OPENED);
                    step(t, "STOCK FOUND", "Opened " + cleanSymbol(t.symbol) + ". Looking for the " + t.side + " button.");
                }
                return;

            case KotakOrderAutomationStore.STATE_STOCK_OPENED:
                if (looksLikeOrderForm(root)) {
                    finishAtOrderPage(root, t, store);
                    return;
                }
                if (!containsIntendedSymbol(root, t)) return;
                AccessibilityNodeInfo side = findExactClickable(root, t.side);
                if (side == null) side = findClickableContaining(root, t.side);
                if (side != null && safeClick(side)) {
                    store.setState(KotakOrderAutomationStore.STATE_SIDE_CLICKED);
                    step(t, t.side + " OPENED", "Opening the Kotak order page for " + cleanSymbol(t.symbol) + ".");
                }
                return;

            case KotakOrderAutomationStore.STATE_SIDE_CLICKED:
                if (looksLikeOrderForm(root)) {
                    finishAtOrderPage(root, t, store);
                }
                return;

            case KotakOrderAutomationStore.STATE_ORDER_FORM:
                finishAtOrderPage(root, t, store);
                return;

            default:
                // v3.9 is intentionally navigation-only. Old v3.8 fill states are not executed.
                store.clear();
                step(t, "STOPPED", "An old automation state was detected. v3.9 stopped before changing any order field.");
        }
    }

    private void handleOpening(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store, long now) {
        // Best path: some Kotak screens expose the search field directly.
        if (setSymbol(root, t, store)) return;

        // Next best: click any accessible Kotak search control.
        AccessibilityNodeInfo search = findClickableContaining(root,
                "search shares", "search stocks", "search scrip", "search & trade", "search here", "search");
        if (search != null && safeClick(search)) {
            store.setState(KotakOrderAutomationStore.STATE_SEARCH_OPENED);
            step(t, "SEARCH OPEN", "Kotak search opened. Entering " + cleanSymbol(t.symbol) + ".");
            return;
        }

        // If the Home tab is accessible, use it to get away from Portfolio/Orders/Watchlist/etc.
        AccessibilityNodeInfo home = findExactClickable(root, "Home");
        if (home != null && safeClick(home)) {
            store.touch();
            step(t, "GOING HOME", "Kotak opened on another screen. Returning to Home before searching.");
            schedulePoll(550L);
            return;
        }

        // Kotak's current Home UI has a search bar at the top. Some builds render it visually but
        // do not expose a clickable accessibility node. Use a coordinate tap only when Home-like
        // content is positively detected.
        if (looksLikeHome(root) && !searchGestureAttempted) {
            searchGestureAttempted = true;
            if (tapFraction(0.36f, 0.085f)) {
                store.touch();
                step(t, "SEARCH TAP", "Kotak Home detected. Tapped the top search bar and waiting for the search field.");
                schedulePoll(500L);
                return;
            }
        }

        recoverTowardHome(root, t, store, now);
    }

    private void recoverTowardHome(AccessibilityNodeInfo root, TradeTicket t,
                                   KotakOrderAutomationStore store, long now) {
        if (now - lastRecoveryAt < RECOVERY_GAP_MS) return;
        lastRecoveryAt = now;

        AccessibilityNodeInfo home = findExactClickable(root, "Home");
        if (home != null && safeClick(home)) {
            store.touch();
            step(t, "HOME RECOVERY", "Returning to Kotak Home before searching for " + cleanSymbol(t.symbol) + ".");
            return;
        }

        // If bottom-nav labels are visible but Home itself is not a clickable node, use a conservative
        // bottom-left Home gesture. This never targets an order confirmation control.
        if (!homeGestureAttempted && hasBottomNavigation(root)) {
            homeGestureAttempted = true;
            if (tapFraction(0.10f, 0.94f)) {
                store.touch();
                step(t, "HOME TAP", "Tapped Kotak's Home position and waiting for the Home screen.");
                return;
            }
        }

        if (backRecoveryCount < MAX_BACK_RECOVERY) {
            boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
            backRecoveryCount++;
            if (ok) {
                store.touch();
                step(t, "BACK " + backRecoveryCount, "Kotak opened on a previous screen. Backing out safely toward Home.");
                return;
            }
        }

        // After several safe Back actions, the app should normally be at Home. If Compose still hides
        // its nodes, try the visual Home search location once. This is deliberately limited to one tap.
        if (!searchGestureAttempted && backRecoveryCount >= 2) {
            searchGestureAttempted = true;
            if (tapFraction(0.36f, 0.085f)) {
                store.touch();
                step(t, "SEARCH FALLBACK", "Tried the Kotak Home search position after navigation recovery.");
                return;
            }
        }

        step(t, "WAITING", "Kotak is open, but its Home/Search controls are not exposed yet. Retrying safely.");
    }

    private boolean setSymbol(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        AccessibilityNodeInfo edit = findSearchEditable(root);
        if (edit == null) return false;
        String symbol = cleanSymbol(t.symbol);
        if (symbol.isEmpty()) return false;
        if (!setText(edit, symbol)) return false;
        store.setState(KotakOrderAutomationStore.STATE_SYMBOL_TYPED);
        step(t, "SYMBOL ENTERED", "Searching Kotak for " + symbol + ".");
        return true;
    }

    private void finishAtOrderPage(AccessibilityNodeInfo root, TradeTicket t, KotakOrderAutomationStore store) {
        boolean intradaySelected = false;
        AccessibilityNodeInfo product = findExactClickable(root, "Intraday", "MIS");
        if (product == null) product = findClickableContaining(root, "intraday", "mis");
        if (product != null) intradaySelected = safeClick(product);

        // IMPORTANT: stop here. v3.9 intentionally does not fill Quantity, Market/Limit, Price,
        // Target, SL, or touch the final broker confirmation.
        store.clear();
        if (intradaySelected) {
            step(t, "ORDER PAGE READY", "Kotak order page is open for " + cleanSymbol(t.symbol)
                    + " • " + t.side + " • Intraday/MIS selected. Review the page yourself. Nothing was submitted.");
        } else {
            step(t, "ORDER PAGE OPEN", "Kotak order page is open for " + cleanSymbol(t.symbol)
                    + " • " + t.side + ". Intraday/MIS was not exposed clearly, so select it manually. Nothing was submitted.");
        }
    }

    private AccessibilityNodeInfo findSearchEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo byKeyword = findEditableByKeyword(root,
                "search shares", "search stocks", "search scrip", "search here", "search");
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
            if (containsKeyword(nodeMeta(e), keywords)) return e;
        }
        return null;
    }

    private AccessibilityNodeInfo findSymbolResult(AccessibilityNodeInfo root, TradeTicket t) {
        String wanted = cleanSymbol(t == null ? null : t.symbol);
        String alt = cleanSymbol(t == null ? null : t.tradingSymbol);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);

        for (AccessibilityNodeInfo n : nodes) {
            if (isEditable(n) || clickableAncestor(n) == null) continue;
            String visible = visibleText(n).toUpperCase(Locale.ROOT);
            if (visible.isEmpty()) continue;

            String compact = cleanSymbol(visible);
            if ((!wanted.isEmpty() && wanted.equals(compact)) || (!alt.isEmpty() && alt.equals(compact))) {
                return n;
            }

            String[] parts = visible.split("[^A-Z0-9&]+");
            for (String part : parts) {
                String token = cleanSymbol(part);
                if ((!wanted.isEmpty() && wanted.equals(token)) || (!alt.isEmpty() && alt.equals(token))) {
                    return n;
                }
            }
        }
        return null;
    }

    private boolean containsIntendedSymbol(AccessibilityNodeInfo root, TradeTicket t) {
        String wanted = cleanSymbol(t == null ? null : t.symbol);
        String alt = cleanSymbol(t == null ? null : t.tradingSymbol);
        if (wanted.isEmpty() && alt.isEmpty()) return false;

        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (isEditable(n)) continue;
            String visible = visibleText(n).toUpperCase(Locale.ROOT);
            if (visible.isEmpty()) continue;
            String compact = cleanSymbol(visible);
            if ((!wanted.isEmpty() && wanted.equals(compact)) || (!alt.isEmpty() && alt.equals(compact))) return true;
            String[] parts = visible.split("[^A-Z0-9&]+");
            for (String part : parts) {
                String token = cleanSymbol(part);
                if ((!wanted.isEmpty() && wanted.equals(token)) || (!alt.isEmpty() && alt.equals(token))) return true;
            }
        }
        return false;
    }

    private boolean looksLikeOrderForm(AccessibilityNodeInfo root) {
        int score = 0;
        if (containsAnyText(root, "intraday", "mis", "delivery")) score++;
        if (containsAnyText(root, "quantity", "qty")) score++;
        if (containsAnyText(root, "market", "limit", "order type")) score++;
        if (containsAnyText(root, "margin required", "available margin")) score++;
        return score >= 2;
    }

    private boolean looksLikeHome(AccessibilityNodeInfo root) {
        int score = 0;
        if (containsAnyText(root, "investment summary")) score++;
        if (containsAnyText(root, "market indices", "market movers")) score++;
        if (containsAnyText(root, "stocks", "f&o", "mutual funds")) score++;
        if (containsAnyText(root, "funds")) score++;
        return score >= 2;
    }

    private boolean hasBottomNavigation(AccessibilityNodeInfo root) {
        int score = 0;
        if (containsAnyText(root, "home")) score++;
        if (containsAnyText(root, "watchlist")) score++;
        if (containsAnyText(root, "orders")) score++;
        if (containsAnyText(root, "portfolio")) score++;
        if (containsAnyText(root, "invest")) score++;
        return score >= 2;
    }

    private AccessibilityNodeInfo findClickableContaining(AccessibilityNodeInfo root, String... needles) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            if (containsKeyword(nodeMeta(n), needles) && clickableAncestor(n) != null) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo findExactClickable(AccessibilityNodeInfo root, String... values) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo n : nodes) {
            String visible = visibleText(n);
            for (String v : values) {
                if (v != null && !v.trim().isEmpty()
                        && visible.equalsIgnoreCase(v.trim())
                        && clickableAncestor(n) != null) {
                    return n;
                }
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
        for (int i = 0; n != null && i < 7; i++) {
            if (n.isEnabled()) {
                if (n.isClickable()) return n;
                for (AccessibilityNodeInfo.AccessibilityAction action : n.getActionList()) {
                    if (action != null && action.getId() == AccessibilityNodeInfo.ACTION_CLICK) return n;
                }
            }
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

    private boolean tapFraction(float xFraction, float yFraction) {
        try {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            float x = Math.max(1f, Math.min(width - 2f, width * xFraction));
            float y = Math.max(1f, Math.min(height - 2f, height * yFraction));
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void step(TradeTicket t, String key, String message) {
        String stable = key == null ? "" : key;
        if (stable.equals(lastStep)) return;
        lastStep = stable;
        String symbol = t == null ? "Kotak" : cleanSymbol(t.symbol);
        if (symbol.isEmpty()) symbol = "Kotak";
        ApprovalNotifier.showStatus(this, symbol + " — " + stable, message);
    }

    private static String safe(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.trim().isEmpty() ? "Unknown error" : m;
    }

    @Override
    public void onInterrupt() {
        // Do nothing. No broker action is attempted during an interruption.
    }

    @Override
    public void onDestroy() {
        pollHandler.removeCallbacksAndMessages(null);
        pollScheduled = false;
        super.onDestroy();
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
