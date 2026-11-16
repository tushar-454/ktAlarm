const { withAppDelegate } = require('expo/config-plugins');

const marker = '// ktAlerm AlarmKit intent registration';
const registration = `${marker}
#if canImport(AlarmKit)
@available(iOS 26.0, *)
struct KtAlarmAppIntentPackage: AppIntentsPackage {
  static var includedPackages: [any AppIntentsPackage.Type] { [KtAlarmIntentPackage.self] }
}
#endif
`;

function registerIntents(contents) {
  if (contents.includes(marker)) return contents;
  return `import AppIntents\nimport KtAlarm\n${contents.trimEnd()}\n\n${registration}`;
}

module.exports = function withKtAlarmIos(config) {
  return withAppDelegate(config, (mod) => {
    if (mod.modResults.language !== 'swift') {
      throw new Error('ktAlerm AlarmKit intents require a Swift AppDelegate.');
    }
    mod.modResults.contents = registerIntents(mod.modResults.contents);
    return mod;
  });
};
module.exports.registerIntents = registerIntents;
