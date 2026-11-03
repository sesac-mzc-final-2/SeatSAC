import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { useSession } from "@/entities/session";
import { myProfileQuery, ProfileSummary } from "@/entities/user";
import { LoginGate } from "@/features/login";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { ErrorState, Icon, LoadingBlock, PageHeading, SegmentedTabs, type SegmentedTab } from "@/shared/ui";
import { BookingHistory } from "@/widgets/booking-history";
import { PreferencesPanel } from "@/widgets/preferences-panel";
import styles from "./MyPage.module.css";

type Tab = "bookings" | "preferences";

const TABS: ReadonlyArray<SegmentedTab<Tab>> = [
  {
    value: "bookings",
    panelId: "my-panel-bookings",
    label: (
      <>
        <Icon name="ticket" /> 예매 내역
      </>
    ),
  },
  {
    value: "preferences",
    panelId: "my-panel-preferences",
    label: (
      <>
        <Icon name="sparkles" /> 선호 설정
      </>
    ),
  },
];

/** 프로필 오류 문구 (GET /users/me) */
const PROFILE_MESSAGES: Record<string, string> = {
  IDENTITY_UNAVAILABLE: "회원 정보를 확인하는 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요.",
};

/**
 * W8 마이페이지 `/me` (3.3.6): 프로필 · 탭(예매 내역 USR-04 · 선호 설정 USR-05).
 * 탭은 `?tab=` 으로 주소에 남겨 새로고침·뒤로 가기에도 유지한다. 비로그인은 로그인 안내.
 */
export function MyPage() {
  useDocumentTitle("마이페이지");
  const session = useSession();
  const [params, setParams] = useSearchParams();
  const tab: Tab = params.get("tab") === "preferences" ? "preferences" : "bookings";

  const authenticated = session.status === "authenticated";
  const profile = useQuery({ ...myProfileQuery(), enabled: authenticated });

  const setTab = (next: Tab) => {
    setParams(
      (current) => {
        if (next === "bookings") current.delete("tab");
        else current.set("tab", next);
        return current;
      },
      { replace: true, preventScrollReset: true },
    );
  };

  const renderBody = () => {
    if (session.status === "loading") return <LoadingBlock label="로그인 상태를 확인하는 중이에요" />;
    if (!authenticated) return <LoginGate returnTo={routes.myPage(tab === "bookings" ? undefined : tab)} />;
    if (profile.isPending) return <LoadingBlock label="회원 정보를 불러오는 중이에요" />;
    if (profile.isError) {
      return (
        <ErrorState
          error={profile.error}
          title="회원 정보를 불러오지 못했어요"
          messages={PROFILE_MESSAGES}
          onRetry={() => profile.refetch()}
        />
      );
    }
    return (
      <>
        <ProfileSummary
          profile={profile.data}
          aside={
            <button type="button" className={styles.logout} onClick={() => session.signOut()}>
              로그아웃
            </button>
          }
        />
        <div className={styles.tabs}>
          <SegmentedTabs tabs={TABS} value={tab} onChange={setTab} aria-label="마이페이지 메뉴" />
        </div>
        <div
          id={tab === "bookings" ? "my-panel-bookings" : "my-panel-preferences"}
          role="tabpanel"
          aria-labelledby={`tab-${tab}`}
        >
          {tab === "bookings" ? <BookingHistory /> : <PreferencesPanel />}
        </div>
      </>
    );
  };

  return (
    <div>
      <PageHeading eyebrow="나의 SeatSAC" title="마이페이지" description="예매 내역과 취향을 한곳에서 관리해요." />
      {renderBody()}
    </div>
  );
}
