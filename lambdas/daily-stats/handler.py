"""OPS-08: 전날(KST) 예매 집계 → stats_db.daily_booking_stat upsert. 재실행 멱등."""
import datetime as dt
import os

import pymysql


def handler(event, context):
    target = (dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).date() - dt.timedelta(days=1))
    conn = pymysql.connect(host=os.environ["DB_HOST"], user=os.environ["DB_USER"],
                           password=os.environ["DB_PASSWORD"], database="stats_db", autocommit=False)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO stats_db.daily_booking_stat
                  (business_date, movie_id, theater_id, booking_count, seat_count, total_amount, aggregated_at)
                SELECT s.business_date, s.movie_id, s.theater_id,
                       COUNT(*), SUM(b.seat_count), SUM(b.amount), UTC_TIMESTAMP()
                FROM booking_db.v_booking_report b
                JOIN catalog_db.v_screening_report s ON s.screening_id = b.screening_id
                WHERE b.status = 'CONFIRMED' AND s.business_date = %s
                GROUP BY s.business_date, s.movie_id, s.theater_id
                ON DUPLICATE KEY UPDATE booking_count = VALUES(booking_count),
                  seat_count = VALUES(seat_count), total_amount = VALUES(total_amount),
                  aggregated_at = VALUES(aggregated_at)
                """,
                (target,),
            )
        conn.commit()
    finally:
        conn.close()
    return {"businessDate": str(target)}
