interface ActivityRingProps {
  done: number;
  total: number;
  size?: number;
  strokeWidth?: number;
  color?: string;
}

export function ActivityRing({
  done,
  total,
  size = 24,
  strokeWidth = 3,
  color = 'var(--apple-green)',
}: ActivityRingProps) {
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const pct = total > 0 ? Math.min(1, Math.max(0, done / total)) : 0;
  const offset = circumference * (1 - pct);

  return (
    <svg width={size} height={size} className="-rotate-90 shrink-0" aria-hidden="true">
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke="var(--apple-separator)"
        strokeWidth={strokeWidth}
      />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke={color}
        strokeWidth={strokeWidth}
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        strokeLinecap="round"
        style={{ transition: 'stroke-dashoffset 0.45s cubic-bezier(0.22, 1, 0.36, 1)' }}
      />
    </svg>
  );
}
