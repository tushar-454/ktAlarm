import type { Weekday } from 'kt-alarm';

export const WEEKDAY_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;
export const WEEKDAY_LETTER = ['S', 'M', 'T', 'W', 'T', 'F', 'S'] as const;
/** Display order: Monday first. */
export const WEEK_ORDER: Weekday[] = [1, 2, 3, 4, 5, 6, 0];

export function formatTime(hour: number, minute: number) {
  const h12 = hour % 12 === 0 ? 12 : hour % 12;
  return {
    time: `${h12}:${String(minute).padStart(2, '0')}`,
    period: hour < 12 ? 'AM' : 'PM',
  };
}

export function formatDays(days: Weekday[]): string {
  if (days.length === 0) return 'Once';
  if (days.length === 7) return 'Every day';
  const set = new Set(days);
  if (days.length === 5 && [1, 2, 3, 4, 5].every((d) => set.has(d as Weekday))) return 'Weekdays';
  if (days.length === 2 && set.has(0) && set.has(6)) return 'Weekends';
  return WEEK_ORDER.filter((d) => set.has(d))
    .map((d) => WEEKDAY_SHORT[d])
    .join(', ');
}

/** "in 7 h 5 min", "in 12 min", "in less than a minute" */
export function formatCountdown(target: number, now = Date.now()): string {
  const totalMin = Math.max(0, Math.round((target - now) / 60_000));
  if (totalMin < 1) return 'in less than a minute';
  const days = Math.floor(totalMin / 1440);
  const hours = Math.floor((totalMin % 1440) / 60);
  const minutes = totalMin % 60;
  const parts: string[] = [];
  if (days) parts.push(`${days} d`);
  if (hours) parts.push(`${hours} h`);
  if (minutes && !days) parts.push(`${minutes} min`);
  return `in ${parts.join(' ')}`;
}

/** Next ring for an alarm that is being edited (mirrors the native logic). */
export function previewNextTrigger(hour: number, minute: number, days: Weekday[], now = new Date()): number {
  for (let offset = 0; offset <= 7; offset++) {
    const d = new Date(now);
    d.setDate(d.getDate() + offset);
    d.setHours(hour, minute, 0, 0);
    if (d.getTime() <= now.getTime()) continue;
    if (days.length === 0 || days.includes(d.getDay() as Weekday)) return d.getTime();
  }
  return now.getTime();
}
