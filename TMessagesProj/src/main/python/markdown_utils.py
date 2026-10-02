"""Telegram-style markdown parsing, kept for plugins written against older SDKs."""

from dataclasses import dataclass
from typing import Tuple

from extera_utils.text_formatting import Markdown, RawEntity, TLEntityType

__all__ = ["parse_markdown", "ParsedMessage", "RawEntity", "TLEntityType"]


@dataclass
class ParsedMessage:
    text: str
    entities: Tuple[RawEntity, ...]


def parse_markdown(markdown: str) -> ParsedMessage:
    parsed = Markdown.parse(markdown)
    return ParsedMessage(parsed["message"], tuple(parsed["entities"]))
