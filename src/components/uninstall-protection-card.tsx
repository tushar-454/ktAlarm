import KtAlarm, { errorCode } from 'kt-alarm';
import { useCallback, useEffect, useState } from 'react';
import { Alert, AppState, Platform, Switch, Text, View } from 'react-native';

const ACCENT = '#FF8A3D';

/**
 * Opt-in device-admin protection so the app can't simply be uninstalled to skip a mission.
 * Android only — iOS has no equivalent.
 */
export function UninstallProtectionCard() {
  const read = () => KtAlarm?.isUninstallProtectionEnabled?.() ?? false;
  const [enabled, setEnabled] = useState(read);

  const refresh = useCallback(() => setEnabled(KtAlarm?.isUninstallProtectionEnabled?.() ?? false), []);

  useEffect(() => {
    if (!KtAlarm) return;
    const subs = [
      // Coming back from the system "activate device admin" screen.
      AppState.addEventListener('change', (s) => s === 'active' && refresh()),
      KtAlarm.addListener('onAlarmsChanged', refresh),
    ];
    return () => subs.forEach((s) => s.remove());
  }, [refresh]);

  if (Platform.OS !== 'android' || !KtAlarm) return null;

  const toggle = async (on: boolean) => {
    if (on) {
      KtAlarm?.enableUninstallProtection();
      return;
    }
    try {
      await KtAlarm?.disableUninstallProtection();
      refresh();
    } catch (e) {
      Alert.alert(
        errorCode(e) === 'ERR_ALARM_LOCKED' ? 'Not right now' : 'Could not turn off',
        errorCode(e) === 'ERR_ALARM_LOCKED'
          ? 'A mission alarm is ringing or snoozed. Complete its mission first.'
          : e instanceof Error
            ? e.message
            : String(e),
      );
    }
  };

  return (
    <View className="mx-5 mt-2 flex-row items-center rounded-3xl bg-neutral-100 px-5 py-4 dark:bg-[#1A1F27]">
      <View className="mr-3 flex-1">
        <Text className="text-base font-semibold text-neutral-900 dark:text-white">🛡️ Uninstall protection</Text>
        <Text className="mt-0.5 text-sm text-neutral-500 dark:text-neutral-400">
          Stops ktAlerm from being uninstalled to skip a wake-up mission.
        </Text>
      </View>
      <Switch
        value={enabled}
        onValueChange={toggle}
        trackColor={{ true: ACCENT, false: '#9CA3AF55' }}
        thumbColor="#FFFFFF"
        accessibilityLabel="Uninstall protection"
      />
    </View>
  );
}
