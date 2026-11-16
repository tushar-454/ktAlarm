import type { AlarmMedia } from 'kt-alarm';

export const RINGTONE_PRESETS: AlarmMedia[] = [
  { uri: 'preset:chime', name: 'Morning chime' },
  { uri: 'preset:sunrise', name: 'Gentle sunrise' },
  { uri: 'preset:bell', name: 'Classic bells' },
];

export const BACKGROUND_PRESETS = [
  { uri: 'preset:dawn', name: 'Dawn', colors: ['#2B1942', '#C46462', '#F4B27D'] },
  { uri: 'preset:forest', name: 'Forest', colors: ['#071A1E', '#194B46', '#588973'] },
  { uri: 'preset:midnight', name: 'Midnight', colors: ['#080F2B', '#222A5C', '#4E427B'] },
  { uri: 'preset:sea', name: 'Sea', colors: ['#081F39', '#1B5871', '#61A4A8'] },
];

export const SLEEP_PRESETS: AlarmMedia[] = [
  { uri: 'preset:white', name: 'White noise' },
  { uri: 'preset:pink', name: 'Pink noise' },
  { uri: 'preset:brown', name: 'Brown noise' },
  { uri: 'preset:rain', name: 'Soft rain' },
  { uri: 'preset:ocean', name: 'Ocean waves' },
  { uri: 'preset:fan', name: 'Fan' },
];

export function backgroundSource(uri: string) {
  const preset = BACKGROUND_PRESETS.find((p) => p.uri === uri);
  if (!preset) return { uri };
  const [top, middle, bottom] = preset.colors;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="700"><defs><linearGradient id="g" x2="0" y2="1"><stop stop-color="${top}"/><stop offset=".5" stop-color="${middle}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs><path fill="url(#g)" d="M0 0h400v700H0z"/></svg>`;
  return { uri: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}` };
}
