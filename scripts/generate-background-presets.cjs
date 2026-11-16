// Bundled PNG previews avoid platform-dependent decoding of SVG data URIs.
// Keep these palettes in sync with Android PresetMedia and iOS AlarmMissionView.
const fs = require('node:fs');
const path = require('node:path');
const { PNG } = require('pngjs');

const palettes = {
  dark: ['#0E1116', '#0E1116', '#0E1116'],
  dawn: ['#2B1942', '#C46462', '#F4B27D'],
  forest: ['#071A1E', '#194B46', '#588973'],
  midnight: ['#080F2B', '#222A5C', '#4E427B'],
  sea: ['#081F39', '#1B5871', '#61A4A8'],
};
const directory = path.join(__dirname, '../assets/images/backgrounds');
fs.mkdirSync(directory, { recursive: true });
for (const [name, palette] of Object.entries(palettes)) {
  const colors = palette.map((hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)));
  const image = new PNG({ width: 400, height: 700 });
  for (let y = 0; y < image.height; y++) {
    const position = y / (image.height - 1) * 2;
    const segment = Math.min(1, Math.floor(position));
    const fraction = position - segment;
    for (let x = 0; x < image.width; x++) {
      const offset = (y * image.width + x) * 4;
      for (let channel = 0; channel < 3; channel++) {
        image.data[offset + channel] = Math.round(
          colors[segment][channel] * (1 - fraction) + colors[segment + 1][channel] * fraction,
        );
      }
      image.data[offset + 3] = 255;
    }
  }
  fs.writeFileSync(path.join(directory, `${name}.png`), PNG.sync.write(image));
}
