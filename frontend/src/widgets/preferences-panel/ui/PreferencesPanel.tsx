import { useQuery } from "@tanstack/react-query";
import { theaterListQuery } from "@/entities/theater";
import { myPreferencesQuery } from "@/entities/user";
import { PreferencesEditor } from "@/features/edit-preferences";
import { ErrorState, LoadingBlock } from "@/shared/ui";

/**
 * W8 탭 2 선호 설정 (USR-05): 저장된 선호 + 배치 있는 극장 4곳을 받아 편집기를 그린다.
 * 편집기는 재마운트하지 않는다 — 저장된 값이 바뀌면(저장 성공·다른 기기에서 저장) 편집기가 `initial` 변화를 보고
 * 스스로 맞추고, 편집 중인 내용은 지키지 않는다. 데이터를 한 번 받은 뒤의 재조회 실패(재연결 등)도 편집기를 내리지 않는다
 */
export function PreferencesPanel() {
  const preferences = useQuery(myPreferencesQuery());
  const theaters = useQuery(theaterListQuery({ hasLayout: true }));

  if (preferences.isPending || theaters.isPending) return <LoadingBlock label="선호 설정을 불러오는 중이에요" />;
  if (preferences.data === undefined) {
    return <ErrorState error={preferences.error} title="선호 설정을 불러오지 못했어요" onRetry={() => preferences.refetch()} />;
  }
  if (theaters.data === undefined) {
    return <ErrorState error={theaters.error} title="극장 목록을 불러오지 못했어요" onRetry={() => theaters.refetch()} />;
  }

  return <PreferencesEditor theaters={theaters.data.items} initial={preferences.data} />;
}
