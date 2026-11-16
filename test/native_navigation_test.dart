import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:cbgpu_app/core/native_navigation.dart';

Map<String, dynamic> message() => {
  'visible': true,
  'tabs': [
    {
      'path': '/?mode=student',
      'label': '홈',
      'svg': '<svg><path d="m3 10 9-7 9 7v10H3Z"/></svg>',
    },
    {
      'path': '/study',
      'label': '자습 신청',
      'svg': '<svg><circle cx="12" cy="12" r="9"/></svg>',
    },
  ],
  'selected': 0,
  'background': 0xffffffff,
  'foreground': 0xff111111,
  'muted': 0xff777777,
  'border': 0xffeeeeee,
  'accent': 0xff4678ed,
  'selectedBackground': 0xffeef2fd,
};
void main() {
  test('only known relative workspace paths reach native navigation', () {
    for (final path in [
      'https://cbgpu.com/',
      '//evil.test/',
      '/api/auth',
      '/study?token=secret',
      '/\\evil',
      '/?mode=admin',
    ]) {
      expect(validNativeTabPath(path), isFalse, reason: path);
    }
    for (final path in [
      '/?mode=student',
      '/?mode=researcher',
      '/ai',
      '/study',
      '/dashboard',
      '/services',
      '/profile',
    ]) {
      expect(validNativeTabPath(path), isTrue, reason: path);
    }
  });
  test('capability parses actual web design and rejects invalid payloads', () {
    final state = NativeNavigationState.fromMessage(message())!;
    expect(state.tabs[0].label, '홈');
    expect(state.accent, const Color(0xff4678ed));
    expect(state.select(1).selected, 1);
    expect(NativeNavigationState.fromMessage({'visible': false}), isNull);
    expect(
      () => NativeNavigationState.fromMessage({...message(), 'selected': 9}),
      throwsFormatException,
    );
    expect(
      () => NativeNavigationState.fromMessage({
        ...message(),
        'background': 'red',
      }),
      throwsFormatException,
    );
  });
  testWidgets(
    'native tabs retain web labels and active semantics without WebView',
    (tester) async {
      int? selected;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            bottomNavigationBar: NativeWorkspaceNavigation(
              state: NativeNavigationState.fromMessage(message())!,
              onSelected: (i) => selected = i,
            ),
          ),
        ),
      );
      expect(find.text('홈'), findsOneWidget);
      expect(find.text('자습 신청'), findsOneWidget);
      await tester.tap(find.text('자습 신청'));
      expect(selected, 1);
      expect(tester.takeException(), isNull);
    },
  );
}
