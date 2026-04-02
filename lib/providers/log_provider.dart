import 'package:flutter_riverpod/flutter_riverpod.dart';

class LogNotifier extends Notifier<List<String>> {
  static const _maxLines = 20;

  @override
  List<String> build() => const [];

  void add(String message) {
    final next = [...state, message];
    state = next.length > _maxLines
        ? next.sublist(next.length - _maxLines)
        : next;
  }
}

final logProvider =
    NotifierProvider<LogNotifier, List<String>>(LogNotifier.new);
