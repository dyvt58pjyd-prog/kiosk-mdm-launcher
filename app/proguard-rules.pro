# Proguard rules for Kiosk MDM Launcher
-keep class com.kiosk.launcher.receiver.KioskAdminReceiver { *; }
-keep class com.kiosk.launcher.receiver.BootReceiver { *; }
-keep class com.kiosk.launcher.dpc.KioskPolicyManager { *; }
-keep class com.kiosk.launcher.mdm.** { *; }
