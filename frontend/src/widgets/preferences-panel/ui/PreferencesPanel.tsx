import { useQuery } from "@tanstack/react-query";
import { theaterListQuery } from "@/entities/theater";
import { myPreferencesQuery } from "@/entities/user";
import { PreferencesEditor } from "@/features/edit-preferences";
import { ErrorState, LoadingBlock } from "@/shared/ui";

/**
 * W8 탭 2 선호 설정 (USR-05): 저장된 선호 + 배치 있는 극장 4곳을 받아 편집기를 그린다.
 * 저장된 값이 바뀌면(저장 성공·다시 불러옴) key 로 편집기를 새로 그린다
 */
export function PreferencesPanel() {
  const preferences = useQuery(myPreferencesQuery());
  const theaters = useQuery(theaterListQuery({ hasLayout: true }));

  if (preferences.isPending || theaters.isPending) return <LoadingBlock label="선호 설정을 불러오는 중이에요" />;
  if (preferences.isError) {
    return <ErrorState error={preferences.error} title="선호 설정을 불러오지 못했어요" onRetry={() => preferences.refetch()} />;
  }
  if (theaters.isError) {
    return <ErrorState error={theaters.error} title="극장 목록을 불러오지 못했어요" onRetry={() => theaters.refetch()} />;
  }

  return (
    <PreferencesEditor
      key={preferences.dataUpdatedAt}
      theaters={theaters.data.items}
      initial={preferences.data}
    />
  );
}
