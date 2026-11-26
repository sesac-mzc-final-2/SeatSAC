package com.seatsac.catalog.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 변경 찾기 주기 — {@code APP_CATALOG_CHANGE_SCAN_INTERVAL}(기본 1분). {@code APP_CATALOG_CHANGE_SCAN_ENABLED=false}면 빈이 없다.
 * 스캔은 outbox에 쓰기만 하고 보내지는 않는다 — 릴레이가 꺼져 있어도(기본) 켜 둬도 된다(PENDING으로 쌓인다).
 * 첫 주기는 기동 직후가 아니라 한 주기 뒤 — 기동 때 readiness·캐시 예열과 겹치지 않게.
 */
@Component
@ConditionalOnProperty(prefix = "app.catalog.change-scan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CatalogChangeScanScheduler {

    private final CatalogChangeScanner scanner;

    public CatalogChangeScanScheduler(CatalogChangeScanner scanner) {
        this.scanner = scanner;
    }

    @Scheduled(fixedDelayString = "${app.catalog.change-scan.interval:1m}",
        initialDelayString = "${app.catalog.change-scan.interval:1m}")
    public void scan() {
        scanner.scanOnce();
    }
}
