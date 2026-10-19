"""Cognito Pre sign-up: 이메일 실발송이 범위 밖이므로 가입을 자동 확인한다."""


def handler(event, context):
    event["response"]["autoConfirmUser"] = True
    event["response"]["autoVerifyEmail"] = True
    return event
