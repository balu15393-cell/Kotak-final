# Multyfi → Kotak Neo No-Static-IP v3.6

This build follows Multyfi notifications only and uses Kotak Neo for MIS intraday order preparation.

## v3.6 quantity rule

For NEW ENTRIES, the quantity is **not** taken from any capital value typed into this app. After the user taps APPROVE, the Accessibility helper navigates to the Kotak Neo stock order form, selects Intraday/MIS, temporarily enters quantity 1, reads Kotak's live **Available Margin** and **Margin Required**, calculates a candidate quantity using 99% of live available margin, fills that quantity, re-checks Kotak's updated margin requirement, and reduces the quantity if necessary. It stops if the live margin values cannot be read safely.

For EXITS, quantity is the exact remaining live Kotak MIS position quantity.

The helper never presses Kotak's final Place/Buy/Sell/Swipe/Confirm action. The final broker confirmation remains manual.

Multyfi target and stop-loss remain exactly as advised.


## v3.7 UI-test fix
The in-app Test Approval flow now arms the same Kotak Accessibility navigation used by live Multyfi tickets. Test mode may navigate to and fill the Kotak MIS order form for validation, but the helper never presses the final Place/Buy/Sell/Swipe/Confirm control.
