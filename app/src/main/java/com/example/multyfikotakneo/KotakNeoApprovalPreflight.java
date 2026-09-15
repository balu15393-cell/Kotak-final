package com.example.multyfikotakneo;

import android.content.Context;

import java.util.Locale;

/**
 * Re-validates a prepared ticket immediately before opening Kotak Neo.
 * This class never places, modifies, or cancels a broker order.
 */
public final class KotakNeoApprovalPreflight {
    private KotakNeoApprovalPreflight() {}

    public static final class Result {
        public final TradeTicket ticket;
        public final boolean noAction;
        public final String message;

        Result(TradeTicket ticket, boolean noAction, String message) {
            this.ticket = ticket;
            this.noAction = noAction;
            this.message = message;
        }
    }

    public static Result refresh(Context context, TradeTicket ticket) throws Exception {
        if (ticket == null) throw new IllegalArgumentException("Trade ticket is missing.");
        if (ticket.dryRun) {
            return new Result(ticket, false, "Test ticket refreshed locally. No broker order can be sent by this app.");
        }

        KotakNeoCredentials credentials = new KotakNeoCredentialStore(context).load();
        KotakNeoSession session = new KotakNeoSessionStore(context).load();
        if (credentials == null || !credentials.isComplete()) {
            throw new IllegalStateException("Kotak Neo credentials are missing.");
        }
        if (session == null || !session.isComplete()) {
            throw new IllegalStateException("Kotak Neo API login is required. Log in with current TOTP + MPIN.");
        }

        if ("EXIT".equals(ticket.ticketKind)) {
            KotakNeoPortfolioClient.Position position = KotakNeoPortfolioClient.fetchMisPosition(session, ticket.symbol);
            if (position == null || !position.isOpen()) {
                return new Result(ticket, true,
                        "No open Kotak Neo MIS position remains. If you already closed it manually, no further action is needed.");
            }
            String exitSide = position.exitSide();
            if (KotakNeoOrderClient.hasActiveExitOrder(session, ticket.symbol, exitSide)) {
                return new Result(ticket, true,
                        "An active Kotak Neo exit order already exists for this symbol. Duplicate exit blocked.");
            }

            ticket.originalSide = position.originalSide();
            ticket.side = exitSide;
            ticket.quantity = position.remainingQuantity();
            ticket.orderType = "MARKET";
            ticket.orderPrice = null;
            ticket.instrumentToken = position.instrumentToken;
            ticket.tradingSymbol = position.tradingSymbol;
            ticket.advisoryEntry = position.netPrice > 0
                    ? String.format(Locale.ROOT, "Kotak Neo MIS avg ₹%.2f", position.netPrice)
                    : "Kotak Neo MIS position";
            try {
                String token = clean(position.instrumentToken);
                if (token.isEmpty()) {
                    token = new KotakNeoScripResolver(context)
                            .resolveNseEquity(credentials.consumerKey, session, ticket.symbol).token;
                }
                ticket.ltp = KotakNeoLtpClient.fetch(credentials.consumerKey, session, token);
            } catch (Exception ignored) {
                // LTP is informational for exits. Position state is the critical check.
            }
            ticket.reason = ticket.reason + " Final preflight verified remaining MIS quantity before opening Kotak Neo.";
            return new Result(ticket, false, "Exit refreshed from the live Kotak Neo MIS position.");
        }

        KotakNeoPortfolioClient.Position existing = KotakNeoPortfolioClient.fetchMisPosition(session, ticket.symbol);
        if (existing != null && existing.isOpen()) {
            throw new IllegalStateException("An open Kotak Neo MIS position already exists for " + ticket.symbol + ". New entry blocked.");
        }

        String token = clean(ticket.instrumentToken);
        String tradingSymbol = clean(ticket.tradingSymbol);
        if (token.isEmpty() || tradingSymbol.isEmpty()) {
            KotakNeoScripResolver.Instrument instrument = new KotakNeoScripResolver(context)
                    .resolveNseEquity(credentials.consumerKey, session, ticket.symbol);
            token = instrument.token;
            tradingSymbol = instrument.tradingSymbol;
            ticket.instrumentToken = token;
            ticket.tradingSymbol = tradingSymbol;
        }

        double ltp = KotakNeoLtpClient.fetch(credentials.consumerKey, session, token);
        ticket.ltp = ltp;

        // Target and stop-loss are owned by the Multyfi advisory. Never recalculate or replace them.
        Double advisoryTarget = ticket.target;
        Double advisoryStopLoss = ticket.stopLoss;
        if (advisoryTarget == null || advisoryTarget <= 0) {
            throw new IllegalStateException("Multyfi advisory target is missing. Entry blocked for manual review.");
        }
        if (advisoryStopLoss == null || advisoryStopLoss <= 0) {
            throw new IllegalStateException("Multyfi advisory stop-loss is missing. Entry blocked for manual review.");
        }

        chooseEntry(ticket, ltp);
        ticket.target = advisoryTarget;     // preserve Multyfi target exactly as received
        ticket.stopLoss = advisoryStopLoss; // preserve Multyfi SL exactly as received

        ticket.capital = 0;
        ticket.quantity = 1; // temporary probe quantity; Accessibility replaces it from Kotak's live MIS margin values.
        ticket.utilizationPct = 100.0;
        ticket.calculateQtyFromKotakMargin = true;
        ticket.reason = ticket.reason + String.format(Locale.ROOT,
                " Final preflight: latest LTP ₹%.2f. Final entry quantity is deferred to Kotak's live MIS margin on the order page.", ltp);
        return new Result(ticket, false, "Entry refreshed with latest LTP/order type. Quantity will be calculated from Kotak live MIS margin on the order page.");
    }


    private static void chooseEntry(TradeTicket t, double ltp) {
        if (ltp <= 0) throw new IllegalStateException("Kotak Neo LTP is invalid at approval time.");
        if (t.entryLow <= 0 || t.entryHigh <= 0) {
            if ("LIMIT".equals(t.orderType) && t.orderPrice != null) return;
            t.orderType = "MARKET";
            t.orderPrice = null;
            return;
        }

        double tol = t.tolerancePct / 100.0;
        double lowOk = t.entryLow * (1 - tol);
        double highOk = t.entryHigh * (1 + tol);
        if ("BUY".equals(t.side)) {
            if (ltp >= lowOk && ltp <= highOk) {
                t.orderType = "MARKET";
                t.orderPrice = null;
                t.reason = "Latest LTP is within the advisory BUY range/tolerance.";
            } else if (ltp > highOk) {
                t.orderType = "LIMIT";
                t.orderPrice = t.entryHigh;
                t.reason = "Latest LTP moved above the advisory BUY entry; do not chase. Use LIMIT at advisory maximum.";
            } else {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Latest LTP ₹%.2f moved materially below the advisory BUY range. Entry blocked for manual review.", ltp));
            }
        } else {
            if (ltp >= lowOk && ltp <= highOk) {
                t.orderType = "MARKET";
                t.orderPrice = null;
                t.reason = "Latest LTP is within the advisory SELL range/tolerance.";
            } else if (ltp < lowOk) {
                t.orderType = "LIMIT";
                t.orderPrice = t.entryLow;
                t.reason = "Latest LTP moved below the advisory SELL entry; use LIMIT at advisory minimum.";
            } else {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "Latest LTP ₹%.2f moved materially above the advisory SELL range. Entry blocked for manual review.", ltp));
            }
        }
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
