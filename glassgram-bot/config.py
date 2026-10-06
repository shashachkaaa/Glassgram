"""Settings, read from the environment (and a .env file next to this one, if there is one)."""

import os
from dataclasses import dataclass
from pathlib import Path


def _load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"{name} is not set (see .env.example)")
    return value


def _ints(value: str) -> list[int]:
    return [int(part) for part in value.replace(" ", "").split(",") if part]


@dataclass(frozen=True)
class Config:
    bot_token: str
    admin_ids: frozenset[int]

    platega_merchant_id: str
    platega_secret: str
    platega_base_url: str
    donate_amounts: list[int]
    donate_min: int
    donate_max: int

    public_url: str
    web_host: str
    web_port: int

    github_token: str
    github_repo: str
    github_branch: str
    badges_path: str
    badge_id: str

    db_path: str

    @property
    def callback_path(self) -> str:
        return "/platega/callback"


def load_config() -> Config:
    _load_dotenv(Path(__file__).with_name(".env"))
    return Config(
        bot_token=_required("BOT_TOKEN"),
        admin_ids=frozenset(_ints(os.environ.get("ADMIN_IDS", ""))),
        platega_merchant_id=_required("PLATEGA_MERCHANT_ID"),
        platega_secret=_required("PLATEGA_SECRET"),
        platega_base_url=os.environ.get("PLATEGA_BASE_URL", "https://app.platega.io").rstrip("/"),
        donate_amounts=_ints(os.environ.get("DONATE_AMOUNTS", "100,300,500,1000")),
        donate_min=int(os.environ.get("DONATE_MIN", "50")),
        donate_max=int(os.environ.get("DONATE_MAX", "100000")),
        public_url=_required("PUBLIC_URL").rstrip("/"),
        web_host=os.environ.get("WEB_HOST", "127.0.0.1"),
        web_port=int(os.environ.get("WEB_PORT", "8080")),
        github_token=_required("GITHUB_TOKEN"),
        github_repo=os.environ.get("GITHUB_REPO", "shashachkaaa/Glassgram"),
        github_branch=os.environ.get("GITHUB_BRANCH", "master"),
        badges_path=os.environ.get("BADGES_PATH", "glassgram-api/badges.json"),
        badge_id=os.environ.get("BADGE_ID", "supporter"),
        db_path=os.environ.get("DB_PATH", str(Path(__file__).with_name("glassgram_bot.sqlite3"))),
    )
