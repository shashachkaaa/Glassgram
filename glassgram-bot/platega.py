"""A small client for the Platega API (https://docs.platega.io)."""

import hmac
from dataclasses import dataclass

import aiohttp

PENDING = "PENDING"
CONFIRMED = "CONFIRMED"
CANCELED = "CANCELED"
CHARGEBACKED = "CHARGEBACKED"


class PlategaError(Exception):
    pass


@dataclass
class Invoice:
    transaction_id: str
    url: str
    status: str
    expires_in: str


class Platega:
    def __init__(self, session: aiohttp.ClientSession, base_url: str, merchant_id: str, secret: str):
        self._session = session
        self._base_url = base_url
        self._merchant_id = merchant_id
        self._secret = secret

    @property
    def _headers(self) -> dict[str, str]:
        return {
            "X-MerchantId": self._merchant_id,
            "X-Secret": self._secret,
            "Content-Type": "application/json",
        }

    async def create_invoice(
        self,
        amount: int,
        description: str,
        order_id: str,
        payload: str,
        user_id: int,
        username: str | None,
        return_url: str,
        failed_url: str,
    ) -> Invoice:
        """A payment link without a fixed method: the payer picks SBP, a card or crypto on Platega's page."""
        body = {
            "paymentDetails": {"amount": amount, "currency": "RUB"},
            "description": description,
            "return": return_url,
            "failedUrl": failed_url,
            "payload": payload,
            "orderId": order_id,
            "metadata": {"userId": str(user_id), "userName": f"@{username}" if username else ""},
        }
        async with self._session.post(f"{self._base_url}/v2/transaction/process", json=body, headers=self._headers) as response:
            data = await self._json(response)
        try:
            return Invoice(
                transaction_id=str(data["transactionId"]),
                url=str(data["url"]),
                status=str(data.get("status", PENDING)),
                expires_in=str(data.get("expiresIn", "")),
            )
        except KeyError as e:
            raise PlategaError(f"unexpected response: {data}") from e

    async def get_status(self, transaction_id: str) -> dict:
        """The transaction as Platega has it: id, status, paymentDetails, payload..."""
        async with self._session.get(f"{self._base_url}/transaction/{transaction_id}", headers=self._headers) as response:
            return await self._json(response)

    def is_genuine_callback(self, merchant_id: str | None, secret: str | None) -> bool:
        """Platega signs callbacks with the merchant's own id and API key in the headers."""
        return (
            merchant_id is not None
            and secret is not None
            and hmac.compare_digest(merchant_id.encode(), self._merchant_id.encode())
            and hmac.compare_digest(secret.encode(), self._secret.encode())
        )

    @staticmethod
    async def _json(response: aiohttp.ClientResponse) -> dict:
        text = await response.text()
        if response.status != 200:
            raise PlategaError(f"HTTP {response.status}: {text[:500]}")
        try:
            return await response.json(content_type=None)
        except ValueError as e:
            raise PlategaError(f"not JSON: {text[:500]}") from e
