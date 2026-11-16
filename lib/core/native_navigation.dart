import 'package:flutter/material.dart';
import 'package:path_parsing/path_parsing.dart';
import 'package:xml/xml.dart';

/// The web workspace supplies its actual labels, Lucide paths and resolved colors.
/// The app renders only the navigation controls; web feature screens stay intact.
class NativeNavigationTab {
  const NativeNavigationTab(this.path, this.label, this.svg);
  final String path, label, svg;
}

class NativeNavigationState {
  const NativeNavigationState({
    required this.tabs,
    required this.selected,
    required this.background,
    required this.foreground,
    required this.muted,
    required this.border,
    required this.accent,
    required this.selectedBackground,
  });
  final List<NativeNavigationTab> tabs;
  final int selected;
  final Color background, foreground, muted, border, accent, selectedBackground;
  static NativeNavigationState? fromMessage(Map<String, dynamic> data) {
    if (data['visible'] == false) return null;
    final items = data['tabs'];
    if (items is! List || items.length < 2 || items.length > 6)
      throw const FormatException('Invalid native tabs');
    final tabs = items
        .map((value) {
          if (value is! Map) throw const FormatException('Invalid native tab');
          final path = value['path'],
              label = value['label'],
              svg = value['svg'];
          if (path is! String ||
              !validNativeTabPath(path) ||
              label is! String ||
              label.isEmpty ||
              label.length > 30 ||
              svg is! String ||
              svg.length > 4000)
            throw const FormatException('Invalid native tab');
          return NativeNavigationTab(path, label, svg);
        })
        .toList(growable: false);
    final selected = data['selected'];
    if (selected is! int || selected < 0 || selected >= tabs.length)
      throw const FormatException('Invalid selected tab');
    Color color(String key) {
      final v = data[key];
      if (v is! int || v < 0 || v > 0xffffffff)
        throw const FormatException('Invalid native color');
      return Color(v);
    }

    return NativeNavigationState(
      tabs: tabs,
      selected: selected,
      background: color('background'),
      foreground: color('foreground'),
      muted: color('muted'),
      border: color('border'),
      accent: color('accent'),
      selectedBackground: color('selectedBackground'),
    );
  }

  NativeNavigationState select(int index) => NativeNavigationState(
    tabs: tabs,
    selected: index,
    background: background,
    foreground: foreground,
    muted: muted,
    border: border,
    accent: accent,
    selectedBackground: selectedBackground,
  );
}

bool validNativeTabPath(String path) {
  if (path.contains(RegExp(r'[\\\r\n]')) || path.startsWith('//')) return false;
  final uri = Uri.tryParse(path);
  return uri != null &&
      !uri.hasScheme &&
      !uri.hasAuthority &&
      const {
        '/',
        '/ai',
        '/study',
        '/dashboard',
        '/services',
        '/profile',
      }.contains(uri.path) &&
      uri.queryParameters.keys.every((k) => k == 'mode') &&
      (!uri.queryParameters.containsKey('mode') ||
          const {
            'student',
            'researcher',
          }.contains(uri.queryParameters['mode']));
}

class NativeWorkspaceNavigation extends StatelessWidget {
  const NativeWorkspaceNavigation({
    super.key,
    required this.state,
    required this.onSelected,
  });
  final NativeNavigationState state;
  final ValueChanged<int> onSelected;
  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: state.background,
      border: Border(top: BorderSide(color: state.border)),
    ),
    child: SafeArea(
      top: false,
      child: SizedBox(
        height: 68,
        child: Row(
          children: List.generate(state.tabs.length, (index) {
            final tab = state.tabs[index],
                active = index == state.selected,
                color = active ? state.accent : state.muted;
            return Expanded(
              child: Semantics(
                selected: active,
                button: true,
                label: tab.label,
                excludeSemantics: true,
                child: Material(
                  color: Colors.transparent,
                  child: InkWell(
                    onTap: () => onSelected(index),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        if (active)
                          Positioned(
                            top: 6,
                            bottom: 4,
                            left: 12,
                            right: 12,
                            child: DecoratedBox(
                              decoration: BoxDecoration(
                                color: state.selectedBackground,
                                borderRadius: BorderRadius.circular(17),
                              ),
                            ),
                          ),
                        Center(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              CustomPaint(
                                size: const Size(20, 20),
                                painter: _WebIconPainter(tab.svg, color),
                              ),
                              const SizedBox(height: 5),
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 3,
                                ),
                                child: Text(
                                  tab.label,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontFamily: 'Pretendard',
                                    fontSize: 12,
                                    height: 1.45,
                                    color: color,
                                    fontWeight: active
                                        ? FontWeight.w600
                                        : FontWeight.w400,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            );
          }),
        ),
      ),
    ),
  );
}

/// Render the same SVG geometry sent by the site's Lucide icons. No scripts,
/// external assets, foreignObject, filters or arbitrary SVG resources are loaded.
class _WebIconPainter extends CustomPainter {
  _WebIconPainter(this.svg, this.color);
  final String svg;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..color = color
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    canvas.save();
    canvas.scale(size.width / 24, size.height / 24);
    try {
      final root = XmlDocument.parse(svg).rootElement;
      double n(XmlElement el, String key, [double fallback = 0]) =>
          double.tryParse(el.getAttribute(key) ?? '') ?? fallback;
      for (final el in root.descendants.whereType<XmlElement>()) {
        switch (el.name.local) {
          case 'path':
            final data = el.getAttribute('d');
            if (data != null) {
              final target = _PathProxy();
              writeSvgPathDataToPath(data, target);
              canvas.drawPath(target.path, paint);
            }
            break;
          case 'line':
            canvas.drawLine(
              Offset(n(el, 'x1'), n(el, 'y1')),
              Offset(n(el, 'x2'), n(el, 'y2')),
              paint,
            );
            break;
          case 'circle':
            canvas.drawCircle(
              Offset(n(el, 'cx'), n(el, 'cy')),
              n(el, 'r'),
              paint,
            );
            break;
          case 'ellipse':
            canvas.drawOval(
              Rect.fromCenter(
                center: Offset(n(el, 'cx'), n(el, 'cy')),
                width: n(el, 'rx') * 2,
                height: n(el, 'ry') * 2,
              ),
              paint,
            );
            break;
          case 'rect':
            canvas.drawRRect(
              RRect.fromRectAndRadius(
                Rect.fromLTWH(
                  n(el, 'x'),
                  n(el, 'y'),
                  n(el, 'width'),
                  n(el, 'height'),
                ),
                Radius.circular(n(el, 'rx')),
              ),
              paint,
            );
            break;
          case 'polyline':
          case 'polygon':
            final values = (el.getAttribute('points') ?? '')
                .split(RegExp(r'[\s,]+'))
                .where((s) => s.isNotEmpty)
                .map(double.parse)
                .toList();
            final path = Path();
            for (var i = 0; i + 1 < values.length; i += 2) {
              if (i == 0) {
                path.moveTo(values[i], values[i + 1]);
              } else {
                path.lineTo(values[i], values[i + 1]);
              }
            }
            if (el.name.local == 'polygon') path.close();
            canvas.drawPath(path, paint);
            break;
        }
      }
    } catch (_) {
      canvas.drawCircle(const Offset(12, 12), 7, paint);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_WebIconPainter old) =>
      old.svg != svg || old.color != color;
}

class _PathProxy extends PathProxy {
  final path = Path();
  @override
  void close() => path.close();
  @override
  void moveTo(double x, double y) => path.moveTo(x, y);
  @override
  void lineTo(double x, double y) => path.lineTo(x, y);
  @override
  void cubicTo(
    double x1,
    double y1,
    double x2,
    double y2,
    double x3,
    double y3,
  ) => path.cubicTo(x1, y1, x2, y2, x3, y3);
}
