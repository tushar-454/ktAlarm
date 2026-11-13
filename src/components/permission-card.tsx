import KtAlarm, { type PermissionStatus } from 'kt-alarm';
import { Platform, Pressable, Text, View } from 'react-native';

type Item = {
  key: keyof PermissionStatus;
  title: string;
  why: string;
  fix: () => void;
};

const ITEMS: Item[] = [
  {
    key: 'notifications',
    title: 'Notifications',
    why: 'Needed to show the ringing alarm.',
    fix: async () => {
      const res = await KtAlarm?.requestNotificationPermission();
      if (!res?.granted) KtAlarm?.openNotificationSettings();
    },
  },
  {
    key: 'exactAlarm',
    title: 'Alarms & reminders',
    why: 'Rings at the exact minute.',
    fix: () => KtAlarm?.openExactAlarmSettings(),
  },
  {
    key: 'fullScreenIntent',
    title: 'Full-screen alarm',
    why: 'Turns on the screen over the lock screen.',
    fix: () => KtAlarm?.openFullScreenIntentSettings(),
  },
  {
    key: 'batteryOptimizationIgnored',
    title: 'Unrestricted battery',
    why: 'Stops the system from killing alarms.',
    fix: () => KtAlarm?.requestIgnoreBatteryOptimizations(),
  },
  {
    key: 'overlay',
    title: 'Display over other apps',
    why: 'Opens the alarm screen even while you use your phone.',
    fix: () => KtAlarm?.openOverlaySettings(),
  },
];

export function PermissionCard({ status }: { status: PermissionStatus }) {
  const missing = ITEMS.filter((i) => !status[i.key]);
  if (missing.length === 0 || !KtAlarm) return null;

  return (
    <View className="mx-5 mb-4 rounded-3xl bg-orange-50 p-4 dark:bg-[#2A1C12]">
      <Text className="text-base font-semibold text-orange-900 dark:text-orange-200">
        Alarms might not ring reliably
      </Text>
      <Text className="mt-1 text-sm text-orange-800/80 dark:text-orange-200/70">
        Allow these so alarms ring when the app is closed and the phone is locked.
      </Text>

      {missing.map((item) => (
        <View key={item.key} className="mt-3 flex-row items-center gap-3">
          <View className="flex-1">
            <Text className="text-[15px] font-medium text-neutral-900 dark:text-white">{item.title}</Text>
            <Text className="text-xs text-neutral-600 dark:text-neutral-400">{item.why}</Text>
          </View>
          <Pressable
            onPress={item.fix}
            className="rounded-full bg-[#FF8A3D] px-4 py-2 active:opacity-70"
            accessibilityRole="button"
            accessibilityLabel={`Allow ${item.title}`}>
            <Text className="text-sm font-semibold text-white">Allow</Text>
          </Pressable>
        </View>
      ))}

      {Platform.OS === 'android' && (
        <Pressable onPress={() => KtAlarm?.openAutoStartSettings()} className="mt-4 active:opacity-60">
          <Text className="text-xs font-medium text-orange-700 underline dark:text-orange-300">
            Xiaomi, Oppo, Vivo, Huawei or Samsung? Also turn on Auto-start
          </Text>
        </Pressable>
      )}
    </View>
  );
}
