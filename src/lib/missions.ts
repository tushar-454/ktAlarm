import type { MathDifficulty, Mission, MissionType } from 'kt-alarm';

type MissionInfo = {
  type: MissionType;
  title: string;
  emoji: string;
  /** Allowed `count` range and its default. Mirrors MissionType in Mission.kt. */
  min: number;
  max: number;
  step: number;
  defaultCount: number;
  unit: (count: number) => string;
};

export const MISSIONS: MissionInfo[] = [
  {
    type: 'math',
    title: 'Math',
    emoji: '🧮',
    min: 1,
    max: 10,
    step: 1,
    defaultCount: 3,
    unit: (n) => (n === 1 ? 'problem' : 'problems'),
  },
  {
    type: 'shake',
    title: 'Shake',
    emoji: '📳',
    min: 10,
    max: 100,
    step: 10,
    defaultCount: 30,
    unit: (n) => (n === 1 ? 'shake' : 'shakes'),
  },
  {
    type: 'walk',
    title: 'Walk',
    emoji: '🚶',
    min: 10,
    max: 300,
    step: 10,
    defaultCount: 30,
    unit: (n) => (n === 1 ? 'step' : 'steps'),
  },
  {
    type: 'squat',
    title: 'Squat',
    emoji: '🏋️',
    min: 5,
    max: 50,
    step: 5,
    defaultCount: 10,
    unit: (n) => (n === 1 ? 'squat' : 'squats'),
  },
  {
    type: 'typing',
    title: 'Typing',
    emoji: '⌨️',
    min: 1,
    max: 5,
    step: 1,
    defaultCount: 2,
    unit: (n) => (n === 1 ? 'phrase' : 'phrases'),
  },
  {
    type: 'barcode',
    title: 'Barcode',
    emoji: '📷',
    min: 1,
    max: 1,
    step: 1,
    defaultCount: 1,
    unit: () => 'code',
  },
];

export const MATH_DIFFICULTIES: { value: MathDifficulty; label: string; example: string }[] = [
  { value: 0, label: 'Easy', example: '4 + 7' },
  { value: 1, label: 'Medium', example: '23 + 48' },
  { value: 2, label: 'Hard', example: '17 × 6 + 31' },
  { value: 3, label: 'Very hard', example: '43 × 27' },
];

export function missionInfo(type: MissionType): MissionInfo {
  return MISSIONS.find((m) => m.type === type) ?? MISSIONS[0];
}

export function defaultMission(type: MissionType): Mission {
  return { type, difficulty: 1, count: missionInfo(type).defaultCount };
}

/** "Math · 3 problems (Medium)", "Barcode · Toothpaste" */
export function describeMission(m: Mission): string {
  const info = missionInfo(m.type);
  if (m.type === 'barcode') return `${info.title} · ${m.barcodeName?.trim() || 'registered code'}`;
  const base = `${info.title} · ${m.count} ${info.unit(m.count)}`;
  return m.type === 'math' ? `${base} (${MATH_DIFFICULTIES[m.difficulty]?.label ?? 'Medium'})` : base;
}

/** Snooze-limit choices; -1 = unlimited. */
export const MAX_SNOOZE_OPTIONS = [0, 1, 2, 3, 5, -1] as const;
/** Default limit once a mission is picked (normal alarms stay unlimited). */
export const MISSION_DEFAULT_MAX_SNOOZES = 3;
