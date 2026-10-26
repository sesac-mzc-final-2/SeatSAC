import { Badge } from "@/shared/ui";
import { gradeLabel, gradeTitle } from "../lib/grade";
import type { ViewGrade } from "../model/types";

export function GradeBadge({ grade }: { grade: ViewGrade }) {
  return (
    <Badge title={gradeTitle(grade)} aria-label={gradeTitle(grade)}>
      {gradeLabel(grade)}
    </Badge>
  );
}
