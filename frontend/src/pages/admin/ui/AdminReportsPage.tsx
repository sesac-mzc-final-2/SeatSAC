import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import {
  getErrorReport,
  isReportReady,
  opsKeys,
  opsReportDatesQuery,
  requestErrorReport,
  type ReportReady,
} from "@/entities/admin";
import { isApiError } from "@/shared/api";
import { formatDateKo, timeOfIso, useDocumentTitle } from "@/shared/lib";
import { Button, EmptyState, ErrorState, Icon, LoadingBlock } from "@/shared/ui";
import { AdminForbidden } from "@/widgets/admin-layout";
import styles from "./AdminPages.module.css";

/** 쿼리 상태를 다시 보는 간격 · 이만큼 지나도 RUNNING이면 멈추고 다시 시도하게 한다 */
const POLL_INTERVAL_MS = 2_000;
const POLL_LIMIT_MS = 120_000;
/** presigned URL 유효 시간(계약 5분). 조금 일찍 만료로 보고 새 주소를 받게 한다 */
const URL_TTL_MS = 4.5 * 60_000;

const REPORT_MESSAGES: Record<string, string> = {
  REPORT_UNAVAILABLE: "리포트 저장소(Athena·S3)를 읽지 못했어요. 다시 시도하면 될 수 있어요.",
  NOT_FOUND: "리포트 쿼리를 찾지 못했어요. 「리포트 만들기」를 다시 눌러 주세요.",
  VALIDATION_FAILED: "날짜가 올바르지 않아요. 목록에서 다시 골라 주세요.",
};

/** 지금 날짜의 리포트 진행 상황. 날짜를 바꾸면 처음으로 */
type Job =
  | { kind: "running"; date: string; queryId: string; startedAt: number }
  | { kind: "ready"; date: string; report: ReportReady; receivedAt: number };

/**
 * 관리자 「오류 리포트」 `/admin/reports` (6.3.7, OPS-22): 날짜 드롭다운 → 리포트 만들기 → CSV 내려받기.
 * - 같은 날짜 결과가 1시간 안에 있으면 ops-api가 바로 주소를 준다(200). 없으면 쿼리를 시작하고(202) 2초마다 상태를 본다
 * - 내려받기 주소는 5분짜리(S3 presigned)라 지나면 숨기고 「새 주소 받기」로 다시 받는다(1시간 안이면 쿼리를 다시 돌리지 않는다)
 * - CSV 열에 IP·토큰·질문 원문은 없다(계약)
 */
export function AdminReportsPage() {
  useDocumentTitle("오류 리포트 · 관리자");
  const dates = useQuery(opsReportDatesQuery());
  const [selected, setSelected] = useState<string | null>(null);
  const [job, setJob] = useState<Job | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const date = selected ?? dates.data?.items[0] ?? null;
  const currentJob = job && job.date === date ? job : null;

  // 요청 세대: 새 요청·날짜 변경마다 올린다. 늦게 온 이전 응답(A→B→A의 첫 A 포함)은 세대가 달라 버린다
  const generation = useRef(0);
  const request = useMutation({
    mutationFn: ({ target }: { target: string; gen: number }) => requestErrorReport(target),
    onSuccess: (result, { target, gen }) => {
      if (gen !== generation.current) return;
      setJob(
        isReportReady(result)
          ? { kind: "ready", date: target, report: result, receivedAt: Date.now() }
          : { kind: "running", date: target, queryId: result.queryId, startedAt: Date.now() },
      );
    },
  });

  const runningId = currentJob?.kind === "running" ? currentJob.queryId : null;
  const status = useQuery({
    queryKey: [...opsKeys.all, "reports", "query", runningId] as const,
    queryFn: ({ signal }) => getErrorReport(runningId!, signal),
    enabled: runningId !== null,
    retry: false,
    refetchInterval: (query) =>
      query.state.data?.state === "RUNNING" &&
      currentJob?.kind === "running" &&
      Date.now() - currentJob.startedAt < POLL_LIMIT_MS
        ? POLL_INTERVAL_MS
        : false,
  });

  // 내려받기 주소: 바로 받은 200(ready) 또는 쿼리 SUCCEEDED. 받은 시각부터 5분짜리
  const succeeded = status.data?.state === "SUCCEEDED" && status.data.downloadUrl ? status.data : null;
  const result: { report: ReportReady; receivedAt: number } | null =
    currentJob?.kind === "ready"
      ? { report: currentJob.report, receivedAt: currentJob.receivedAt }
      : succeeded
        ? {
            report: {
              downloadUrl: succeeded.downloadUrl!,
              rowCount: succeeded.rowCount ?? 0,
              generatedAt: succeeded.generatedAt ?? "",
            },
            receivedAt: status.dataUpdatedAt,
          }
        : null;

  // 화면 시계: 내려받기 주소 만료·쿼리 대기 한도 시각에 한 번 다시 그린다
  const deadline = result
    ? result.receivedAt + URL_TTL_MS
    : currentJob?.kind === "running"
      ? currentJob.startedAt + POLL_LIMIT_MS + POLL_INTERVAL_MS
      : null;
  useEffect(() => {
    if (deadline === null) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.max(0, deadline - Date.now()));
    return () => window.clearTimeout(timer);
  }, [deadline]);

  if (dates.isPending) return <LoadingBlock label="리포트 날짜를 불러오는 중이에요" />;
  if (dates.isError) {
    if (isApiError(dates.error, 403)) return <AdminForbidden />;
    return (
      <ErrorState
        error={dates.error}
        title="리포트 날짜를 불러오지 못했어요"
        messages={REPORT_MESSAGES}
        onRetry={() => void dates.refetch()}
      />
    );
  }

  const header = (
    <div className={styles.headRow}>
      <h2 className={styles.title}>오류 리포트</h2>
      <p className="micro">오류·보안 로그를 날짜별 CSV로 내려받아요</p>
    </div>
  );

  if (date === null) {
    return (
      <div className="panel">
        {header}
        <EmptyState
          icon="alert-circle"
          title="아직 리포트가 없어요"
          description="오류 로그는 10분마다 리포트 저장소로 옮겨져요. 잠시 뒤 다시 확인해 주세요."
          action={
            <Button onClick={() => void dates.refetch()}>
              <Icon name="rotate-cw" /> 다시 확인
            </Button>
          }
        />
      </div>
    );
  }

  const start = () => {
    generation.current += 1;
    setJob(null);
    request.mutate({ target: date, gen: generation.current });
  };
  const timedOut =
    currentJob?.kind === "running" &&
    status.data?.state === "RUNNING" &&
    !status.isFetching &&
    now - currentJob.startedAt >= POLL_LIMIT_MS;
  const failed = currentJob?.kind === "running" && (status.isError || status.data?.state === "FAILED");
  const busy = request.isPending || (currentJob?.kind === "running" && !result && !timedOut && !failed);

  const renderResult = () => {
    if (request.isPending) return <LoadingBlock label="리포트를 요청하는 중이에요" />;
    if (request.isError && !currentJob) {
      if (isApiError(request.error, 403)) return <AdminForbidden />;
      return <ErrorState error={request.error} title="리포트를 만들지 못했어요" messages={REPORT_MESSAGES} onRetry={start} />;
    }
    if (!currentJob) return null;

    if (!result) {
      if (status.isError) {
        return <ErrorState error={status.error} title="리포트 상태를 확인하지 못했어요" messages={REPORT_MESSAGES} onRetry={start} />;
      }
      if (status.data?.state === "FAILED") {
        return (
          <div className={styles.result} role="alert">
            <p>리포트 쿼리가 실패했어요(Athena). 다시 만들어 주세요.</p>
            <Button onClick={start}>
              <Icon name="rotate-cw" /> 다시 만들기
            </Button>
          </div>
        );
      }
      if (timedOut) {
        return (
          <div className={styles.result} role="status">
            <p>리포트가 오래 걸리고 있어요. 잠시 뒤 다시 만들어 주세요.</p>
            <Button onClick={start}>
              <Icon name="rotate-cw" /> 다시 만들기
            </Button>
          </div>
        );
      }
      return <LoadingBlock label="리포트를 만드는 중이에요 (Athena 쿼리)" />;
    }

    const { report, receivedAt } = result;
    const expired = now - receivedAt >= URL_TTL_MS;
    return (
      <div className={styles.result} role="status">
        <p>
          <strong>{formatDateKo(currentJob.date)}</strong> 리포트 · {report.rowCount.toLocaleString("ko-KR")}행
          {report.generatedAt && <span className="micro"> · {timeOfIso(report.generatedAt)}에 만듦</span>}
        </p>
        {report.rowCount === 0 && <p className="micro">이 날짜에는 기록된 오류가 없어요(빈 CSV).</p>}
        <p className="micro">로그는 10분마다 옮겨져 최근 약 10분은 아직 없을 수 있어요. 새로 옮겨진 로그가 있으면 「리포트 만들기」가 다시 만들어요.</p>
        {expired ? (
          <>
            <p className="micro">내려받기 주소가 만료됐어요(5분).</p>
            <Button onClick={start}>
              <Icon name="rotate-cw" /> 새 주소 받기
            </Button>
          </>
        ) : (
          <>
            <a className={styles.download} href={report.downloadUrl} download={`errors-${currentJob.date}.csv`}>
              <Icon name="save" /> CSV 내려받기
            </a>
            <p className="micro">주소는 5분 동안만 쓸 수 있어요. 지나면 「새 주소 받기」가 나와요.</p>
          </>
        )}
      </div>
    );
  };

  return (
    <div className="panel">
      {header}
      <div className={styles.toolbar}>
        <div className={styles.selectWrap}>
          <select
            className={styles.select}
            aria-label="리포트 날짜"
            value={date}
            onChange={(event) => {
              generation.current += 1;
              setSelected(event.target.value);
              setJob(null);
              request.reset();
            }}
          >
            {dates.data.items.map((item) => (
              <option key={item} value={item}>
                {formatDateKo(item, { withYear: true })}
              </option>
            ))}
          </select>
          <span className={styles.selectChevron}>
            <Icon name="chevron-down" />
          </span>
        </div>
        <Button variant="primary" onClick={start} disabled={busy}>
          리포트 만들기
        </Button>
      </div>
      {renderResult()}
      <p className={`micro ${styles.footnote}`}>
        열: timestamp · service · level · errorCategory · status · path · traceId · message — IP·토큰·질문 원문은 없어요.
      </p>
    </div>
  );
}
