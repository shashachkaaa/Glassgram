"""Gives and takes the supporter badge by editing glassgram-api/badges.json in the repository.

The app reads that file from master on every start, so a commit there is all it takes. The
APK workflow ignores glassgram-api/**, so these commits do not start a build.
"""

import asyncio
import base64
import json
import logging

import aiohttp

log = logging.getLogger(__name__)


class BadgeError(Exception):
    pass


class Badges:
    def __init__(self, session: aiohttp.ClientSession, token: str, repo: str, branch: str, path: str, badge_id: str,
                 api_url: str = "https://api.github.com"):
        self._session = session
        self._url = f"{api_url}/repos/{repo}/contents/{path}"
        self._branch = branch
        self._badge_id = badge_id
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        # One edit at a time from this process; GitHub's sha check covers edits from elsewhere
        self._lock = asyncio.Lock()

    async def grant(self, user_id: int, reason: str) -> bool:
        """Gives the badge; False when the user already has it or another badge (that one is kept)."""

        def change(peers: dict) -> bool:
            key = str(user_id)
            if key in peers:
                return False
            peers[key] = self._badge_id
            return True

        return await self._edit(change, f"Badges: {self._badge_id} for {user_id} ({reason})")

    async def revoke(self, user_id: int, reason: str) -> bool:
        """Takes the badge back; a different badge the user has is left alone."""

        def change(peers: dict) -> bool:
            key = str(user_id)
            value = peers.get(key)
            badge = value.get("badge") if isinstance(value, dict) else value
            if badge != self._badge_id:
                return False
            del peers[key]
            return True

        return await self._edit(change, f"Badges: no {self._badge_id} for {user_id} ({reason})")

    async def has_badge(self, user_id: int) -> bool:
        data, _ = await self._read()
        value = data.get("peers", {}).get(str(user_id))
        badge = value.get("badge") if isinstance(value, dict) else value
        return badge == self._badge_id

    async def _read(self) -> tuple[dict, str]:
        async with self._session.get(self._url, params={"ref": self._branch}, headers=self._headers) as response:
            if response.status != 200:
                raise BadgeError(f"GitHub read: HTTP {response.status}: {(await response.text())[:300]}")
            file = await response.json()
        content = base64.b64decode(file["content"]).decode("utf-8")
        return json.loads(content), file["sha"]

    async def _edit(self, change, message: str) -> bool:
        async with self._lock:
            for attempt in range(5):
                data, sha = await self._read()
                if not change(data.setdefault("peers", {})):
                    return False
                content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
                body = {
                    "message": message,
                    "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
                    "sha": sha,
                    "branch": self._branch,
                }
                async with self._session.put(self._url, json=body, headers=self._headers) as response:
                    if response.status in (200, 201):
                        log.info("%s", message)
                        return True
                    text = await response.text()
                # 409/422: the file changed since it was read (an edit by hand); read it again
                if response.status in (409, 422) and attempt < 4:
                    log.warning("badges.json changed meanwhile, retrying: %s", text[:200])
                    await asyncio.sleep(1 + attempt)
                    continue
                raise BadgeError(f"GitHub write: HTTP {response.status}: {text[:300]}")
        return False
