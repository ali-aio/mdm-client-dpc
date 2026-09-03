# Keep the DeviceAdminReceiver + services referenced from the manifest / by name in adb commands.
-keep class com.aioapp.mdm.agent.MdmDeviceAdminReceiver { *; }
-keep class com.aioapp.mdm.agent.** extends android.app.Service { *; }
-keep class com.aioapp.mdm.agent.** extends android.content.BroadcastReceiver { *; }

# OkHttp / Okio ship their own consumer rules; nothing extra needed here.
