import { useMutation, useQueryClient } from "@tanstack/react-query";
import { replaceMyPreferences, userKeys, type Preferences } from "@/entities/user";
import { messageForError } from "@/shared/api";
import { useToast } from "@/shared/ui";

const SAVE_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: "선택한 극장을 확인할 수 없어요. 극장을 다시 골라 주세요.",
};

/** USR-05 PUT (전체 교체). 성공하면 캐시를 응답으로 바꾼다 */
export function useSavePreferences(options: { onSuccess?: (saved: Preferences) => void } = {}) {
  const queryClient = useQueryClient();
  const toast = useToast();

  return useMutation({
    mutationFn: (body: Preferences) => replaceMyPreferences(body),
    onSuccess: (saved) => {
      queryClient.setQueryData(userKeys.preferences(), saved);
      toast.show("선호 설정을 저장했어요.");
      options.onSuccess?.(saved);
    },
    onError: (error) => {
      toast.show(messageForError(error, SAVE_MESSAGES), { tone: "error" });
    },
  });
}
