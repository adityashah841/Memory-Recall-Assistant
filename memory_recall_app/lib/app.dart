import 'package:flutter/material.dart';

import 'core/platform/memory_backend_channel.dart';
import 'core/theme/app_theme.dart';
import 'features/debug/debug_screen.dart';
import 'features/enrollment/enrollment_screen.dart';
import 'features/recall/recall_screen.dart';
import 'features/timeline/timeline_screen.dart';

/// App bootstrap: theming + routing across the four screens (Section 13.1).
class MemoryRecallApp extends StatelessWidget {
  const MemoryRecallApp({super.key});

  @override
  Widget build(BuildContext context) {
    // Single backend channel shared across screens (DI seam; Section 13.1).
    final backend = MemoryBackendChannel();
    return MaterialApp(
      title: 'Memory Recall',
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      home: HomeShell(backend: backend),
    );
  }
}

/// Bottom-navigation shell across Recall / Timeline / Enrollment / Debug.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.backend});

  final MemoryBackendChannel backend;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  late final List<Widget> _screens = [
    RecallScreen(backend: widget.backend),
    TimelineScreen(backend: widget.backend),
    EnrollmentScreen(backend: widget.backend),
    DebugScreen(backend: widget.backend),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _index, children: _screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: (i) => setState(() => _index = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.center_focus_strong_outlined),
            selectedIcon: Icon(Icons.center_focus_strong),
            label: 'Recall',
          ),
          NavigationDestination(
            icon: Icon(Icons.timeline_outlined),
            selectedIcon: Icon(Icons.timeline),
            label: 'Timeline',
          ),
          NavigationDestination(
            icon: Icon(Icons.person_add_alt_outlined),
            selectedIcon: Icon(Icons.person_add_alt),
            label: 'Enroll',
          ),
          NavigationDestination(
            icon: Icon(Icons.bug_report_outlined),
            selectedIcon: Icon(Icons.bug_report),
            label: 'Debug',
          ),
        ],
      ),
    );
  }
}
