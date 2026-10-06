"""@glassGramBot: takes donations through Platega and gives payers the Glassgram supporter badge.

Flow: the user picks an amount, the bot creates a Platega payment link and remembers it. When
Platega reports the payment (callback to <PUBLIC_URL>/platega/callback), or the user taps
"Check payment", or the background check finds it, the bot asks Platega for the transaction's
status itself and, when it is CONFIRMED, adds the user's id to glassgram-api/badges.json.
"""

import asyncio
import html
import logging
import time
import uuid

import aiohttp
from aiogram import Bot, Dispatcher, F
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message
from aiohttp import web

import platega as pg
from badges import BadgeError, Badges
from config import Config, load_config
from storage import Payment, Storage

log = logging.getLogger("glassgrambot")

# Payments still PENDING (or paid with the badge not given yet, if GitHub failed) are checked
# with Platega this often, for this long after creation, in case a callback is lost
POLL_INTERVAL = 60
POLL_WINDOW = 2 * 60 * 60


class Payments:
    """Settles payments: the only place that gives or takes the badge."""

    def __init__(self, config: Config, bot: Bot, storage: Storage, platega: pg.Platega, badges: Badges):
        self.config = config
        self.bot = bot
        self.storage = storage
        self.platega = platega
        self.badges = badges
        self._locks: dict[str, asyncio.Lock] = {}

    async def create(self, user_id: int, username: str | None, amount: int) -> tuple[Payment, str]:
        """A new payment and its link."""
        order_id = uuid.uuid4().hex
        me = await self.bot.get_me()
        back = f"https://t.me/{me.username}"
        invoice = await self.platega.create_invoice(
            amount=amount,
            description=f"Поддержка разработки Glassgram ({amount} ₽)",
            order_id=order_id,
            payload=f"{user_id}:{order_id}",
            user_id=user_id,
            username=username,
            return_url=back,
            failed_url=back,
        )
        await self.storage.add(invoice.transaction_id, order_id, user_id, amount, invoice.status)
        return await self.storage.get(invoice.transaction_id), invoice.url

    async def settle(self, transaction_id: str, notify: bool = True) -> Payment | None:
        """Brings a payment up to date with Platega; returns None for payments this bot did not create."""
        lock = self._locks.setdefault(transaction_id, asyncio.Lock())
        async with lock:
            payment = await self.storage.get(transaction_id)
            if payment is None:
                return None
            # Never trust the callback's body: the status comes from Platega's API
            data = await self.platega.get_status(transaction_id)
            status = str(data.get("status", payment.status)).upper()
            paid = (data.get("paymentDetails") or {}).get("amount")
            if status == pg.CONFIRMED and paid is not None and float(paid) + 1e-6 < payment.amount:
                log.warning("payment %s: paid %s, expected %s; no badge", transaction_id, paid, payment.amount)
                status = "UNDERPAID"
            if status != payment.status:
                await self.storage.set_status(transaction_id, status)
                payment.status = status

            if status == pg.CONFIRMED and not payment.badge_given:
                await self._give_badge(payment, notify)
            elif status == pg.CHARGEBACKED and payment.badge_given:
                await self._take_badge(payment, notify)
            return payment

    async def _give_badge(self, payment: Payment, notify: bool) -> None:
        added = await self.badges.grant(payment.user_id, f"Platega {payment.transaction_id}, {payment.amount} RUB")
        # Not added: the user already has it (an earlier payment) or another badge that is kept
        await self.storage.set_badge_given(payment.transaction_id, True)
        payment.badge_given = True
        log.info("payment %s confirmed: user %s, %s RUB, badge %s",
                 payment.transaction_id, payment.user_id, payment.amount, "added" if added else "already there")
        if notify:
            await self._send(payment.user_id,
                             "💜 <b>Спасибо за поддержку Glassgram!</b>\n\n"
                             f"Платёж на {payment.amount} ₽ получен. Значок <b>Supporter</b> появится рядом "
                             "с вашим именем в Glassgram при следующем открытии приложения (обычно в течение "
                             "пары минут).")

    async def _take_badge(self, payment: Payment, notify: bool) -> None:
        await self.storage.set_badge_given(payment.transaction_id, False)
        payment.badge_given = False
        # Another confirmed payment from the same user keeps the badge
        others = [p for p in await self.storage.confirmed_for(payment.user_id) if p.transaction_id != payment.transaction_id]
        if others:
            return
        await self.badges.revoke(payment.user_id, f"chargeback of Platega {payment.transaction_id}")
        log.info("payment %s chargebacked: badge of %s taken back", payment.transaction_id, payment.user_id)
        if notify:
            await self._send(payment.user_id, "Платёж был возвращён, поэтому значок Supporter снят.")

    async def _send(self, user_id: int, text: str) -> None:
        try:
            await self.bot.send_message(user_id, text)
        except Exception as e:  # blocked the bot, deleted the account...
            log.warning("cannot message %s: %s", user_id, e)

    async def poll_forever(self) -> None:
        while True:
            await asyncio.sleep(POLL_INTERVAL)
            try:
                for payment in await self.storage.unsettled(int(time.time()) - POLL_WINDOW):
                    try:
                        await self.settle(payment.transaction_id)
                    except (pg.PlategaError, BadgeError, aiohttp.ClientError) as e:
                        log.warning("check of %s failed: %s", payment.transaction_id, e)
            except Exception:
                log.exception("payment check loop")


# --- Telegram ---------------------------------------------------------------------------------

def amounts_keyboard(config: Config) -> InlineKeyboardMarkup:
    buttons = [InlineKeyboardButton(text=f"{amount} ₽", callback_data=f"donate:{amount}") for amount in config.donate_amounts]
    rows = [buttons[i:i + 3] for i in range(0, len(buttons), 3)]
    rows.append([InlineKeyboardButton(text="Другая сумма", callback_data="donate:custom")])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def setup_handlers(dp: Dispatcher, config: Config, payments: Payments) -> None:
    waiting_amount: set[int] = set()

    async def start_payment(message: Message, user_id: int, username: str | None, amount: int) -> None:
        try:
            payment, url = await payments.create(user_id, username, amount)
        except (pg.PlategaError, aiohttp.ClientError) as e:
            log.error("cannot create a payment for %s: %s", user_id, e)
            await message.answer("Не удалось создать платёж. Попробуйте чуть позже.")
            return
        keyboard = InlineKeyboardMarkup(inline_keyboard=[
            [InlineKeyboardButton(text=f"Оплатить {amount} ₽", url=url)],
            [InlineKeyboardButton(text="Я оплатил — проверить", callback_data=f"check:{payment.transaction_id}")],
        ])
        await message.answer(
            f"Счёт на <b>{amount} ₽</b> создан. Оплатить можно по СБП, картой или криптовалютой на странице Platega.\n\n"
            "Значок выдаётся автоматически сразу после оплаты.",
            reply_markup=keyboard,
        )

    @dp.message(CommandStart())
    async def on_start(message: Message) -> None:
        await message.answer(
            "👋 Это бот <b>Glassgram</b>.\n\n"
            "Поддержите разработку любой суммой — и рядом с вашим именем в Glassgram появится "
            "значок <b>Supporter</b> ⭐. Выберите сумму:",
            reply_markup=amounts_keyboard(config),
        )

    @dp.message(Command("donate"))
    async def on_donate(message: Message) -> None:
        await message.answer("Выберите сумму:", reply_markup=amounts_keyboard(config))

    @dp.callback_query(F.data.startswith("donate:"))
    async def on_amount(query: CallbackQuery) -> None:
        await query.answer()
        value = query.data.split(":", 1)[1]
        if value == "custom":
            waiting_amount.add(query.from_user.id)
            await query.message.answer(f"Напишите сумму в рублях, от {config.donate_min} до {config.donate_max}:")
            return
        await start_payment(query.message, query.from_user.id, query.from_user.username, int(value))

    @dp.message(F.text.regexp(r"^\s*\d{1,7}\s*(₽|руб\.?|р\.?)?\s*$"), lambda message: message.from_user.id in waiting_amount)
    async def on_custom_amount(message: Message) -> None:
        amount = int("".join(ch for ch in message.text if ch.isdigit()))
        if not config.donate_min <= amount <= config.donate_max:
            await message.answer(f"Сумма должна быть от {config.donate_min} до {config.donate_max} ₽.")
            return
        waiting_amount.discard(message.from_user.id)
        await start_payment(message, message.from_user.id, message.from_user.username, amount)

    @dp.callback_query(F.data.startswith("check:"))
    async def on_check(query: CallbackQuery) -> None:
        transaction_id = query.data.split(":", 1)[1]
        payment = await payments.storage.get(transaction_id)
        if payment is None or payment.user_id != query.from_user.id:
            await query.answer("Платёж не найден.", show_alert=True)
            return
        try:
            payment = await payments.settle(transaction_id, notify=False)
        except (pg.PlategaError, BadgeError, aiohttp.ClientError) as e:
            log.warning("check of %s failed: %s", transaction_id, e)
            await query.answer("Не удалось проверить платёж, попробуйте через минуту.", show_alert=True)
            return
        if payment.status == pg.CONFIRMED:
            await query.answer("Оплата получена, спасибо! Значок появится в Glassgram в течение пары минут.", show_alert=True)
        elif payment.status == pg.PENDING:
            await query.answer("Оплата ещё не поступила. Если вы уже оплатили, подождите минуту и проверьте снова.", show_alert=True)
        elif payment.status == "UNDERPAID":
            await query.answer("Сумма оплаты меньше суммы счёта. Напишите администратору.", show_alert=True)
        else:
            await query.answer("Платёж отменён. Можно создать новый: /donate", show_alert=True)

    @dp.message(Command("badge"))
    async def on_badge(message: Message) -> None:
        try:
            has = await payments.badges.has_badge(message.from_user.id)
        except (BadgeError, aiohttp.ClientError):
            await message.answer("Не удалось проверить, попробуйте позже.")
            return
        await message.answer("У вас есть значок Supporter ⭐" if has else "Значка пока нет. Поддержать: /donate")

    # Admins

    def is_admin(message: Message) -> bool:
        return message.from_user is not None and message.from_user.id in config.admin_ids

    @dp.message(Command("grant"), is_admin)
    async def on_grant(message: Message, command: CommandObject) -> None:
        if not command.args or not command.args.strip().lstrip("-").isdigit():
            await message.answer("Использование: /grant &lt;user id&gt;")
            return
        user_id = int(command.args.strip())
        added = await payments.badges.grant(user_id, f"by admin {message.from_user.id}")
        await message.answer(f"Значок выдан {user_id}." if added else f"У {user_id} уже есть значок (или другой).")

    @dp.message(Command("revoke"), is_admin)
    async def on_revoke(message: Message, command: CommandObject) -> None:
        if not command.args or not command.args.strip().lstrip("-").isdigit():
            await message.answer("Использование: /revoke &lt;user id&gt;")
            return
        user_id = int(command.args.strip())
        removed = await payments.badges.revoke(user_id, f"by admin {message.from_user.id}")
        await message.answer(f"Значок снят у {user_id}." if removed else f"У {user_id} нет значка Supporter.")

    @dp.message(Command("payments"), is_admin)
    async def on_payments(message: Message) -> None:
        latest = await payments.storage.latest(20)
        if not latest:
            await message.answer("Платежей пока нет.")
            return
        lines = [
            f"<code>{html.escape(p.transaction_id[:8])}</code> {p.user_id} — {p.amount} ₽ — {p.status}{' ⭐' if p.badge_given else ''}"
            for p in latest
        ]
        await message.answer("\n".join(lines))


# --- Platega callbacks ------------------------------------------------------------------------

def make_web_app(config: Config, payments: Payments) -> web.Application:
    async def callback(request: web.Request) -> web.Response:
        if not payments.platega.is_genuine_callback(request.headers.get("X-MerchantId"), request.headers.get("X-Secret")):
            log.warning("callback with wrong credentials from %s", request.remote)
            return web.Response(status=401)
        try:
            body = await request.json()
        except ValueError:
            return web.Response(status=400)
        transaction_id = str(body.get("id", ""))
        log.info("callback: %s %s", transaction_id, body.get("status"))
        try:
            payment = await payments.settle(transaction_id)
        except (pg.PlategaError, BadgeError, aiohttp.ClientError) as e:
            # Not 200: Platega retries up to 3 times, every 5 minutes; the background check covers the rest
            log.error("callback %s failed: %s", transaction_id, e)
            return web.Response(status=500)
        if payment is None:
            log.warning("callback for an unknown transaction %s", transaction_id)
        return web.Response(text="OK")

    async def health(_: web.Request) -> web.Response:
        return web.Response(text="ok")

    app = web.Application()
    app.router.add_post(config.callback_path, callback)
    app.router.add_get("/health", health)
    return app


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = load_config()

    storage = Storage(config.db_path)
    await storage.open()
    session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))
    bot = Bot(config.bot_token, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    platega = pg.Platega(session, config.platega_base_url, config.platega_merchant_id, config.platega_secret)
    badges = Badges(session, config.github_token, config.github_repo, config.github_branch, config.badges_path, config.badge_id)
    payments = Payments(config, bot, storage, platega, badges)

    dp = Dispatcher()
    setup_handlers(dp, config, payments)

    runner = web.AppRunner(make_web_app(config, payments))
    await runner.setup()
    await web.TCPSite(runner, config.web_host, config.web_port).start()
    log.info("Platega callbacks: %s%s (listening on %s:%s)", config.public_url, config.callback_path, config.web_host, config.web_port)

    poller = asyncio.create_task(payments.poll_forever())
    try:
        await dp.start_polling(bot)
    finally:
        poller.cancel()
        await runner.cleanup()
        await session.close()
        await bot.session.close()
        await storage.close()


if __name__ == "__main__":
    asyncio.run(main())
