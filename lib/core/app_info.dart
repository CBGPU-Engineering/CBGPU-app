import 'package:flutter/services.dart';

/// Installed package metadata, read from Android rather than a source constant.
Future<Map<String, dynamic>> installedAppInfo() async {
  final info = await const MethodChannel(
    'com.cbgpu/app_info',
  ).invokeMapMethod<String, dynamic>('getAppInfo');
  if (info == null) throw StateError('Installed app version unavailable');
  return info;
}
