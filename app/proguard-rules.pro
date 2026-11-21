# Room, WorkManager and Compose ship their own consumer rules. Add app-specific keeps here if needed.
-keepattributes *Annotation*
-keep class com.betslippro.app.DailySyncWorker { *; }
