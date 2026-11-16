import type { AlarmMedia } from 'kt-alarm';

export const RINGTONE_PRESETS: AlarmMedia[] = [
  { uri: 'preset:chime', name: 'Morning chime' },
  { uri: 'preset:sunrise', name: 'Gentle sunrise' },
  { uri: 'preset:bell', name: 'Classic bells' },
];

export const BACKGROUND_PRESETS = [
  { uri: 'preset:dawn', name: 'Dawn', image: require('../../assets/images/backgrounds/dawn.png') },
  { uri: 'preset:forest', name: 'Forest', image: require('../../assets/images/backgrounds/forest.png') },
  { uri: 'preset:midnight', name: 'Midnight', image: require('../../assets/images/backgrounds/midnight.png') },
  { uri: 'preset:sea', name: 'Sea', image: require('../../assets/images/backgrounds/sea.png') },
];

export const SLEEP_PRESETS: AlarmMedia[] = [
  { uri: 'preset:white', name: 'White noise' },
  { uri: 'preset:pink', name: 'Pink noise' },
  { uri: 'preset:brown', name: 'Brown noise' },
  { uri: 'preset:rain', name: 'Soft rain' },
  { uri: 'preset:ocean', name: 'Ocean waves' },
  { uri: 'preset:fan', name: 'Fan' },
];

export function backgroundSource(uri: string | null) {
  if (!uri) return require('../../assets/images/backgrounds/dark.png');
  const preset = BACKGROUND_PRESETS.find((p) => p.uri === uri);
  return preset?.image ?? { uri };
}
