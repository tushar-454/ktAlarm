import { Text, TextInput, View } from 'react-native';

import type { TimePickerProps } from './time-picker.types';

/** Web fallback (alarms only work in native builds, but keep the screen usable). */
export function TimePicker({ hour, minute, onChange }: TimePickerProps) {
  const field = 'w-20 rounded-xl bg-neutral-100 py-3 text-center text-4xl text-neutral-900 dark:bg-neutral-800 dark:text-white';
  return (
    <View className="flex-row items-center justify-center gap-2">
      <TextInput
        className={field}
        keyboardType="number-pad"
        maxLength={2}
        defaultValue={String(hour).padStart(2, '0')}
        onChangeText={(t) => onChange(Math.min(23, Number(t) || 0), minute)}
      />
      <Text className="text-4xl text-neutral-900 dark:text-white">:</Text>
      <TextInput
        className={field}
        keyboardType="number-pad"
        maxLength={2}
        defaultValue={String(minute).padStart(2, '0')}
        onChangeText={(t) => onChange(hour, Math.min(59, Number(t) || 0))}
      />
    </View>
  );
}
