# @glassGramBot — donations through Platega and the Supporter badge

The bot takes donations through [Platega](https://docs.platega.io) and gives everyone who paid the
**Supporter** badge in Glassgram. The badge is an entry in
[`glassgram-api/badges.json`](../glassgram-api/badges.json): the bot adds the payer's Telegram id
there through the GitHub API, and the app shows the badge the next time it is opened.

## How it works

1. `/start` (or `/donate`): the user picks an amount (or types their own).
2. The bot creates a payment link (`POST /v2/transaction/process`, no fixed method: SBP, a card or
   crypto on Platega's page) and stores it in SQLite.
3. Platega posts the payment status to `<PUBLIC_URL>/platega/callback`. The bot checks the
   `X-MerchantId` / `X-Secret` headers, then **asks Platega for the transaction itself**
   (`GET /transaction/{id}`) and never trusts the callback's body.
4. `CONFIRMED` (and the full amount paid) → the user's id is added to `peers` as `"supporter"`
   in `badges.json` (one commit to `master`; the APK workflow ignores this folder), and the user is
   thanked. `CHARGEBACKED` → the badge is taken back unless the user has another paid donation.

If a callback is lost, the user's **"Я оплатил — проверить"** button and a background check every
minute (for two hours after the link is created) settle the payment the same way. Each payment
gives the badge once; a user who already has another badge (developer, owner...) keeps it.

Admins (`ADMIN_IDS`): `/grant <id>`, `/revoke <id>`, `/payments` (the last 20). Everyone: `/badge`.

## Setup

1. **Platega**: take the merchant id and the API key from the account settings, and set the
   callback URL to `https://<your domain>/platega/callback`. Platega calls only HTTPS addresses with a
   valid certificate (not an IP, not localhost).
2. **GitHub**: a fine-grained token for `shashachkaaa/Glassgram` only, with *Contents: Read and write*.
3. **Server** (Python 3.10+):

   ```bash
   cd glassgram-bot
   python3 -m venv .venv && . .venv/bin/activate
   pip install -r requirements.txt
   cp .env.example .env   # fill it in
   python bot.py
   ```

   The callback server listens on `WEB_HOST:WEB_PORT` (127.0.0.1:8080 by default); put it behind a
   reverse proxy with a certificate, e.g. Caddy:

   ```
   bot.example.com {
       reverse_proxy /platega/* 127.0.0.1:8080
   }
   ```

4. Keep it running, e.g. with systemd:

   ```ini
   [Unit]
   Description=glassGramBot
   After=network-online.target

   [Service]
   WorkingDirectory=/opt/glassgram/glassgram-bot
   ExecStart=/opt/glassgram/glassgram-bot/.venv/bin/python bot.py
   Restart=always

   [Install]
   WantedBy=multi-user.target
   ```

The bot gets Telegram updates by long polling, so only the Platega callback needs a public address.
`.env` and the database (`glassgram_bot.sqlite3`) stay on the server; back the database up, it is
the record of who paid.
