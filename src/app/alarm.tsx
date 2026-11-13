import { router, useLocalSearchParams } from 'expo-router';
import KtAlarm, { errorCode, type Mission, type MissionType, type Weekday } from 'kt-alarm';
import { useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { TimePicker } from '@/components/time-picker';
import { useNow } from '@/hooks/use-alarms';
import { formatCountdown, previewNextTrigger, WEEK_ORDER, WEEKDAY_LETTER } from '@/lib/alarm-format';
import {
  defaultMission,
  MATH_DIFFICULTIES,
  MAX_SNOOZE_OPTIONS,
  MISSION_DEFAULT_MAX_SNOOZES,
  MISSIONS,
  missionInfo,
} from '@/lib/missions';

const ACCENT = '#FF8A3D';
const SNOOZE_OPTIONS = [5, 10, 15, 20];
/** Missions run on the native Android alarm screen; iOS support comes separately. */
const MISSIONS_SUPPORTED = Platform.OS === 'android';

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
  const [mission, setMission] = useState<Mission | null>(existing?.missions?.[0] ?? null);
  const [maxSnoozes, setMaxSnoozes] = useState(existing?.maxSnoozes ?? -1);
  // Until the user picks a snooze limit themselves, follow the mission default.
  const [maxSnoozesTouched, setMaxSnoozesTouched] = useState(
    existing?.maxSnoozes != null && existing.maxSnoozes !== -1,
  );
  const [saving, setSaving] = useState(false);
  const locked = existing?.locked === true;
  const now = useNow(15_000);

  const toggleDay = (d: Weekday) =>
    setDays((cur) => (cur.includes(d) ? cur.filter((x) => x !== d) : [...cur, d]));

  const pickMission = (type: MissionType | null) => {
    setMission(type ? (mission?.type === type ? mission : defaultMission(type)) : null);
    if (!maxSnoozesTouched) setMaxSnoozes(type ? MISSION_DEFAULT_MAX_SNOOZES : -1);
  };

  const save = async () => {
    if (!KtAlarm) return;
    if (mission?.type === 'barcode' && !mission.barcodeValue) {
      Alert.alert('Register a code first', 'Tap "Register code" and scan the barcode you want to use.');
      return;
    }
    if (mission && !KtAlarm.getPermissionStatus().overlay) {
      Alert.alert(
        'One more permission',
        'Mission alarms need "Display over other apps" so the mission screen comes back if you press Home. Allow it, then tap Save again.',
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Open settings', onPress: () => KtAlarm?.openOverlaySettings() },
        ],
      );
      return;
    }
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
        ...(MISSIONS_SUPPORTED ? { missions: mission ? [mission] : [], maxSnoozes } : {}),
      });
      router.back();
    } catch (e) {
      Alert.alert(
        errorCode(e) === 'ERR_ALARM_LOCKED' ? 'Alarm is locked' : 'Could not save alarm',
        e instanceof Error ? e.message : String(e),
      );
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
          try {
            if (existing) await KtAlarm?.deleteAlarm(existing.id);
            router.back();
          } catch (e) {
            Alert.alert('Could not delete alarm', e instanceof Error ? e.message : String(e));
          }
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
          disabled={saving || locked}
          hitSlop={8}
          accessibilityRole="button"
          className="min-w-[88px] items-center rounded-full px-5 py-2.5 active:opacity-60"
          style={{ backgroundColor: ACCENT, opacity: saving || locked ? 0.5 : 1 }}>
          <Text className="text-base font-semibold text-white">{saving ? 'Saving…' : 'Save'}</Text>
        </Pressable>
      </View>

      <ScrollView
        contentContainerStyle={{ paddingBottom: insets.bottom + 32 }}
        keyboardShouldPersistTaps="handled">
        {locked && (
          <View className="mx-5 mt-2 rounded-2xl bg-orange-50 p-4 dark:bg-[#2A1C12]">
            <Text className="text-sm font-medium text-orange-900 dark:text-orange-200">
              🔒 This alarm is ringing or snoozed. Complete its mission to turn it off — until then it
              can&apos;t be changed or deleted.
            </Text>
          </View>
        )}
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

        {/* Mission */}
        {MISSIONS_SUPPORTED && (
          <Section title="Wake-up mission">
            <View className="flex-row flex-wrap gap-2">
              <Chip label="None" active={!mission} onPress={() => pickMission(null)} />
              {MISSIONS.map((m) => (
                <Chip
                  key={m.type}
                  label={`${m.emoji} ${m.title}`}
                  active={mission?.type === m.type}
                  onPress={() => pickMission(m.type)}
                />
              ))}
            </View>
            {mission ? (
              <MissionSettings mission={mission} onChange={setMission} />
            ) : (
              <Text className="mt-3 text-sm text-neutral-500 dark:text-neutral-400">
                Add a mission and the alarm won&apos;t stop until you complete it.
              </Text>
            )}
          </Section>
        )}

        {/* Snooze */}
        <Section title="Snooze length">
          <View className="flex-row gap-2">
            {SNOOZE_OPTIONS.map((m) => (
              <Chip key={m} label={`${m} min`} active={snoozeMinutes === m} onPress={() => setSnoozeMinutes(m)} />
            ))}
          </View>
        </Section>

        {MISSIONS_SUPPORTED && (
          <Section title="Max snoozes">
            <View className="flex-row flex-wrap gap-2">
              {MAX_SNOOZE_OPTIONS.map((n) => (
                <Chip
                  key={n}
                  label={n === -1 ? '∞' : String(n)}
                  active={maxSnoozes === n}
                  onPress={() => {
                    setMaxSnoozes(n);
                    setMaxSnoozesTouched(true);
                  }}
                />
              ))}
            </View>
          </Section>
        )}

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

        {existing && !locked && (
          <Pressable onPress={remove} className="mx-5 mt-8 items-center rounded-2xl py-3.5 active:opacity-60">
            <Text className="text-base font-medium text-red-500">Delete alarm</Text>
          </Pressable>
        )}
      </ScrollView>
    </View>
  );
}

function MissionSettings({ mission, onChange }: { mission: Mission; onChange: (m: Mission) => void }) {
  if (mission.type === 'barcode') return <BarcodeSettings mission={mission} onChange={onChange} />;
  const info = missionInfo(mission.type);
  const setCount = (count: number) =>
    onChange({ ...mission, count: Math.min(info.max, Math.max(info.min, count)) });

  return (
    <View className="mt-3 rounded-2xl bg-neutral-100 p-4 dark:bg-[#1A1F27]">
      {mission.type === 'math' && (
        <>
          <Text className="mb-2 text-sm font-medium text-neutral-700 dark:text-neutral-300">Difficulty</Text>
          <View className="mb-1 flex-row flex-wrap gap-2">
            {MATH_DIFFICULTIES.map((d) => (
              <Chip
                key={d.value}
                label={d.label}
                active={mission.difficulty === d.value}
                onPress={() => onChange({ ...mission, difficulty: d.value })}
              />
            ))}
          </View>
          <Text className="mb-4 text-xs text-neutral-500 dark:text-neutral-400">
            e.g. {MATH_DIFFICULTIES[mission.difficulty]?.example}
          </Text>
        </>
      )}

      <View className="flex-row items-center justify-between">
        <Text className="text-base text-neutral-900 dark:text-white">
          {mission.count} {info.unit(mission.count)}
        </Text>
        <View className="flex-row gap-2">
          <StepButton label="−" disabled={mission.count <= info.min} onPress={() => setCount(mission.count - info.step)} />
          <StepButton label="+" disabled={mission.count >= info.max} onPress={() => setCount(mission.count + info.step)} />
        </View>
      </View>

      <PreviewButton mission={mission} />
    </View>
  );
}

function BarcodeSettings({ mission, onChange }: { mission: Mission; onChange: (m: Mission) => void }) {
  const [scanning, setScanning] = useState(false);
  const registered = !!mission.barcodeValue;

  const scan = async () => {
    if (!KtAlarm) return;
    setScanning(true);
    try {
      const code = await KtAlarm.scanBarcode();
      if (code) {
        onChange({ ...mission, barcodeValue: code.value, barcodeName: mission.barcodeName || 'My code' });
      }
    } catch (e) {
      if (errorCode(e) === 'ERR_CAMERA_PERMISSION') {
        Alert.alert('Camera is off', 'Allow camera access for ktAlerm in Settings to register a code.');
      } else {
        Alert.alert('Could not scan', e instanceof Error ? e.message : String(e));
      }
    } finally {
      setScanning(false);
    }
  };

  return (
    <View className="mt-3 rounded-2xl bg-neutral-100 p-4 dark:bg-[#1A1F27]">
      <Text className="text-sm text-neutral-600 dark:text-neutral-400">
        Scan a code somewhere away from your bed — toothpaste, shampoo, a coffee bag. To turn the alarm
        off you&apos;ll have to get up and scan the same code.
      </Text>

      {registered && (
        <>
          <Text className="mb-2 mt-4 text-sm font-medium text-neutral-700 dark:text-neutral-300">Name</Text>
          <TextInput
            value={mission.barcodeName ?? ''}
            onChangeText={(t) => onChange({ ...mission, barcodeName: t })}
            placeholder="Toothpaste"
            placeholderTextColor="#9CA3AF"
            maxLength={40}
            className="rounded-xl bg-white px-4 py-3 text-base text-neutral-900 dark:bg-[#0E1116] dark:text-white"
          />
          <Text className="mt-2 text-xs text-neutral-500 dark:text-neutral-400" numberOfLines={1}>
            ✓ Code registered · …{mission.barcodeValue!.slice(-8)}
          </Text>
        </>
      )}

      <Pressable
        onPress={scan}
        disabled={scanning}
        accessibilityRole="button"
        className="mt-4 items-center rounded-full py-2.5 active:opacity-60"
        style={{ backgroundColor: ACCENT, opacity: scanning ? 0.5 : 1 }}>
        <Text className="text-sm font-semibold text-white">{registered ? 'Scan a different code' : 'Register code'}</Text>
      </Pressable>

      {registered && <PreviewButton mission={mission} />}
    </View>
  );
}

function PreviewButton({ mission }: { mission: Mission }) {
  return (
    <Pressable
      onPress={() => KtAlarm?.previewMission(mission)}
      accessibilityRole="button"
      className="mt-4 items-center rounded-full border-2 py-2.5 active:opacity-60"
      style={{ borderColor: ACCENT }}>
      <Text className="text-sm font-semibold" style={{ color: ACCENT }}>
        Preview mission
      </Text>
    </Pressable>
  );
}

function StepButton({ label, disabled, onPress }: { label: string; disabled: boolean; onPress: () => void }) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      className="h-10 w-10 items-center justify-center rounded-full bg-white active:opacity-60 dark:bg-[#0E1116]"
      style={{ opacity: disabled ? 0.4 : 1 }}>
      <Text className="text-xl font-semibold text-neutral-900 dark:text-white">{label}</Text>
    </Pressable>
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
