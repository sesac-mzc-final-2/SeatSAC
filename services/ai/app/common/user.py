"""Gateway 헤더 → 현재 사용자. userId 는 Cognito sub."""
from dataclasses import dataclass

from fastapi import Header

from app.common.errors import BusinessError


@dataclass(frozen=True)
class CurrentUser:
    user_id: str
    role: str

    @property
    def is_admin(self) -> bool:
        return self.role == "ADMIN"


def current_user(x_user_id: str | None = Header(default=None), x_user_role: str = Header(default="USER")) -> CurrentUser:
    if not x_user_id:
        raise BusinessError("UNAUTHORIZED", "인증 정보가 없습니다.")
    return CurrentUser(x_user_id, x_user_role)


def admin_only(user: CurrentUser) -> CurrentUser:
    if not user.is_admin:
        raise BusinessError("FORBIDDEN", "관리자만 사용할 수 있습니다.")
    return user
