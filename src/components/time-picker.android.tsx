import { requireNativeView } from 'expo';
import KtAlarm from 'kt-alarm';
import { useState } from 'react';
import { Text, useColorScheme, View, type ViewProps } from 'react-native';

import type { TimePickerProps } from './time-picker.types';

type NativePickerProps = ViewProps & {
  initialHour: number;
  initialMinute: number;
  dark: boolean;
  onTimeChanged: (event: { nativeEvent: { hour: number; minute: number } }) => void;
};

const NativePicker = KtAlarm ? requireNativeView<NativePickerProps>('KtAlarm') : null;

/** Native cyclic wheels: drawing, momentum and snapping run without per-frame JS work. */
export function TimePicker({ hour, minute, onChange }: TimePickerProps) {
  const dark = useColorScheme() === 'dark';
  const [initial] = useState({ hour, minute });

  return (
    <View style={{ width: 280 }}>
      {NativePicker ? (
        <NativePicker initialHour={initial.hour} initialMinute={initial.minute} dark={dark}
          style={{ height: 204, width: '100%' }}
          onTimeChanged={({ nativeEvent }) => onChange(nativeEvent.hour, nativeEvent.minute)} />
      ) : (
        <Text className="py-10 text-center text-5xl text-neutral-900 dark:text-white">
          {String(hour).padStart(2, '0')}:{String(minute).padStart(2, '0')}
        </Text>
      )}
      <Text className="mt-2 text-center text-xs text-neutral-500 dark:text-neutral-400">
        Hours · Minutes (24-hour) · Swipe to set
      </Text>
    </View>
  );
}
