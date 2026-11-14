# R8 rules applied to the app that uses this module (consumerProguardFiles).

# ML Kit (barcode + pose) finds its component registrars by class name (manifest meta-data) and
# creates them with the no-arg constructor via reflection. R8 full mode keeps those classes but
# strips the constructor, so ML Kit starts with no components and BarcodeScanning.getClient() /
# PoseDetection.getClient() throw a NullPointerException in release builds only.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
  public <init>();
}

# Same problem for Room databases (WorkManager's, pulled in by ML Kit pose detection): Room
# instantiates the generated <Name>_Impl class by reflection. Without its constructor the app
# crashes on launch while WorkManager initialises.
-keep class * extends androidx.room.RoomDatabase {
  <init>();
}
