"""HTML and Markdown parsing into Telegram message entities.

Offsets and lengths are counted in UTF-16 code units, as Telegram expects:
the text is parsed with astral characters split into surrogate pairs.
"""

import html
import re
import struct
from dataclasses import dataclass
from enum import Enum
from html.parser import HTMLParser
from typing import Any, List, Optional

_ASTRAL_RE = re.compile(r"[\U00010000-\U0010FFFF]")


def add_surrogates(text: str) -> str:
    return _ASTRAL_RE.sub(
        lambda m: "".join(chr(i) for i in struct.unpack("<HH", m.group().encode("utf-16le"))),
        text,
    )


def remove_surrogates(text: str) -> str:
    return text.encode("utf-16", "surrogatepass").decode("utf-16")


class TLEntityType(Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"

    @classmethod
    def from_(cls, entity):
        name = entity.getClass().getSimpleName()
        for key, value in _TLRPC_NAMES.items():
            if value == name:
                return key
        return None


_TLRPC_NAMES = {
    TLEntityType.CODE: "TL_messageEntityCode",
    TLEntityType.PRE: "TL_messageEntityPre",
    TLEntityType.STRIKETHROUGH: "TL_messageEntityStrike",
    TLEntityType.TEXT_LINK: "TL_messageEntityTextUrl",
    TLEntityType.BOLD: "TL_messageEntityBold",
    TLEntityType.ITALIC: "TL_messageEntityItalic",
    TLEntityType.UNDERLINE: "TL_messageEntityUnderline",
    TLEntityType.SPOILER: "TL_messageEntitySpoiler",
    TLEntityType.CUSTOM_EMOJI: "TL_messageEntityCustomEmoji",
    TLEntityType.BLOCKQUOTE: "TL_messageEntityBlockquote",
}


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: Optional[str] = None
    url: Optional[str] = None
    document_id: Optional[int] = None
    collapsed: Optional[bool] = None

    @property
    def TLRPC_ENTITIES_MAP(self):
        return _TLRPC_NAMES

    def to_tlrpc_object(self):
        from java import jclass

        cls = jclass("org.telegram.tgnet.TLRPC$" + _TLRPC_NAMES[self.type])
        entity = cls()
        entity.offset = int(self.offset)
        entity.length = int(self.length)
        if self.type == TLEntityType.PRE:
            entity.language = self.language or ""
        elif self.type == TLEntityType.TEXT_LINK:
            entity.url = self.url or ""
        elif self.type == TLEntityType.CUSTOM_EMOJI:
            entity.document_id = int(self.document_id or 0)
        elif self.type == TLEntityType.BLOCKQUOTE:
            entity.collapsed = bool(self.collapsed)
            if self.collapsed:
                entity.flags |= 1
        return entity


_TAGS = {
    "b": TLEntityType.BOLD,
    "strong": TLEntityType.BOLD,
    "i": TLEntityType.ITALIC,
    "em": TLEntityType.ITALIC,
    "u": TLEntityType.UNDERLINE,
    "ins": TLEntityType.UNDERLINE,
    "s": TLEntityType.STRIKETHROUGH,
    "del": TLEntityType.STRIKETHROUGH,
    "strike": TLEntityType.STRIKETHROUGH,
    "code": TLEntityType.CODE,
    "pre": TLEntityType.PRE,
    "spoiler": TLEntityType.SPOILER,
    "tg-spoiler": TLEntityType.SPOILER,
    "a": TLEntityType.TEXT_LINK,
    "blockquote": TLEntityType.BLOCKQUOTE,
    "tg-emoji": TLEntityType.CUSTOM_EMOJI,
    "emoji": TLEntityType.CUSTOM_EMOJI,
}


class Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = ""
        self.entities = []
        self.tag_entities = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "br":
            self.text += "\n"
            return
        if tag == "span" and attrs.get("class") == "tg-spoiler":
            tag = "spoiler"
        entity_type = _TAGS.get(tag)
        if entity_type is None:
            return
        entity = RawEntity(entity_type, len(self.text), 0)
        if entity_type == TLEntityType.TEXT_LINK:
            entity.url = attrs.get("href", "")
        elif entity_type == TLEntityType.PRE:
            entity.language = attrs.get("language", "")
        elif entity_type == TLEntityType.CUSTOM_EMOJI:
            try:
                entity.document_id = int(attrs.get("emoji-id") or attrs.get("id") or 0)
            except ValueError:
                entity.document_id = 0
        elif entity_type == TLEntityType.BLOCKQUOTE:
            entity.collapsed = "expandable" in attrs
        elif entity_type == TLEntityType.CODE:
            # <pre><code class="language-x"> sets the language of the enclosing block
            lang = attrs.get("class", "")
            if lang.startswith("language-") and self.tag_entities.get("pre"):
                self.tag_entities["pre"][-1].language = lang[len("language-"):]
                self.tag_entities.setdefault(tag, []).append(None)
                return
        self.tag_entities.setdefault(tag, []).append(entity)

    def handle_data(self, data):
        self.text += add_surrogates(data)

    def handle_endtag(self, tag):
        if tag == "span":
            tag = "spoiler" if self.tag_entities.get("spoiler") else tag
        stack = self.tag_entities.get(tag)
        if not stack:
            return
        entity = stack.pop()
        if entity is None:
            return
        entity.length = len(self.text) - entity.offset
        if entity.length > 0:
            self.entities.append(entity)


class HTML:
    @staticmethod
    def parse(text: str) -> dict:
        parser = Parser()
        parser.feed(text or "")
        parser.close()
        entities = sorted(parser.entities, key=lambda e: e.offset)
        return {"message": remove_surrogates(parser.text), "entities": entities}

    @staticmethod
    def unparse(text: str, entities: list) -> str:
        text = add_surrogates(text or "")
        inserts = []
        for e in entities or []:
            if not isinstance(e, RawEntity):
                e = _from_tlrpc(e)
                if e is None:
                    continue
            start, end = e.offset, e.offset + e.length
            if e.type == TLEntityType.TEXT_LINK:
                open_tag, close_tag = '<a href="%s">' % html.escape(e.url or "", quote=True), "</a>"
            elif e.type == TLEntityType.PRE:
                open_tag = '<pre language="%s">' % html.escape(e.language or "") if e.language else "<pre>"
                close_tag = "</pre>"
            elif e.type == TLEntityType.CUSTOM_EMOJI:
                open_tag, close_tag = '<tg-emoji emoji-id="%d">' % (e.document_id or 0), "</tg-emoji>"
            elif e.type == TLEntityType.BLOCKQUOTE:
                open_tag = "<blockquote expandable>" if e.collapsed else "<blockquote>"
                close_tag = "</blockquote>"
            else:
                tag = {
                    TLEntityType.BOLD: "b", TLEntityType.ITALIC: "i", TLEntityType.UNDERLINE: "u",
                    TLEntityType.STRIKETHROUGH: "s", TLEntityType.CODE: "code", TLEntityType.SPOILER: "tg-spoiler",
                }[e.type]
                open_tag, close_tag = "<%s>" % tag, "</%s>" % tag
            inserts.append((start, 1, open_tag))
            inserts.append((end, 0, close_tag))
        inserts.sort(key=lambda x: (x[0], x[1]))
        out = []
        last = 0
        for pos, _, tag in inserts:
            out.append(html.escape(text[last:pos], quote=False))
            out.append(tag)
            last = pos
        out.append(html.escape(text[last:], quote=False))
        return remove_surrogates("".join(out))


def _from_tlrpc(entity) -> Optional[RawEntity]:
    entity_type = TLEntityType.from_(entity)
    if entity_type is None:
        return None
    raw = RawEntity(entity_type, entity.offset, entity.length)
    if entity_type == TLEntityType.TEXT_LINK:
        raw.url = entity.url
    elif entity_type == TLEntityType.PRE:
        raw.language = entity.language
    elif entity_type == TLEntityType.CUSTOM_EMOJI:
        raw.document_id = entity.document_id
    elif entity_type == TLEntityType.BLOCKQUOTE:
        raw.collapsed = entity.collapsed
    return raw


BOLD_DELIM = "**"
ITALIC_DELIM = "__"
UNDERLINE_DELIM = "--"
STRIKE_DELIM = "~~"
SPOILER_DELIM = "||"
CODE_DELIM = "`"
PRE_DELIM = "```"
BLOCKQUOTE_DELIM = ">"
BLOCKQUOTE_EXPANDABLE_DELIM = "**>"
BLOCKQUOTE_EXPANDABLE_END_DELIM = "||"
MARKDOWN_ESCAPABLE_CHARS = "\\`*_~|[]()-!>"
ESCAPED_MARKDOWN_RE = re.compile(r"\\([%s])" % re.escape(MARKDOWN_ESCAPABLE_CHARS))
MARKDOWN_RE = re.compile(r"(```|\*\*|__|--|~~|\|\||`|!?\[)")
OPENING_TAG = "<{}>"
CLOSING_TAG = "</{}>"
URL_MARKUP = '<a href="{}">{}</a>'
EMOJI_MARKUP = '<tg-emoji emoji-id="{}">{}</tg-emoji>'
FIXED_WIDTH_DELIMS = [CODE_DELIM, PRE_DELIM]

_INLINE = {
    BOLD_DELIM: TLEntityType.BOLD,
    ITALIC_DELIM: TLEntityType.ITALIC,
    UNDERLINE_DELIM: TLEntityType.UNDERLINE,
    STRIKE_DELIM: TLEntityType.STRIKETHROUGH,
    SPOILER_DELIM: TLEntityType.SPOILER,
}


def replace_once(source: str, old: str, new: str, start: int):
    return source[:start] + source[start:].replace(old, new, 1)


class Markdown:
    @staticmethod
    def protect_escaped_chars(text: str):
        escaped = {}

        def repl(m):
            token = "\x00%d\x00" % len(escaped)
            escaped[token] = m.group(1)
            return token

        return ESCAPED_MARKDOWN_RE.sub(repl, text), escaped

    @staticmethod
    def restore_escaped_chars(text: str, escaped_chars: dict) -> str:
        for token, char in escaped_chars.items():
            text = text.replace(token, char)
        return text

    @staticmethod
    def escape_and_create_quotes(text: str, strict: bool):
        return text

    @classmethod
    def parse(cls, text: str, strict: bool = False):
        out, entities = _parse_markdown(add_surrogates(text or ""))
        entities.sort(key=lambda e: e.offset)
        return {"message": remove_surrogates(out), "entities": entities}

    @staticmethod
    def unparse(text: str, entities: list):
        text = add_surrogates(text or "")
        inserts = []
        for e in entities or []:
            if not isinstance(e, RawEntity):
                e = _from_tlrpc(e)
                if e is None:
                    continue
            start, end = e.offset, e.offset + e.length
            if e.type == TLEntityType.TEXT_LINK:
                inserts.append((start, 1, "["))
                inserts.append((end, 0, "](%s)" % (e.url or "")))
            elif e.type == TLEntityType.CUSTOM_EMOJI:
                inserts.append((start, 1, "!["))
                inserts.append((end, 0, "](tg://emoji?id=%d)" % (e.document_id or 0)))
            elif e.type == TLEntityType.PRE:
                inserts.append((start, 1, "```%s\n" % (e.language or "")))
                inserts.append((end, 0, "\n```"))
            elif e.type == TLEntityType.CODE:
                inserts.append((start, 1, "`"))
                inserts.append((end, 0, "`"))
            elif e.type == TLEntityType.BLOCKQUOTE:
                continue
            else:
                delim = {v: k for k, v in _INLINE.items()}[e.type]
                inserts.append((start, 1, delim))
                inserts.append((end, 0, delim))
        inserts.sort(key=lambda x: (x[0], x[1]))
        out = []
        last = 0
        for pos, _, token in inserts:
            out.append(text[last:pos])
            out.append(token)
            last = pos
        out.append(text[last:])
        return remove_surrogates("".join(out))


def _parse_markdown(text: str):
    """Returns (plain text, entities) for Telegram-style markdown; text has surrogates added."""
    out = []
    out_len = 0
    entities = []
    open_inline = {}
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "\\" and i + 1 < n and text[i + 1] in MARKDOWN_ESCAPABLE_CHARS:
            out.append(text[i + 1])
            out_len += 1
            i += 2
            continue
        if text.startswith(PRE_DELIM, i):
            end = text.find(PRE_DELIM, i + 3)
            if end != -1:
                body = text[i + 3:end]
                language = ""
                newline = body.find("\n")
                if newline != -1 and body[:newline].strip() and " " not in body[:newline].strip():
                    language = body[:newline].strip()
                    body = body[newline + 1:]
                elif body.startswith("\n"):
                    body = body[1:]
                if body.endswith("\n"):
                    body = body[:-1]
                entities.append(RawEntity(TLEntityType.PRE, out_len, len(body), language=language))
                out.append(body)
                out_len += len(body)
                i = end + 3
                continue
        if ch == "`":
            end = text.find("`", i + 1)
            if end != -1:
                body = text[i + 1:end]
                entities.append(RawEntity(TLEntityType.CODE, out_len, len(body)))
                out.append(body)
                out_len += len(body)
                i = end + 1
                continue
        if ch == "[" or (ch == "!" and text.startswith("![", i)):
            is_emoji = ch == "!"
            start = i + (2 if is_emoji else 1)
            close = _find_link_close(text, start)
            if close != -1 and text.startswith("](", close):
                url_end = text.find(")", close + 2)
                if url_end != -1:
                    inner_text, inner_entities = _parse_markdown(text[start:close])
                    url = text[close + 2:url_end]
                    for e in inner_entities:
                        e.offset += out_len
                        entities.append(e)
                    if is_emoji:
                        match = re.search(r"id=(\d+)", url)
                        entities.append(RawEntity(TLEntityType.CUSTOM_EMOJI, out_len, len(inner_text),
                                                  document_id=int(match.group(1)) if match else 0))
                    else:
                        entities.append(RawEntity(TLEntityType.TEXT_LINK, out_len, len(inner_text), url=url))
                    out.append(inner_text)
                    out_len += len(inner_text)
                    i = url_end + 1
                    continue
        matched = None
        for delim in _INLINE:
            if text.startswith(delim, i):
                matched = delim
                break
        if matched is not None:
            if matched in open_inline:
                start = open_inline.pop(matched)
                if out_len > start:
                    entities.append(RawEntity(_INLINE[matched], start, out_len - start))
                i += len(matched)
                continue
            if text.find(matched, i + len(matched)) != -1:
                open_inline[matched] = out_len
                i += len(matched)
                continue
        out.append(ch)
        out_len += 1
        i += 1
    return "".join(out), entities


def _find_link_close(text: str, start: int) -> int:
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if c == "[":
            depth += 1
        elif c == "]":
            if depth == 0:
                return i
            depth -= 1
        i += 1
    return -1


def parse_text(text: str, parse_mode: Optional[str] = "HTML", is_caption: bool = False) -> dict:
    if parse_mode is None:
        return {"message": text, "entities": []}
    mode = str(parse_mode).lower()
    if mode == "html":
        return HTML.parse(text)
    if mode in ("markdown", "md", "markdownv2"):
        return Markdown.parse(text)
    raise ValueError("Unsupported parse_mode: %s" % parse_mode)


def to_tlrpc_entities(entities: List[Any]):
    """Java ArrayList of TLRPC.MessageEntity for raw entities."""
    from java.util import ArrayList

    result = ArrayList()
    for e in entities or []:
        if isinstance(e, RawEntity):
            result.add(e.to_tlrpc_object())
        else:
            result.add(e)
    return result
