import { NativeModule, requireOptionalNativeModule } from 'expo';

/** 0 = Sunday … 6 = Saturday */
export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6;

export type MissionType = 'math' | 'shake' | 'squat' | 'typing' | 'barcode';

/** Math difficulty: 0 = easy … 3 = very hard. */
export type MathDifficulty = 0 | 1 | 2 | 3;

/** A task that must be completed before the ringing alarm can be turned off. */
export type Mission = {
  type: MissionType;
  /** Only used by `math`. */
  difficulty: MathDifficulty;
  /** Problems / shakes / squats / phrases. Always 1 for `barcode`. */
  count: number;
  /** `barcode` only: raw value of the registered code. Required for that type. */
  barcodeValue?: string | null;
  /** `barcode` only: what the user called it, e.g. "Toothpaste". */
  barcodeName?: string | null;
};

export type ScannedCode = { value: string; format: string };

/** `maxSnoozes` value meaning "no limit". */
export const UNLIMITED_SNOOZES = -1;

export type Alarm = {
  id: string;
  hour: number;
  minute: number;
  label: string;
  /** Repeat days. Empty = rings once. */
  days: Weekday[];
  enabled: boolean;
  vibrate: boolean;
  snoozeMinutes: number;
  /** Epoch ms of a pending snooze, if any. */
  snoozeUntil: number | null;
  /** Epoch ms of the next ring, null when disabled. */
  nextTriggerAt: number | null;
  /**
   * Missions required to dismiss (at most one for now). Android only for now — iOS
   * returns no field, so read it as `alarm.missions ?? []`.
   */
  missions?: Mission[];
  /** Snoozes allowed per ring, or `UNLIMITED_SNOOZES`. */
  maxSnoozes?: number;
  /** Snoozes used for the current ring. */
  snoozeCount?: number;
  /**
   * A mission alarm that is ringing or snoozed: it can't be switched off, edited or deleted
   * until the mission is done.
   */
  locked?: boolean;
};

export type AlarmInput = {
  id?: string;
  hour: number;
  minute: number;
  label?: string;
  days?: Weekday[];
  enabled?: boolean;
  vibrate?: boolean;
  snoozeMinutes?: number;
  missions?: Mission[];
  maxSnoozes?: number;
};

export type PermissionStatus = {
  /** Can post the ringing notification. */
  notifications: boolean;
  /** Can ring at the exact minute. */
  exactAlarm: boolean;
  /** Can wake the screen and show the alarm over the lock screen (Android 14+ can revoke). */
  fullScreenIntent: boolean;
  /** Excluded from Doze / battery optimisation. */
  batteryOptimizationIgnored: boolean;
  /** "Display over other apps" — lets the alarm screen open even while the phone is in use. */
  overlay: boolean;
};

type KtAlarmEvents = {
  onAlarmsChanged: () => void;
  onRingingChanged: (event: { alarmId: string | null }) => void;
};

declare class KtAlarmNativeModule extends NativeModule<KtAlarmEvents> {
  getAlarms(): Alarm[];
  saveAlarm(input: AlarmInput): Promise<Alarm>;
  deleteAlarm(id: string): Promise<void>;
  setAlarmEnabled(id: string, enabled: boolean): Promise<void>;
  /** Returns false when no snoozes are left. */
  snooze(id: string): boolean;
  /** Returns false for a locked mission alarm. */
  dismiss(id: string): boolean;
  /** Opens the native mission screen without ringing, to try a mission. Android only. */
  previewMission(mission: Mission): void;
  /**
   * Opens the native camera scanner to register a barcode mission's code. Resolves null when
   * cancelled; rejects with `ERR_CAMERA_PERMISSION` if the camera is denied. Android only.
   */
  scanBarcode(): Promise<ScannedCode | null>;

  /** Device-admin uninstall protection. Android only. */
  isUninstallProtectionEnabled(): boolean;
  /** Opens the system "activate device admin" screen. */
  enableUninstallProtection(): void;
  /** Rejects with `ERR_ALARM_LOCKED` while a mission alarm is ringing or snoozed. */
  disableUninstallProtection(): Promise<void>;
  getRingingAlarmId(): string | null;

  getPermissionStatus(): PermissionStatus;
  requestNotificationPermission(): Promise<{ granted: boolean }>;
  openExactAlarmSettings(): void;
  openFullScreenIntentSettings(): void;
  requestIgnoreBatteryOptimizations(): void;
  openOverlaySettings(): void;
  openNotificationSettings(): void;
  /** Opens the vendor "auto-start" screen (Xiaomi, Oppo, Vivo…). Android only. */
  openAutoStartSettings(): boolean;
}

/**
 * Null on platforms without the native module (web, Expo Go). The app needs a development
 * build: `npx expo run:android` / `npx expo run:ios`.
 */
/**
 * Error codes thrown by the native module:
 * - `ERR_ALARM_LOCKED`: mission alarm is ringing/snoozed; finish the mission first
 * - `ERR_OVERLAY_REQUIRED`: mission alarms need "Display over other apps"
 * - `ERR_INVALID_MISSION`: unknown mission type, or barcode mission without a code
 * - `ERR_CAMERA_PERMISSION`: camera denied while registering a code
 */
export function errorCode(e: unknown): string | undefined {
  return typeof e === 'object' && e !== null && 'code' in e ? String((e as { code: unknown }).code) : undefined;
}

const KtAlarm = requireOptionalNativeModule<KtAlarmNativeModule>('KtAlarm');

export default KtAlarm;
