import type { ViewGrade } from "../model/types";

const LABEL: Record<ViewGrade, string> = { ALL: "전체", "12": "12", "15": "15", "19": "청불" };
const TITLE: Record<ViewGrade, string> = {
  ALL: "전체 관람가",
  "12": "12세 이상 관람가",
  "15": "15세 이상 관람가",
  "19": "청소년 관람 불가",
};

export const gradeLabel = (grade: ViewGrade): string => LABEL[grade] ?? grade;
export const gradeTitle = (grade: ViewGrade): string => TITLE[grade] ?? grade;
