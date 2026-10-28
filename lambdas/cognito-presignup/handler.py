"""Cognito Pre sign-up (3.1.16): 이메일 실발송이 범위 밖이므로 Hosted UI 가입을 자동 확인한다.

관리자가 만든 계정(PreSignUp_AdminCreateUser — 부하 테스트 4.2.1)은 Cognito가 따로 처리하므로 그대로 둔다.
"""

SELF_SIGN_UP = {"PreSignUp_SignUp", "PreSignUp_ExternalProvider"}


def handler(event, context):
    if event.get("triggerSource") in SELF_SIGN_UP:
        event["response"]["autoConfirmUser"] = True
        event["response"]["autoVerifyEmail"] = True
    return event
