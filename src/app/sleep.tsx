import { router, useLocalSearchParams } from 'expo-router';
import KtAlarm, { type AlarmMedia } from 'kt-alarm';
import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { SleepSoundControls } from '@/components/sleep-sound-controls';
import { readSleepSound } from '@/hooks/use-sleep-sound';
import { SLEEP_PRESETS } from '@/lib/media-presets';

export default function SleepSoundsScreen() {
  const insets = useSafeAreaInsets();
  const { id } = useLocalSearchParams<{ id?: string }>();
  const [initial] = useState(() => ({ alarm: id ? KtAlarm?.getAlarms().find((a) => a.id === id) : null,
    playback: readSleepSound() }));
  const [sound, setSound] = useState<AlarmMedia>(() => initial.alarm?.sleepSoundUri
    ? { uri: initial.alarm.sleepSoundUri, name: initial.alarm.sleepSoundName || 'Sleep sound' }
    : initial.playback ? { uri: initial.playback.uri, name: initial.playback.name } : SLEEP_PRESETS[1]);
  const [minutes, setMinutes] = useState(initial.alarm?.sleepSoundMinutes ?? initial.playback?.minutes ?? 30);
  return (
    <View className="flex-1 bg-white dark:bg-[#0E1116]" style={{ paddingTop: insets.top }}>
      <View className="flex-row items-center px-5 py-4">
        <Pressable onPress={() => router.back()} accessibilityRole="button" hitSlop={12}>
          <Text className="text-base font-medium text-[#FF8A3D]">Back</Text>
        </Pressable>
        <Text className="ml-6 text-xl font-semibold text-neutral-900 dark:text-white">Sleep sounds</Text>
      </View>
      <ScrollView contentContainerStyle={{ padding: 20, paddingBottom: insets.bottom + 32 }}
        keyboardShouldPersistTaps="handled">
        {initial.alarm && <Text className="mb-4 text-sm text-neutral-500 dark:text-neutral-400">
          For {initial.alarm.label || 'your alarm'} · {initial.alarm.enabled
            ? 'your wake-up alarm remains scheduled.' : 'this alarm is off. Turn it on in alarm settings for a wake-up alarm.'}
        </Text>}
        {initial.alarm && (
          <Pressable onPress={() => router.push({ pathname: '/alarm', params: { id } })}
            accessibilityRole="button" className="mb-4 self-start py-2">
            <Text className="font-semibold text-[#FF8A3D]">Alarm settings</Text>
          </Pressable>
        )}
        <SleepSoundControls sound={sound} minutes={minutes} onSoundChange={setSound}
          onMinutesChange={setMinutes} alarmId={id} />
      </ScrollView>
    </View>
  );
}
