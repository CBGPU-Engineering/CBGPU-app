import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:cbgpu_app/core/app_info.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.cbgpu/app_info');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('returns installed version from platform package metadata', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getAppInfo');
          return {'version': '1.0.8', 'buildNumber': 9, 'platform': 'android'};
        });
    expect((await installedAppInfo())['buildNumber'], 9);
  });
}
