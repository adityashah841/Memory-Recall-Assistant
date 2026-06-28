// Smoke test: the app boots and shows the four-tab navigation shell.
//
// The backend channel has no platform implementation under `flutter test`, so the
// controllers' initial loads fail gracefully (broad catch) — the UI must still build.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:memory_recall_app/app.dart';

void main() {
  testWidgets('boots into the four-tab shell', (WidgetTester tester) async {
    await tester.pumpWidget(const MemoryRecallApp());
    await tester.pump();

    expect(find.byType(NavigationBar), findsOneWidget);
    expect(find.text('Recall'), findsWidgets);
    expect(find.text('Timeline'), findsOneWidget);
    expect(find.text('Enroll'), findsOneWidget);
    expect(find.text('Debug'), findsOneWidget);
  });
}
