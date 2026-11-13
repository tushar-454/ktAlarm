import { DateTimePicker, Host } from '@expo/ui/jetpack-compose';
import { useState } from 'react';
import { useColorScheme } from 'react-native';

import type { TimePickerProps } from './time-picker.types';

/** Material 3 clock-dial time picker (Jetpack Compose). */
export function TimePicker({ hour, minute, onChange }: TimePickerProps) {
  const scheme = useColorScheme();
  // Only the initial value is read by the native picker; keep it stable across renders.
  const [initialDate] = useState(() => {
    const d = new Date();
    d.setHours(hour, minute, 0, 0);
    return d.toISOString();
  });

  return (
    <Host matchContents colorScheme={scheme === 'dark' ? 'dark' : 'light'}>
      <DateTimePicker
        displayedComponents="hourAndMinute"
        initialDate={initialDate}
        is24Hour={false}
        showVariantToggle={false}
        color="#FF8A3D"
        onDateSelected={(date) => {
          if (date) onChange(date.getHours(), date.getMinutes());
        }}
      />
    </Host>
  );
}
