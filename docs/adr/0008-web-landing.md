# 0008 Web: static landing page only
**Decision.** QR codes point to one stable HTTPS URL per book/edition on a static page: what the app is, "Get it on Google Play", nothing else. The QR identifies the edition; it carries no code, token or secret. No login, no student content, no backend calls.
**Consequences.** Minimal attack surface. Android App Links can open the app directly when installed. A student web client is out of scope for release 1.
