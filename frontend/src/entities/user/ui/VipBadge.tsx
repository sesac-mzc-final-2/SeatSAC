import { Icon } from "@/shared/ui";
import styles from "./VipBadge.module.css";

/** VIP 회원 배지 (ADR-118). NORMAL 에는 배지를 달지 않는다 */
export function VipBadge({ size = "md" }: { size?: "sm" | "md" }) {
  return (
    <span className={styles.badge} data-size={size}>
      <Icon name="sparkles" size={size === "sm" ? 12 : 14} />
      VIP
    </span>
  );
}
