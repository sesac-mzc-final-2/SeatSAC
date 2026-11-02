
"""Keep SQS queues active for CloudWatch metrics (3.1.9)."""

import boto3

sqs = boto3.client("sqs")


def handler(event, context):
    queue_url = event["queue_url"]

    response = sqs.get_queue_attributes(
        QueueUrl=queue_url,
        AttributeNames=["ApproximateNumberOfMessages"],
    )

    return {
        "queue_url": queue_url,
        "attributes": response["Attributes"],
    }
