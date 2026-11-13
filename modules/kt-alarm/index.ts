import { NativeModule, requireOptionalNativeModule } from 'expo';

/** 0 = Sunday … 6 = Saturday */
export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6;

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
  snooze(id: string): void;
  dismiss(id: string): void;
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
const KtAlarm = requireOptionalNativeModule<KtAlarmNativeModule>('KtAlarm');

export default KtAlarm;
