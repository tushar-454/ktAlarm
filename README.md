# Welcome to your Expo app 👋

This is an [Expo](https://expo.dev) project created with [`create-expo-app`](https://www.npmjs.com/package/create-expo-app).

## Native configuration and alarm audio

This repository intentionally commits `android/` and `ios/`. EAS builds these native projects;
it does not regenerate them from `app.json`. Native manifests, resources and Xcode settings are
the source of truth for native behavior. When changing orientation, icons, schemes, appearance,
platform identifiers or config plugins, update/check the corresponding native projects too.
Do not delete or regenerate the native projects as a way to clear a Doctor warning.

The Expo Doctor `appConfigFieldsNotSyncedCheck` is disabled for this intentional native-project
workflow, as supported by Expo. All dependency, config-schema and other Doctor checks remain enabled.

Android sleep sounds use a separate media foreground service, loop offline, and stop at the selected
deadline or as soon as a wake-up alarm rings. They never modify alarm scheduling. The notification
provides a Stop action. Durations are elapsed-time based and support 1–720 minutes.
Built-in noise loops and ringtones are original synthesized PCM audio; regenerate them with
`python3 scripts/generate-audio-presets.py`. No third-party recordings are bundled.

iOS also implements mission chains and previews, barcode registration, camera squat counting,
walking/shake detection, custom ringtones/backgrounds, snooze limits, and background sleep sounds.
See [iOS feature parity and device checks](IOS_PARITY.md) for the implementation map and platform
limits. Wake-up alarms require iOS 26+. Rebuild the native app after pulling these changes;
JavaScript reloads and Expo Go cannot install the Swift implementation or its bundled sounds.

## Installable iPhone build

Register each iPhone before building, then create the signed IPA in EAS:

```bash
npm run ios:device:register
npm run build:ipa
```

Sign in to Expo and your paid Apple Developer account when prompted. The `preview` profile uses
internal distribution and a physical-device Release build with bundled JavaScript, so Metro is
not required. After the build succeeds, open EAS's install link on the registered iPhone to install
the app. Only devices included in the signing profile can install this build; after registering
another device, build again and include it when EAS prompts for the device list.
See [Expo's internal distribution guide](https://docs.expo.dev/build/internal-distribution/).

## Get started

1. Install dependencies

   ```bash
   npm install
   ```

2. Start the app

   ```bash
   npx expo start
   ```

In the output, you'll find options to open the app in a

- [development build](https://docs.expo.dev/develop/development-builds/introduction/)
- [Android emulator](https://docs.expo.dev/workflow/android-studio-emulator/)
- [iOS simulator](https://docs.expo.dev/workflow/ios-simulator/)
- [Expo Go](https://expo.dev/go), a limited sandbox for trying out app development with Expo

You can start developing by editing the files inside the **src/app** directory. This project uses [file-based routing](https://docs.expo.dev/router/introduction).

## Get a fresh project

When you're ready, run:

```bash
npm run reset-project
```

This command will move the starter code to the **example** directory and create a blank **src/app** directory where you can start developing.

### Other setup steps

- To set up ESLint for linting, run `npx expo lint`, or follow our guide on ["Using ESLint and Prettier"](https://docs.expo.dev/guides/using-eslint/)
- If you'd like to set up unit testing, follow our guide on ["Unit Testing with Jest"](https://docs.expo.dev/develop/unit-testing/)
- Learn more about the TypeScript setup in this template in our guide on ["Using TypeScript"](https://docs.expo.dev/guides/typescript/)

## Learn more

To learn more about developing your project with Expo, look at the following resources:

- [Expo documentation](https://docs.expo.dev/): Learn fundamentals, or go into advanced topics with our [guides](https://docs.expo.dev/guides).
- [Learn Expo tutorial](https://docs.expo.dev/tutorial/introduction/): Follow a step-by-step tutorial where you'll create a project that runs on Android, iOS, and the web.

## Join the community

Join our community of developers creating universal apps.

- [Expo on GitHub](https://github.com/expo/expo): View our open source platform and contribute.
- [Discord community](https://chat.expo.dev): Chat with Expo users and ask questions.
