import { router, useLocalSearchParams } from 'expo-router';
import KtAlarm, { type Weekday } from 'kt-alarm';
import { useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { TimePicker } from '@/components/time-picker';
import { useNow } from '@/hooks/use-alarms';
import { formatCountdown, previewNextTrigger, WEEK_ORDER, WEEKDAY_LETTER } from '@/lib/alarm-format';

const ACCENT = '#FF8A3D';
const SNOOZE_OPTIONS = [5, 10, 15, 20];

function defaultTime() {
  const d = new Date(Date.now() + 60 * 60 * 1000);
  return { hour: d.getHours(), minute: 0 };
}

export default function AlarmEditorScreen() {
  const insets = useSafeAreaInsets();
  const { id } = useLocalSearchParams<{ id?: string }>();
  const existing = id ? KtAlarm?.getAlarms().find((a) => a.id === id) : undefined;

  const [hour, setHour] = useState(existing?.hour ?? defaultTime().hour);
  const [minute, setMinute] = useState(existing?.minute ?? defaultTime().minute);
  const [days, setDays] = useState<Weekday[]>(existing?.days ?? []);
  const [label, setLabel] = useState(existing?.label ?? '');
  const [snoozeMinutes, setSnoozeMinutes] = useState(existing?.snoozeMinutes ?? 5);
  const [vibrate, setVibrate] = useState(existing?.vibrate ?? true);
  const [saving, setSaving] = useState(false);
  const now = useNow(15_000);

  const toggleDay = (d: Weekday) =>
    setDays((cur) => (cur.includes(d) ? cur.filter((x) => x !== d) : [...cur, d]));

  const save = async () => {
    if (!KtAlarm) return;
    setSaving(true);
    try {
      if (!KtAlarm.getPermissionStatus().notifications) {
        await KtAlarm.requestNotificationPermission();
      }
      await KtAlarm.saveAlarm({
        id: existing?.id,
        hour,
        minute,
        days,
        label,
        snoozeMinutes,
        vibrate,
        enabled: true,
      });
      router.back();
    } catch (e) {
      Alert.alert('Could not save alarm', e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = () =>
    Alert.alert('Delete alarm?', undefined, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Delete',
        style: 'destructive',
        onPress: async () => {
          if (existing) await KtAlarm?.deleteAlarm(existing.id);
          router.back();
        },
      },
    ]);

  const nextRing = previewNextTrigger(hour, minute, days, new Date(now));

  return (
    <View className="flex-1 bg-white dark:bg-[#0E1116]">
      {/* Top bar */}
      <View
        className="flex-row items-center justify-between px-4 pb-3"
        // Android shows this modal full-screen (edge-to-edge), so push the bar below the
        // status bar; on iOS it's a sheet that already starts below it.
        style={{ paddingTop: (Platform.OS === 'android' ? insets.top : 0) + 12 }}>
        <Pressable
          onPress={() => router.back()}
          hitSlop={8}
          accessibilityRole="button"
          className="min-w-[88px] items-center rounded-full bg-neutral-100 px-5 py-2.5 active:opacity-60 dark:bg-[#1A1F27]">
          <Text className="text-base font-medium text-neutral-700 dark:text-neutral-300">Cancel</Text>
        </Pressable>
        <Text className="text-base font-semibold text-neutral-900 dark:text-white">
          {existing ? 'Edit alarm' : 'New alarm'}
        </Text>
        <Pressable
          onPress={save}
          disabled={saving}
          hitSlop={8}
          accessibilityRole="button"
          className="min-w-[88px] items-center rounded-full px-5 py-2.5 active:opacity-60"
          style={{ backgroundColor: ACCENT, opacity: saving ? 0.5 : 1 }}>
          <Text className="text-base font-semibold text-white">{saving ? 'Saving…' : 'Save'}</Text>
        </Pressable>
      </View>

      <ScrollView
        contentContainerStyle={{ paddingBottom: insets.bottom + 32 }}
        keyboardShouldPersistTaps="handled">
        <View className="items-center pt-4">
          <TimePicker
            hour={hour}
            minute={minute}
            onChange={(h, m) => {
              setHour(h);
              setMinute(m);
            }}
          />
          <Text className="mt-3 text-sm text-neutral-500 dark:text-neutral-400">
            Alarm will ring {formatCountdown(nextRing, now)}
          </Text>
        </View>

        {/* Repeat */}
        <Section title="Repeat">
          <View className="flex-row justify-between">
            {WEEK_ORDER.map((d) => {
              const on = days.includes(d);
              return (
                <Pressable
                  key={d}
                  onPress={() => toggleDay(d)}
                  accessibilityRole="checkbox"
                  accessibilityState={{ checked: on }}
                  className={`h-11 w-11 items-center justify-center rounded-full ${on ? '' : 'bg-neutral-100 dark:bg-[#1A1F27]'}`}
                  style={on ? { backgroundColor: ACCENT } : undefined}>
                  <Text className={`font-semibold ${on ? 'text-white' : 'text-neutral-700 dark:text-neutral-300'}`}>
                    {WEEKDAY_LETTER[d]}
                  </Text>
                </Pressable>
              );
            })}
          </View>
          <View className="mt-3 flex-row gap-2">
            <Chip label="Once" active={days.length === 0} onPress={() => setDays([])} />
            <Chip
              label="Weekdays"
              active={days.length === 5 && !days.includes(0) && !days.includes(6)}
              onPress={() => setDays([1, 2, 3, 4, 5])}
            />
            <Chip label="Every day" active={days.length === 7} onPress={() => setDays([0, 1, 2, 3, 4, 5, 6])} />
          </View>
        </Section>

        {/* Label */}
        <Section title="Label">
          <TextInput
            value={label}
            onChangeText={setLabel}
            placeholder="Wake up"
            placeholderTextColor="#9CA3AF"
            maxLength={40}
            className="rounded-2xl bg-neutral-100 px-4 py-3.5 text-base text-neutral-900 dark:bg-[#1A1F27] dark:text-white"
          />
        </Section>

        {/* Snooze */}
        <Section title="Snooze length">
          <View className="flex-row gap-2">
            {SNOOZE_OPTIONS.map((m) => (
              <Chip key={m} label={`${m} min`} active={snoozeMinutes === m} onPress={() => setSnoozeMinutes(m)} />
            ))}
          </View>
        </Section>

        {/* Vibrate */}
        <View className="mx-5 mt-6 flex-row items-center justify-between rounded-2xl bg-neutral-100 px-4 py-3 dark:bg-[#1A1F27]">
          <Text className="text-base text-neutral-900 dark:text-white">Vibrate</Text>
          <Switch
            value={vibrate}
            onValueChange={setVibrate}
            trackColor={{ true: ACCENT, false: '#9CA3AF55' }}
            thumbColor="#FFFFFF"
          />
        </View>

        {existing && (
          <Pressable onPress={remove} className="mx-5 mt-8 items-center rounded-2xl py-3.5 active:opacity-60">
            <Text className="text-base font-medium text-red-500">Delete alarm</Text>
          </Pressable>
        )}
      </ScrollView>
    </View>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View className="mx-5 mt-7">
      <Text className="mb-3 text-xs font-semibold uppercase tracking-widest text-neutral-500 dark:text-neutral-400">
        {title}
      </Text>
      {children}
    </View>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      className={`rounded-full px-4 py-2 ${active ? '' : 'bg-neutral-100 dark:bg-[#1A1F27]'}`}
      style={active ? { backgroundColor: ACCENT } : undefined}>
      <Text className={`text-sm font-medium ${active ? 'text-white' : 'text-neutral-700 dark:text-neutral-300'}`}>
        {label}
      </Text>
    </Pressable>
  );
}
