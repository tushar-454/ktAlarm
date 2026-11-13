import KtAlarm, { type Alarm, type PermissionStatus } from 'kt-alarm';
import { useCallback, useEffect, useState } from 'react';
import { AppState } from 'react-native';

/** Live list of alarms from the native store. Refreshes on native events and app resume. */
export function useAlarms() {
  const [alarms, setAlarms] = useState<Alarm[]>(() => KtAlarm?.getAlarms() ?? []);

  const refresh = useCallback(() => {
    setAlarms(KtAlarm?.getAlarms() ?? []);
  }, []);

  useEffect(() => {
    if (!KtAlarm) return;
    const subs = [
      KtAlarm.addListener('onAlarmsChanged', refresh),
      KtAlarm.addListener('onRingingChanged', refresh),
      AppState.addEventListener('change', (s) => s === 'active' && refresh()),
    ];
    return () => subs.forEach((s) => s.remove());
  }, [refresh]);

  return { alarms, refresh };
}

const ALL_GRANTED: PermissionStatus = {
  notifications: true,
  exactAlarm: true,
  fullScreenIntent: true,
  batteryOptimizationIgnored: true,
  overlay: true,
};

/** Permission state, re-read whenever the user comes back from a settings screen. */
export function useAlarmPermissions() {
  const read = () => KtAlarm?.getPermissionStatus() ?? ALL_GRANTED;
  const [status, setStatus] = useState<PermissionStatus>(read);

  const refresh = useCallback(() => setStatus(KtAlarm?.getPermissionStatus() ?? ALL_GRANTED), []);

  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => s === 'active' && refresh());
    return () => sub.remove();
  }, [refresh]);

  return { status, refresh };
}

/** Re-render every 30 s so "rings in …" stays current. */
export function useNow(intervalMs = 30_000) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(t);
  }, [intervalMs]);
  return now;
}
