import KtAlarm, { type SleepSoundState } from 'kt-alarm';
import { useEffect, useState } from 'react';
import { AppState, Platform } from 'react-native';

export const SLEEP_SUPPORTED = Platform.OS === 'android' && !!KtAlarm;
export function readSleepSound(): SleepSoundState | null {
  return SLEEP_SUPPORTED ? KtAlarm!.getSleepSoundState() : null;
}

export function useSleepSound() {
  const [state, setState] = useState(readSleepSound);
  useEffect(() => {
    if (!SLEEP_SUPPORTED || !KtAlarm) return;
    const native = KtAlarm.addListener('onSleepSoundChanged', setState);
    const resume = AppState.addEventListener('change', (s) => {
      if (s === 'active') setState(readSleepSound());
    });
    return () => { native.remove(); resume.remove(); };
  }, []);
  return state;
}
