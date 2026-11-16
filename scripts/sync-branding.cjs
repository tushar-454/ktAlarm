// Apply only icon, splash and root background plugins to the committed native projects.
// This deliberately avoids prebuild, which would regenerate unrelated native settings.
const { compileModsAsync, withPlugins } = require('@expo/config-plugins');
const { withAndroidIcons } = require('@expo/prebuild-config/build/plugins/icons/withAndroidIcons');
const { withIosIcons } = require('@expo/prebuild-config/build/plugins/icons/withIosIcons');

async function syncBranding() {
  const projectRoot = require('node:path').resolve(__dirname, '..');
  let config = structuredClone(require('../app.json').expo);
  config._internal = { projectRoot };
  const splash = config.plugins.find((plugin) => Array.isArray(plugin) && plugin[0] === 'expo-splash-screen')[1];
  config = withAndroidIcons(config);
  config = withIosIcons(config);
  config = withPlugins(config, [['expo-splash-screen', splash], 'expo-system-ui']);
  await compileModsAsync(config, { projectRoot, platforms: ['android', 'ios'] });
}

syncBranding().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
