import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import 'core/alarm_service.dart';
import 'core/home_widgets.dart';
import 'core/supabase_config.dart';
import 'core/theme.dart';
import 'core/theme_controller.dart';
import 'core/webview_shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Supabase.initialize(
    url: SupabaseConfig.url,
    publishableKey: SupabaseConfig.anonKey,
  );
  await initializeHomeWidgets();
  await AlarmService.instance.init();
  runApp(const CbgpuApp());
}

/// Hybrid app: shared web feature screens retain the website design while
/// native Flutter controls own acknowledged workspace navigation, haptics,
/// system back behavior, file actions, notifications and session persistence.
class CbgpuApp extends StatelessWidget {
  const CbgpuApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: themeModeNotifier,
      builder: (context, mode, _) => MaterialApp(
        title: 'CBGPU',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        darkTheme: AppTheme.dark(),
        themeMode: mode,
        home: const WebViewShellScreen(),
      ),
    );
  }
}
