import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Wraps any widget with D-pad / enter key activation and exposes focus state
/// to the builder so the caller can apply custom focus styling.
class TvFocusable extends StatefulWidget {
  final Widget Function(BuildContext context, bool isFocused) builder;
  final VoidCallback? onActivate;
  final bool autofocus;
  final FocusNode? focusNode;

  const TvFocusable({
    super.key,
    required this.builder,
    this.onActivate,
    this.autofocus = false,
    this.focusNode,
  });

  @override
  State<TvFocusable> createState() => _TvFocusableState();
}

class _TvFocusableState extends State<TvFocusable> {
  bool _focused = false;

  void _activate() => widget.onActivate?.call();

  @override
  Widget build(BuildContext context) {
    return Focus(
      autofocus: widget.autofocus,
      focusNode: widget.focusNode,
      onFocusChange: (f) => setState(() => _focused = f),
      onKeyEvent: (_, event) {
        if (event is KeyDownEvent &&
            (event.logicalKey == LogicalKeyboardKey.select ||
                event.logicalKey == LogicalKeyboardKey.enter ||
                event.logicalKey == LogicalKeyboardKey.space ||
                event.logicalKey == LogicalKeyboardKey.gameButtonA)) {
          _activate();
          return KeyEventResult.handled;
        }
        return KeyEventResult.ignored;
      },
      child: GestureDetector(
        onTap: _activate,
        child: widget.builder(context, _focused),
      ),
    );
  }
}
