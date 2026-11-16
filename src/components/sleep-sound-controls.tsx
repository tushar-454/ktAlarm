import KtAlarm, { type AlarmMedia } from 'kt-alarm';
import { useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, Text, TextInput, View } from 'react-native';

import { useNow } from '@/hooks/use-alarms';
import { SLEEP_SUPPORTED, useSleepSound } from '@/hooks/use-sleep-sound';
import { SLEEP_PRESETS } from '@/lib/media-presets';

export function SleepSoundControls({ sound, minutes, onSoundChange, onMinutesChange, alarmId,
  disabled = false, onBusyChange }: {
  sound: AlarmMedia; minutes: number; onSoundChange: (sound: AlarmMedia) => void;
  onMinutesChange: (minutes: number) => void; alarmId?: string; disabled?: boolean;
  onBusyChange?: (busy: boolean) => void;
}) {
  const state = useSleepSound();
  const [busy, setBusy] = useState(false);
  const [duration, setDuration] = useState(String(minutes));
  const active = state?.playing || state?.preparing;
  const now = useNow(active ? 1000 : 60_000);
  const seconds = Math.max(0, Math.ceil(((state?.endsAt ?? now) - now) / 1000));
  const validMinutes = Number(duration) >= 1 && Number(duration) <= 720;
  const unavailable = disabled || busy || !SLEEP_SUPPORTED;

  const error = (e: unknown) => Alert.alert('Sleep sounds', e instanceof Error ? e.message : String(e));
  const chooseFile = async () => {
    if (!KtAlarm || unavailable) return;
    setBusy(true); onBusyChange?.(true);
    try {
      const selected = await KtAlarm.pickAlarmMedia('audio');
      if (selected) onSoundChange(selected);
    } catch (e) { error(e); }
    finally { setBusy(false); onBusyChange?.(false); }
  };
  const start = async () => {
    if (!KtAlarm || !validMinutes) return;
    setBusy(true);
    try {
      if (Platform.OS === 'android' && !KtAlarm.getPermissionStatus().notifications) await KtAlarm.requestNotificationPermission();
      await KtAlarm.startSleepSound({ ...sound, minutes: Number(duration), alarmId });
    } catch (e) { error(e); }
    finally { setBusy(false); }
  };

  return (
    <View className="rounded-2xl bg-neutral-100 p-4 dark:bg-[#1A1F27]">
      <Text className="mb-3 text-sm text-neutral-600 dark:text-neutral-400">
        Relax before sleep. Sounds loop in the background until the timer ends. Your wake-up alarm takes priority.
      </Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="handled" style={{ flexGrow: 0 }}
        contentContainerStyle={{ alignItems: 'center', gap: 8 }}>
        {SLEEP_PRESETS.map((preset) => (
          <Choice key={preset.uri} label={preset.name} active={sound.uri === preset.uri}
            disabled={unavailable} onPress={() => onSoundChange(preset)} />
        ))}
      </ScrollView>
      <Pressable onPress={chooseFile} disabled={unavailable} accessibilityRole="button" className="mt-3 py-2">
        <Text className="font-semibold text-[#FF8A3D]">{busy ? 'Please wait…' : 'Choose audio from phone'}</Text>
      </Pressable>
      <Text className="mt-1 text-sm text-neutral-900 dark:text-white" numberOfLines={1}>{sound.name}</Text>
      <Text className="mb-2 mt-5 text-sm font-medium text-neutral-700 dark:text-neutral-300">Sleep timer</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false}
        keyboardShouldPersistTaps="handled" style={{ flexGrow: 0 }}
        contentContainerStyle={{ alignItems: 'center', gap: 8 }}>
        {[15, 30, 45, 60, 90, 120].map((n) => (
          <Choice key={n} label={`${n} min`} active={minutes === n} disabled={unavailable}
            onPress={() => { setDuration(String(n)); onMinutesChange(n); }} />
        ))}
      </ScrollView>
      <View className="mt-3 flex-row items-center gap-3">
        <TextInput value={duration} keyboardType="number-pad" maxLength={3} editable={!unavailable}
          accessibilityLabel="Sleep sound duration in minutes" selectTextOnFocus
          onChangeText={(text) => {
            const digits = text.replace(/\D/g, '');
            setDuration(digits);
            const n = Number(digits);
            if (n >= 1 && n <= 720) onMinutesChange(n);
          }}
          className="w-20 rounded-xl bg-white px-3 py-3 text-center text-base text-neutral-900 dark:bg-[#0E1116] dark:text-white" />
        <Text className="text-sm text-neutral-500 dark:text-neutral-400">minutes · 1 to 720</Text>
      </View>
      {active && (
        <Text className="mt-4 text-sm font-medium text-neutral-900 dark:text-white">
          {state.preparing ? 'Preparing…' : `${state.name} · ${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} remaining`}
        </Text>
      )}
      {state?.error && <Text className="mt-3 text-sm text-red-500">{state.error}</Text>}
      <View className="mt-4 flex-row gap-3">
        <Pressable onPress={start} disabled={unavailable || !validMinutes || state?.preparing}
          accessibilityRole="button" className="flex-1 items-center rounded-full bg-[#FF8A3D] px-4 py-3"
          style={{ opacity: unavailable || !validMinutes || state?.preparing ? 0.5 : 1 }}>
          <Text className="font-semibold text-white">{active ? 'Restart with selection' : 'Start sleep sound'}</Text>
        </Pressable>
        {active && (
          <Pressable onPress={() => KtAlarm?.stopSleepSound().catch(error)} accessibilityRole="button"
            className="items-center rounded-full bg-white px-5 py-3 dark:bg-[#0E1116]">
            <Text className="font-semibold text-neutral-900 dark:text-white">Stop</Text>
          </Pressable>
        )}
      </View>
    </View>
  );
}

function Choice({ label, active, disabled, onPress }: {
  label: string; active: boolean; disabled: boolean; onPress: () => void;
}) {
  return (
    <Pressable onPress={onPress} disabled={disabled} accessibilityRole="button"
      accessibilityState={{ selected: active, disabled }}
      className={`shrink-0 rounded-full border px-3 py-2 ${active ? 'border-[#FF8A3D] bg-[#FF8A3D]' : 'border-neutral-300 dark:border-neutral-600'}`}>
      <Text numberOfLines={1} className={`text-sm ${active ? 'text-white' : 'text-neutral-700 dark:text-neutral-300'}`}>{label}</Text>
    </Pressable>
  );
}
