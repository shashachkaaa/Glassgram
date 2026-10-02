# Glassgram badges API

Glassgram shows a badge next to the names of the channels, chats, users and bots listed in
[`badges.json`](badges.json): in the chat header, in the profile and in the chat list.
Tapping the name in the profile shows the badge's text.

The app downloads the file from

    https://raw.githubusercontent.com/shashachkaaa/Telegram/master/glassgram-api/badges.json

at start and then at most every 3 hours (when the app comes back to the screen), and keeps the
last copy for offline starts. To hand out a badge, edit the file and push it to `master`; users
see it after their next refresh. To serve the list from your own server instead, change
`GlassgramBadges.API_URL` and return the same JSON there.

## Format

```json
{
  "version": 1,
  "badges": {
    "glassgram": {
      "icon": "arrow",
      "color": "#2AABEE",
      "text": { "en": "Official Glassgram source.", "ru": "Официальный источник Glassgram." }
    }
  },
  "peers": {
    "-1001234567890": "glassgram",
    "123456789": "glassgram"
  },
  "usernames": {
    "glassgramdev": "glassgram"
  }
}
```

- `badges` — the badge kinds, by any id you like.
  - `icon` — the glyph on the badge: `arrow` (the Glassgram arrow), `check`, `star`, `heart`,
    `bolt`, `crown`, `code`, `shield`.
  - `color` — the badge color, `#RRGGBB` or `#AARRGGBB`.
  - `text` — what tapping the badge shows, per language code; `en` is the fallback. A plain
    string works too.
- `peers` — who gets which badge, by Telegram id. Users and bots use their id; channels and
  supergroups can be written the Bot API way (`-100…`) or as `-` and the channel id; basic groups
  as `-` and the chat id. Glassgram shows ids in profiles (Glassgram Preferences > General > Show ID).
- `usernames` — the same by public username, without `@`. Handy, but a peer keeps the badge only
  while it keeps the username; ids are safer.
