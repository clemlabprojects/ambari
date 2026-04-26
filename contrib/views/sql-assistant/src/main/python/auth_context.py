from contextvars import ContextVar

user_token: ContextVar[str | None] = ContextVar("user_token", default=None)
user_name: ContextVar[str | None] = ContextVar("user_name", default=None)
