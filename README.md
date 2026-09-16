# Multyfi → Kotak Neo helper v3.8

This no-static-IP build prepares a Kotak Neo MIS order after one explicit approval.

v3.8 fixes the case where Kotak Neo opens but the Accessibility helper stays on the home page. Kotak can finish drawing its search/home UI without producing another useful accessibility event, so v3.8 polls the active Kotak window safely while the approved ticket is armed. It also accepts search-result rows where the symbol is shown together with NSE/company text.

The helper may navigate to the matching stock order form, choose MIS/Intraday, choose Market/Limit, and fill accessible fields. New-entry quantity is calculated from Kotak's live Available Margin and Margin Required when those values are exposed to Android Accessibility.

It deliberately never clicks the final broker Place/Buy/Sell/Swipe/Confirm action. Review and confirm the live order yourself inside Kotak Neo.
