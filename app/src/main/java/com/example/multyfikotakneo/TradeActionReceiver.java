package com.example.multyfikotakneo;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TradeActionReceiver extends BroadcastReceiver {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        String ticketId = intent.getStringExtra(ApprovalNotifier.EXTRA_TICKET_ID);
        if (ticketId == null) return;
        TradeStore store = new TradeStore(context);

        if (ApprovalNotifier.ACTION_REJECT.equals(intent.getAction())) {
            try {
                TradeTicket t = store.loadTicket(ticketId);
                if (t == null) return;
                boolean exit = "EXIT".equals(t.ticketKind);
                ApprovalNotifier.cancelTicket(context, t);
                store.appendLog(exit ? "EXIT_IGNORED" : "ENTRY_REJECTED", t,
                        exit ? "User ignored the prepared exit ticket." : "User rejected the prepared entry ticket.");
                store.deleteTicket(ticketId);
            } catch (Exception e) {
                ApprovalNotifier.showStatus(context, "Trade assistant", "Could not reject ticket: " + safe(e));
            }
            return;
        }

        if (!ApprovalNotifier.ACTION_APPROVE.equals(intent.getAction())) return;
        if (!store.claimExecution(ticketId)) {
            ApprovalNotifier.showStatus(context, "Kotak Neo ticket", "This ticket is already being processed.");
            return;
        }

        final PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                TradeTicket original = store.loadTicket(ticketId);
                if (original == null) return;
                boolean exit = "EXIT".equals(original.ticketKind);

                ApprovalNotifier.showStatus(context,
                        exit ? original.symbol + " — refreshing exit" : original.symbol + " — refreshing entry",
                        "Checking latest Kotak Neo LTP/position/funds before opening Kotak Neo…");

                KotakNeoApprovalPreflight.Result refreshed = KotakNeoApprovalPreflight.refresh(context, original);
                if (refreshed.noAction) {
                    store.appendLog(exit ? "EXIT_NO_ACTION" : "ENTRY_NO_ACTION", original, refreshed.message);
                    ApprovalNotifier.cancelTicket(context, original);
                    ApprovalNotifier.showStatus(context, original.symbol + " — no action needed", refreshed.message);
                    store.deleteTicket(ticketId);
                    return;
                }

                TradeTicket t = refreshed.ticket;
                ClipboardManager cb = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText(exit ? "Prepared MIS exit" : "Prepared MIS order", t.displayText()));

                boolean accessibilityReady = KotakOrderAccessibilityService.isEnabled(context);
                boolean automationArmed = false;
                if (accessibilityReady) {
                    try {
                        new KotakOrderAutomationStore(context).arm(t);
                        automationArmed = true;
                    } catch (Exception armError) {
                        ApprovalNotifier.showStatus(context, t.symbol + " — accessibility helper not armed", safe(armError));
                    }
                }

                store.appendLog(exit ? "EXIT_APPROVED_OPENED_KOTAK" : "ENTRY_APPROVED_OPENED_KOTAK", t,
                        refreshed.message + (automationArmed
                                ? " Accessibility helper armed to prepare the Kotak MIS order page. Final broker confirmation remains manual."
                                : " Ticket copied and Kotak Neo opened. Final broker confirmation remains manual."));
                ApprovalNotifier.cancelTicket(context, t);

                if (t.dryRun && automationArmed) {
                    ApprovalNotifier.showStatus(context,
                            exit ? t.symbol + " — UI TEST EXIT PREP" : t.symbol + " — UI TEST ORDER PREP",
                            "TEST MODE: opening Kotak Neo and exercising the Accessibility navigation/fill flow. The helper will stop before Kotak's final confirmation and will not submit an order.");
                } else if (t.dryRun) {
                    ApprovalNotifier.showStatus(context,
                            exit ? t.symbol + " — TEST EXIT READY" : t.symbol + " — TEST ORDER READY",
                            "TEST MODE: Accessibility helper is OFF, so Kotak will only open. Enable Multyfi Kotak order helper in Android Accessibility to test order-page navigation.");
                } else if (automationArmed) {
                    ApprovalNotifier.showStatus(context,
                            exit ? t.symbol + " — PREPARING EXIT IN KOTAK" : t.symbol + " — PREPARING ORDER IN KOTAK",
                            "Opening Kotak Neo. The accessibility helper will try to reach the matching MIS order page and fill safe fields, then stop before Kotak's final confirmation.");
                } else {
                    ApprovalNotifier.showStatus(context,
                            exit ? t.symbol + " — EXIT READY" : t.symbol + " — ORDER READY",
                            accessibilityReady
                                    ? "Latest ticket copied. Kotak Neo will open, but the order helper could not be armed. Continue manually."
                                    : "Accessibility order helper is OFF. Latest ticket copied; Kotak Neo will open for manual MIS entry.");
                }

                openKotakNeo(context);
                store.deleteTicket(ticketId);
            } catch (Exception e) {
                store.releaseExecution(ticketId);
                ApprovalNotifier.showStatus(context, "Kotak Neo ticket blocked", safe(e));
            } finally {
                pending.finish();
            }
        });
    }

    private static void openKotakNeo(Context context) {
        try {
            if (!KotakNeoAppLauncher.open(context)) {
                ApprovalNotifier.showStatus(context, "Kotak Neo app not detected",
                        "Could not resolve the installed Kotak Neo launcher. Open Kotak Neo manually and confirm the prepared MIS ticket.");
            }
        } catch (Exception e) {
            ApprovalNotifier.showStatus(context, "Could not open Kotak Neo", safe(e));
        }
    }

    private static String safe(Exception e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.trim().isEmpty() ? "Unknown error" : m;
    }
}
