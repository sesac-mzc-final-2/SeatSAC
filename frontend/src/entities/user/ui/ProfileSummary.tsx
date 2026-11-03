import type { ReactNode } from "react";
import { Chip, Icon } from "@/shared/ui";
import type { Profile } from "../model/types";
import styles from "./ProfileSummary.module.css";

interface Props {
  profile: Profile;
  /** 오른쪽 아래 보조 문구·행동 (로그아웃 등) */
  aside?: ReactNode;
}

/** 프로필 영역 (W8): 이름 · 이메일 · 역할 */
export function ProfileSummary({ profile, aside }: Props) {
  const name = profile.name?.trim() || profile.email.split("@")[0];
  return (
    <section className={`panel ${styles.panel}`} aria-label="프로필">
      <div className={styles.avatar} aria-hidden="true">
        <Icon name="user" size={28} strokeWidth={1.6} />
      </div>
      <div className={styles.identity}>
        <p className="micro">SeatSAC 회원</p>
        <h2 className={styles.name}>{name}</h2>
        <p className={styles.email}>{profile.email}</p>
      </div>
      <div className={styles.meta}>
        <Chip size="sm" selected disabled aria-disabled="true" tabIndex={-1}>
          {profile.role === "ADMIN" ? "관리자" : "일반 회원"}
        </Chip>
        {aside}
      </div>
    </section>
  );
}
