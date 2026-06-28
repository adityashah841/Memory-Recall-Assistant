import 'package:flutter/material.dart';

/// Central app theming (IMPLEMENTATION_PLAN.md Section 12 `core/theme`).
class AppTheme {
  AppTheme._();

  static const Color _seed = Color(0xFF4C5BD4);

  static ThemeData get light => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: _seed),
      );

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _seed,
          brightness: Brightness.dark,
        ),
      );
}
