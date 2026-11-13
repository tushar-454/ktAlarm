import { router } from 'expo-router';
import KtAlarm, { type Alarm } from 'kt-alarm';
import { Alert, FlatList, Pressable, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { PermissionCard } from '@/components/permission-card';
import { UninstallProtectionCard } from '@/components/uninstall-protection-card';
import { useAlarmPermissions, useAlarms, useNow } from '@/hooks/use-alarms';
import { formatCountdown, formatDays, formatTime } from '@/lib/alarm-format';
import { describeMission, missionInfo } from '@/lib/missions';

const ACCENT = '#FF8A3D';

export default function AlarmsScreen() {
  const insets = useSafeAreaInsets();
  const { alarms } = useAlarms();
  const { status } = useAlarmPermissions();
  const now = useNow();

  const next = alarms
    .filter((a) => a.enabled && a.nextTriggerAt)
    .sort((a, b) => a.nextTriggerAt! - b.nextTriggerAt!)[0];

  return (
    <View className="flex-1 bg-white dark:bg-[#0E1116]" style={{ paddingTop: insets.top }}>
      <FlatList
        data={alarms}
        keyExtractor={(a) => a.id}
        contentContainerStyle={{ paddingBottom: insets.bottom + 120 }}
        ListHeaderComponent={
          <>
            <View className="px-6 pb-6 pt-8">
              <Text className="text-4xl font-bold text-neutral-900 dark:text-white">Alarms</Text>
              <Text className="mt-1 text-base text-neutral-500 dark:text-neutral-400">
                {next?.nextTriggerAt
                  ? `Next alarm ${formatCountdown(next.nextTriggerAt, now)}`
                  : 'No alarms on'}
              </Text>
            </View>
            <PermissionCard status={status} />
            {!KtAlarm && (
              <Text className="mx-6 mb-4 text-sm text-red-500">
                Native alarm module not found. Run a development build: npx expo run:android
              </Text>
            )}
          </>
        }
        ListEmptyComponent={<EmptyState />}
        ListFooterComponent={alarms.length > 0 ? <UninstallProtectionCard /> : undefined}
        renderItem={({ item }) => <AlarmRow alarm={item} now={now} />}
      />

      <Pressable
        onPress={() => router.push('/alarm')}
        accessibilityRole="button"
        accessibilityLabel="Add alarm"
        className="absolute right-6 h-16 w-16 items-center justify-center rounded-full active:scale-95"
        style={{
          bottom: insets.bottom + 24,
          backgroundColor: ACCENT,
          boxShadow: '0 6px 16px rgba(255, 138, 61, 0.45)',
        }}>
        <PlusIcon />
      </Pressable>
    </View>
  );
}

function AlarmRow({ alarm, now }: { alarm: Alarm; now: number }) {
  const { time, period } = formatTime(alarm.hour, alarm.minute);
  const snoozed = alarm.snoozeUntil && alarm.snoozeUntil > now;
  const mission = alarm.missions?.[0];

  const showError = (e: unknown) => Alert.alert('Alarm is locked', e instanceof Error ? e.message : String(e));

  const confirmDelete = () =>
    alarm.locked
      ? showError(new Error('Complete the wake-up mission to turn off this alarm.'))
      : Alert.alert('Delete alarm?', `${time} ${period}${alarm.label ? ` · ${alarm.label}` : ''}`, [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Delete',
            style: 'destructive',
            onPress: () => KtAlarm?.deleteAlarm(alarm.id).catch(showError),
          },
        ]);

  return (
    <Pressable
      onPress={() => router.push({ pathname: '/alarm', params: { id: alarm.id } })}
      onLongPress={confirmDelete}
      className="mx-5 mb-3 flex-row items-center rounded-3xl bg-neutral-100 px-5 py-4 active:opacity-80 dark:bg-[#1A1F27]">
      <View className="flex-1">
        <View className="flex-row items-baseline">
          <Text
            className={`text-[44px] font-light ${alarm.enabled ? 'text-neutral-900 dark:text-white' : 'text-neutral-400 dark:text-neutral-600'}`}
            style={{ fontVariant: ['tabular-nums'] }}>
            {time}
          </Text>
          <Text
            className={`ml-1.5 text-lg ${alarm.enabled ? 'text-neutral-700 dark:text-neutral-300' : 'text-neutral-400 dark:text-neutral-600'}`}>
            {period}
          </Text>
        </View>
        <Text className="text-sm text-neutral-500 dark:text-neutral-400" numberOfLines={1}>
          {[alarm.label, formatDays(alarm.days)].filter(Boolean).join(' · ')}
        </Text>
        {mission && (
          <Text className="mt-0.5 text-xs text-neutral-500 dark:text-neutral-400" numberOfLines={1}>
            {alarm.locked ? '🔒' : missionInfo(mission.type).emoji} {describeMission(mission)}
          </Text>
        )}
        {snoozed ? (
          <Text className="mt-1 text-xs font-medium text-[#FF8A3D]">
            Snoozed · rings {formatCountdown(alarm.snoozeUntil!, now)}
          </Text>
        ) : alarm.enabled && alarm.nextTriggerAt ? (
          <Text className="mt-1 text-xs text-neutral-400 dark:text-neutral-500">
            Rings {formatCountdown(alarm.nextTriggerAt, now)}
          </Text>
        ) : null}
      </View>
      <Switch
        value={alarm.enabled}
        onValueChange={(v) => KtAlarm?.setAlarmEnabled(alarm.id, v).catch(showError)}
        trackColor={{ true: ACCENT, false: '#9CA3AF55' }}
        thumbColor="#FFFFFF"
        accessibilityLabel={`Alarm ${time} ${period}`}
      />
    </Pressable>
  );
}

function EmptyState() {
  return (
    <View className="items-center px-10 pt-24">
      <Text className="text-6xl">⏰</Text>
      <Text className="mt-4 text-lg font-semibold text-neutral-900 dark:text-white">No alarms yet</Text>
      <Text className="mt-1 text-center text-sm text-neutral-500 dark:text-neutral-400">
        Tap + to set your first alarm.
      </Text>
    </View>
  );
}

function PlusIcon() {
  return (
    <View className="h-6 w-6 items-center justify-center">
      <View className="absolute h-[3px] w-6 rounded-full bg-white" />
      <View className="absolute h-6 w-[3px] rounded-full bg-white" />
    </View>
  );
}
